"""Run each demo, try its important failure cases, and check the stored results. Fixture mail never leaves Docker."""
import json
import os
import time
import urllib.error
import urllib.request
import uuid

APP = "http://app:8080"
OPERATOR = {"X-Demo-Operator": "local-operator"}


def request(url, data=None, headers=None, expected=200):
    encoded = json.dumps(data).encode() if data is not None else None
    supplied = {"Content-Type": "application/json", **(headers or {})}
    try:
        response = urllib.request.urlopen(urllib.request.Request(url, data=encoded, headers=supplied), timeout=30)
    except urllib.error.HTTPError as failed:
        response = failed
    with response:
        body = response.read()
        assert response.status == expected, (url, response.status, body[:300])
        return json.loads(body) if body else None


def await_value(read, predicate, description, timeout=60):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = read()
            if predicate(last):
                return last
        except (OSError, ValueError):
            pass
        time.sleep(0.1)
    raise AssertionError((description, last))


def tenant(name):
    return {"X-Demo-Tenant-Token": f"tenant-{name}-local"}


def tenant_report(name, envelope):
    report = ("From: fixture-postmaster@example.test\r\nTo: bounce@example.test\r\nMIME-Version: 1.0\r\n"
              "Subject: Explicitly injected synthetic notification\r\n"
              "Content-Type: multipart/report; report-type=delivery-status; boundary=dsn\r\n\r\n"
              "--dsn\r\nContent-Type: text/plain\r\n\r\nNot Internet delivery evidence.\r\n"
              "--dsn\r\nContent-Type: message/delivery-status\r\n\r\nReporting-MTA: dns; smtp.example.test\r\n"
              f"Original-Envelope-Id: {envelope}\r\n\r\nFinal-Recipient: rfc822; reader@example.test\r\n"
              "Action: failed\r\nStatus: 5.1.1\r\n\r\n--dsn--\r\n").encode()
    supplied = {"Content-Type": "message/rfc822", **tenant(name)}
    with urllib.request.urlopen(urllib.request.Request(f"{APP}/reports", data=report, headers=supplied), timeout=10) as response:
        return json.load(response)


def relaydesk():
    prefix = uuid.uuid4().hex[:10]
    prove_tenant_isolation(prefix)
    selected = prove_route_replacement(prefix)
    prove_feedback_stays_with_its_tenant(selected)
    route = prove_failed_replacement_pauses_only_its_tenant(prefix)
    print(json.dumps({"pattern": "relaydesk", "tenantIsolation": True, "fairTurns": True,
                      "credentialRevision": next(item["revision"] for item in route if item["tenant"] == "a"),
                      "oldRouteRetired": True, "partialAndUnknownHeld": True, "crossTenantFeedbackRejected": True,
                      "failedReplacementPausedOnlyItsTenant": True}), flush=True)


def prove_tenant_isolation(prefix):
    request(f"{APP}/jobs?tenant=b&id={prefix}_forbidden", {}, tenant("a"), 403)
    before = request("http://smtp-a:8025/stats")["dataStarted"]
    request("http://smtp-a:8025/control", {"blocked": True})
    try:
        request(f"{APP}/jobs?id={prefix}_a", {}, tenant("a"))
        await_value(lambda: request("http://smtp-a:8025/stats"), lambda state: state["dataStarted"] > before,
                    "this tenant A send reaches the fixture barrier")
        request(f"{APP}/jobs?id={prefix}_b", {}, tenant("b"))
        await_value(lambda: request(f"{APP}/jobs", headers=tenant("b")),
                    lambda rows: any(row["id"] == f"{prefix}_b" and row["state"] == "SUBMITTED" for row in rows),
                    "tenant B progresses while tenant A is still blocked")
    finally:
        request("http://smtp-a:8025/control", {"blocked": False})
    await_value(lambda: request(f"{APP}/jobs", headers=tenant("a")),
                lambda rows: any(row["id"] == f"{prefix}_a" and row["state"] == "SUBMITTED" for row in rows), "tenant A completes")


def prove_route_replacement(prefix):
    request(f"{APP}/jobs?id={prefix}_a", {}, tenant("a"))
    previous_revision = next(item["revision"] for item in request(f"{APP}/routes", headers=OPERATOR) if item["tenant"] == "a")
    request("http://smtp-a:8025/control", {"password": "rotated-local-only"})
    route = request(f"{APP}/routes?tenant=a", {"password": "rotated-local-only"}, OPERATOR)
    replacement = next(item for item in route if item["tenant"] == "a")
    assert replacement["revision"] > previous_revision and not replacement["paused"], replacement
    for scenario in ("happy", "partial", "unknown"):
        request(f"{APP}/jobs?id={prefix}_{scenario}&scenario={scenario}", {}, tenant("a"))
    rows = await_value(lambda: request(f"{APP}/jobs", headers=tenant("a")),
                       lambda rows: sum(row["id"].startswith(prefix) and row["state"] in ("SUBMITTED", "HELD") for row in rows) == 4,
                       "replacement and adverse sends complete")
    selected = {row["id"].removeprefix(prefix + "_"): row for row in rows if row["id"].startswith(prefix)}
    assert selected["happy"]["revision"] == replacement["revision"]
    assert selected["partial"]["state"] == "HELD" and selected["unknown"]["state"] == "HELD"
    assert selected["partial"]["submission"] == "PARTIALLY_ACCEPTED", selected["partial"]
    assert selected["unknown"]["submission"] == "UNKNOWN", selected["unknown"]
    a = request("http://smtp-a:8025/stats")["messages"]
    b = request("http://smtp-b:8025/stats")["messages"]
    assert len([item for item in a if item["messageId"] == selected["a"]["message_id"]]) == 1
    assert all("@a.example.test" in item["sender"] for item in a)
    assert all("@b.example.test" in item["sender"] for item in b)
    return selected


def prove_feedback_stays_with_its_tenant(selected):
    envelope = selected["a"]["message_id"].removeprefix("<").split("@", 1)[0]
    assert tenant_report("a", envelope)["result"] == "CORRELATED"
    assert tenant_report("b", envelope)["result"] == "UNCORRELATED"


def prove_failed_replacement_pauses_only_its_tenant(prefix):
    request(f"{APP}/routes?tenant=a", {"password": "wrong-local"}, OPERATOR, 500)
    route = request(f"{APP}/routes", headers=OPERATOR)
    assert any(item["tenant"] == "a" and item["paused"] for item in route)
    request(f"{APP}/jobs?id={prefix}_paused", {}, tenant("a"))
    request(f"{APP}/jobs?id={prefix}_independent", {}, tenant("b"))
    await_value(lambda: request(f"{APP}/jobs", headers=tenant("b")),
                lambda rows: any(row["id"] == prefix + "_independent" and row["state"] == "SUBMITTED" for row in rows),
                "another tenant progresses while replacement is paused")
    assert any(row["id"] == prefix + "_paused" and row["state"] == "READY" for row in request(f"{APP}/jobs", headers=tenant("a")))
    route = request(f"{APP}/routes?tenant=a", {"password": "rotated-local-only"}, OPERATOR)
    await_value(lambda: request(f"{APP}/jobs", headers=tenant("a")),
                lambda rows: any(row["id"] == prefix + "_paused" and row["state"] == "SUBMITTED" for row in rows),
                "approved replacement resumes held admission")
    return route


def polar():
    prefix = uuid.uuid4().hex[:10]
    await_value(lambda: request("http://app-second:8080/health"), lambda state: state["status"] == "UP", "second dispatcher readiness")
    bulk_proof = prove_shared_limits_do_not_starve_urgent_work(prefix)
    proof = prove_primary_recovery_and_protected_archive(prefix)
    prove_failed_result_storage_is_visible(prefix + "_observer")
    print(json.dumps({"pattern": "polar-meridian", "urgentNotStarved": True, "sharedBulkCharges": 2,
                      "failoverAndRecovery": True, "protectedArchive": proof, "bulkProtectedArchive": bulk_proof,
                      "observerFailureVisible": True}), flush=True)


def prove_shared_limits_do_not_starve_urgent_work(prefix):
    request(f"{APP}/limits", {"workload": "BULK", "perSecond": 0, "perDay": 2}, OPERATOR)
    for index in range(6):
        request(f"{APP}/jobs?id={prefix}_bulk_{index}&workload=BULK&scenario=protected", {})
    request(f"{APP}/jobs?id={prefix}_urgent", {})
    await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["id"] == f"{prefix}_urgent" and row["state"] == "SUBMITTED" for row in rows),
                "urgent work passes held bulk backlog")
    request(f"{APP}/limits", {"workload": "BULK", "perSecond": 1, "perDay": 2}, OPERATOR)
    rows = await_value(lambda: request(f"{APP}/jobs"),
                       lambda rows: sum(row["id"].startswith(prefix + "_bulk_") and row["state"] == "SUBMITTED" for row in rows) == 2,
                       "two-instance allowance ceiling")
    assert sum(row["workload"] == "BULK" for row in request(f"{APP}/limits")) == 2
    # Concurrent dispatchers need not finish jobs in enqueue order. Inspect a protected job that actually used the allowance.
    submitted = next(row["id"] for row in rows if row["id"].startswith(prefix + "_bulk_") and row["state"] == "SUBMITTED")
    proof = request(f"{APP}/archive-proof?id={submitted}")
    assert proof["sameBytes"] and proof["verification"] == "VALID" and proof["readable"], proof
    return proof


def prove_primary_recovery_and_protected_archive(prefix):
    request("http://urgent-a:8025/control", {"online": False, "dropConnections": True})
    before_b = len(request("http://urgent-b:8025/stats")["messages"])
    request(f"{APP}/jobs?id={prefix}_failover", {})
    await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["id"] == f"{prefix}_failover" and row["state"] == "SUBMITTED" for row in rows), "approved failover")
    assert len(request("http://urgent-b:8025/stats")["messages"]) == before_b + 1
    request("http://urgent-a:8025/control", {"online": True})
    # The configured one-second reconsideration interval is intentional behavior, not an asserted SMTP performance threshold.
    due = time.monotonic() + 1.2
    await_value(time.monotonic, lambda now: now >= due, "primary reconsideration becomes due")
    before_a = len(request("http://urgent-a:8025/stats")["messages"])
    request(f"{APP}/jobs?id={prefix}_protected&scenario=protected", {})
    await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["id"] == f"{prefix}_protected" and row["state"] == "SUBMITTED" for row in rows), "protected archived submission")
    assert len(request("http://urgent-a:8025/stats")["messages"]) == before_a + 1
    proof = request(f"{APP}/archive-proof?id={prefix}_protected")
    assert proof["sameBytes"] and proof["verification"] == "VALID" and proof["readable"], proof
    return proof


def result_write_failed_for(events, request_id):
    return any(row["kind"] == "RESULT_WRITE_FAILED" and f"requestId={request_id};" in row["detail"] for row in events)


def prove_failed_result_storage_is_visible(request_id):
    request(f"{APP}/observer-failure", {"enabled": True}, OPERATOR)
    request("http://app-second:8080/observer-failure", {"enabled": True}, OPERATOR)
    try:
        request(f"{APP}/jobs?id={request_id}", {})
        await_value(lambda: request(f"{APP}/events"), lambda rows: result_write_failed_for(rows, request_id),
                    "failed result storage is visible for this job, not an earlier experiment")
    finally:
        request(f"{APP}/observer-failure", {"enabled": False}, OPERATOR)
        request("http://app-second:8080/observer-failure", {"enabled": False}, OPERATOR)


def handoff():
    prefix = uuid.uuid4().hex[:10]
    id = "happy_" + prefix
    verify_protected_handoff(id)
    if os.environ.get("HANDOFF_MODE") != "rabbitmq":
        verify_uncertain_and_refused_http_handoffs(prefix)
    else:
        previous = request("http://gateway:8080/dead-letters")["count"]
        request("http://gateway:8080/poison-package", {})
        await_value(lambda: request("http://gateway:8080/dead-letters"), lambda evidence: evidence["count"] > previous,
                    "this invalid broker package is retained for review")
    print(json.dumps({"pattern": "custom-handoff", "mode": os.environ.get("HANDOFF_MODE", "https"),
                      "smtpServer": False, "preparedProtectionVerified": True, "duplicateSuppressed": True, "smtpAcceptance": "UNKNOWN"}), flush=True)


def verify_protected_handoff(id):
    request(f"{APP}/jobs?id={id}", {})
    request(f"{APP}/jobs?id={id}", {})
    packages = await_value(lambda: request("http://gateway:8080/packages"), lambda rows: any(row["id"] == id for row in rows), "non-SMTP consumer processes package")
    result = request(f"{APP}/jobs?id={id}")
    assert result["completed"] and result["smtpAcceptance"] == "UNKNOWN", result
    matching = [row for row in packages if row["id"] == id]
    assert len(matching) == 1 and matching[0]["signature"] == "VALID" and matching[0]["decrypted"]
    assert "archive@example.test" in matching[0]["recipients"]


def verify_uncertain_and_refused_http_handoffs(prefix):
    request(f"{APP}/jobs?id=unknown_{prefix}", {})
    assert request(f"{APP}/jobs?id=unknown_{prefix}")["state"] == "HELD"
    await_value(lambda: request("http://gateway:8080/packages"),
                lambda rows: any(row["id"] == "unknown_" + prefix for row in rows), "lost confirmation retains accepted package")
    request(f"{APP}/jobs?id=reject_{prefix}", {})
    assert request(f"{APP}/jobs?id=reject_{prefix}")["state"] == "HELD"


def aws_local():
    id = uuid.uuid4().hex
    request(f"{APP}/jobs?id={id}", {})
    request(f"{APP}/jobs?id={id}", {})
    rows = await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["jobId"] == id and row["status"] == "SUBMITTED" for row in rows), "SQS worker submits once")
    row = next(row for row in rows if row["jobId"] == id)
    proof = request(f"{APP}/archive-proof?id={id}")
    assert proof["sha256"] == row["archiveSha256"] and proof["verification"] == "VALID" and proof["readable"], proof
    verify_out_of_order_ses_feedback(id)
    verify_unsafe_aws_replays_are_held(id)
    previous = request(f"{APP}/queue-evidence")["feedbackDeadLetters"]
    request(f"{APP}/reports", {"eventType": "Delivery", "mail": {"tags": {"sjm_attempt": ["unknown-job"]}, "destination": ["reader@example.test"]}})
    await_value(lambda: request(f"{APP}/queue-evidence"), lambda queues: queues["feedbackDeadLetters"] > previous,
                "this uncorrelated feedback is retained in the DLQ")
    print(json.dumps({"pattern": "aws-correspondence", "lane": "local-contract-fixtures", "protectedArchive": proof,
                      "duplicateSubmissionPrevented": True, "outOfOrderFeedbackPreserved": True,
                      "uncertainSubmissionHeld": True, "uncorrelatedFeedbackRetained": True}), flush=True)


def verify_out_of_order_ses_feedback(id):
    event = {"eventType": "DeliveryDelay", "mail": {"messageId": "synthetic-ses-id", "destination": ["reader@example.test"], "tags": {"sjm_attempt": [id]}}}
    request(f"{APP}/reports", event)
    request(f"{APP}/reports", event)
    await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["jobId"] == id and row["delivery"] == "DELAYED" for row in rows), "delayed event")
    event["eventType"] = "Delivery"
    request(f"{APP}/reports", event)
    await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["jobId"] == id and row["delivery"] == "DELIVERED" for row in rows), "delivery event")
    event["eventType"] = "DeliveryDelay"
    event["syntheticOrder"] = "late"
    request(f"{APP}/reports", event)
    rows = await_value(lambda: request(f"{APP}/jobs"), lambda rows: any(row["jobId"] == id and len(row["events"]) == 3 for row in rows), "duplicate and out-of-order evidence")
    assert next(row for row in rows if row["jobId"] == id)["delivery"] == "DELIVERED"
    assert len([item for item in request("http://smtp-a:8025/stats")["messages"] if item["messageId"] == f"<{id}@correspondence.example.test>"]) == 1


def verify_unsafe_aws_replays_are_held(id):
    for label, recipient, status, captures in (("uncertain", "lost", "UNKNOWN", 1), ("rejected", "reject", "REJECTED", 0)):
        attempt = id + "_" + label
        for _ in range(2):
            request(f"{APP}/jobs?id={attempt}&recipient={recipient}@example.test", {})
        held = await_value(lambda: request(f"{APP}/jobs"),
                           lambda jobs: any(job["jobId"] == attempt and job["status"] == "HELD" for job in jobs), "unsafe replay held")
        assert next(job for job in held if job["jobId"] == attempt)["smtpStatus"] == status
        assert len([item for item in request("http://smtp-a:8025/stats")["messages"]
                    if item["messageId"] == f"<{attempt}@correspondence.example.test>"]) == captures


if __name__ == "__main__":
    await_value(lambda: request(f"{APP}/health"), lambda state: state["status"] == "UP", "application readiness", 180)
    {"relaydesk": relaydesk, "polar-meridian": polar, "custom-handoff": handoff, "aws-correspondence": aws_local}[os.environ["PATTERN"]]()
