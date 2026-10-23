"""Run one mail experiment, then inspect the application's stored evidence. No fixture mail leaves Docker."""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

APP = "http://app:8080"
SMTP = "http://smtp:8025"


def request(url, data=None, content_type="application/octet-stream"):
    req = urllib.request.Request(url, data=data, headers={"Content-Type": content_type})
    with urllib.request.urlopen(req, timeout=30) as response:
        body = response.read()
        content_type = response.headers.get("Content-Type", "").split(";", 1)[0]
        return json.loads(body) if content_type.endswith("json") else body


def await_value(read, accepts, explanation, seconds=120):
    limit = time.monotonic() + seconds
    last = None
    while time.monotonic() < limit:
        try:
            last = read()
            if accepts(last):
                return last
        except (urllib.error.URLError, TimeoutError, ConnectionError) as failure:
            last = str(failure)
        time.sleep(0.25)
    raise AssertionError(f"Timed out waiting for {explanation}: {last}")


def job(id):
    return await_value(lambda: request(f"{APP}/jobs/{id}"), lambda row: row["state"] not in ("READY", "SENDING"), "terminal submission evidence")


def scenario(kind):
    """Follow a new job, repeat its input, and show how later mail feedback differs from SMTP acceptance."""
    id = "demo_" + uuid.uuid4().hex[:12]
    before = request(f"{SMTP}/stats")["count"]
    request(f"{APP}/jobs/{id}?scenario={kind}", b"")
    evidence = job(id)

    verify_submission_evidence(kind, evidence)
    verify_repeated_input_does_not_resend(id, kind, before)
    if kind in ("happy", "delayed"):
        verify_later_feedback(id, evidence, "delayed" if kind == "delayed" else "failed")

    print(json.dumps({"scenario": kind, "job": id, "state": evidence["state"], "submission": evidence["submission_status"],
                      "smtpSubmissions": 1, "duplicateSuppressed": True,
                      "feedback": ("delayed" if kind == "delayed" else "failed") if kind in ("happy", "delayed") else "not-inferred"}), flush=True)


def verify_submission_evidence(kind, evidence):
    expected = {"happy": ("SUBMITTED", "ACCEPTED"), "delayed": ("SUBMITTED", "ACCEPTED"),
                "partial": ("HELD", "PARTIALLY_ACCEPTED"), "unknown": ("HELD", "UNKNOWN")}
    state, submission = expected[kind]
    assert evidence["state"] == state, evidence
    # A known partial acceptance is not interchangeable with losing the server's final reply.
    assert evidence["submission_status"] == submission, evidence
    if submission in ("ACCEPTED", "PARTIALLY_ACCEPTED"):
        assert evidence["accepted"].split(",") == ["reader@example.test"], evidence
    elif submission == "UNKNOWN":
        assert not evidence["accepted"], evidence


def verify_repeated_input_does_not_resend(id, kind, previous_submissions):
    request(f"{APP}/jobs/{id}?scenario={kind}", b"")
    time.sleep(0.4)
    assert request(f"{SMTP}/stats")["count"] == previous_submissions + 1, "Duplicate SMTP submission"


def verify_later_feedback(id, evidence, action):
    # This report is injected by the test. SMTP capture alone cannot establish later mailbox delivery.
    report = request(f"{SMTP}/synthetic-report?envelopeId={evidence['attempt_id']}&action={action}")
    feedback = request(f"{APP}/reports", report, "message/rfc822")
    assert feedback["result"] == "RECORDED", feedback
    assert request(f"{APP}/reports", report, "message/rfc822")["result"] == "DUPLICATE"
    assert request(f"{APP}/jobs/{id}")["delivery_action"] == action.upper()
    if os.environ.get("EXPECT_EVENT"):
        await_value(lambda: request(f"{APP}/events"),
                    lambda rows: any(row["job_id"] == id and row["kind"] == os.environ["EXPECT_EVENT"] for row in rows),
                    "framework feedback decision")
    missing = request(f"{SMTP}/synthetic-report?envelopeId=missing-{uuid.uuid4().hex}&action=failed")
    assert request(f"{APP}/reports", missing, "message/rfc822")["result"] == "UNCORRELATED"
    if action == "failed":
        verify_stale_feedback_cannot_replace_a_final_result(id, evidence)


def verify_stale_feedback_cannot_replace_a_final_result(id, evidence):
    stale = request(f"{SMTP}/synthetic-report?envelopeId={evidence['attempt_id']}&action=delayed")
    assert request(f"{APP}/reports", stale, "message/rfc822")["result"] == "IGNORED_OUT_OF_ORDER"
    assert request(f"{APP}/jobs/{id}")["delivery_action"] == "FAILED"


def restart():
    prefix = "night_" + uuid.uuid4().hex[:10]
    before = request(f"{SMTP}/stats")["count"]
    interrupted = request(f"{APP}/nightly?prefix={prefix}&stopAfter=2", b"")
    assert interrupted["batchStatus"] == "FAILED", interrupted
    rows = [row for row in request(f"{APP}/jobs") if row["id"].startswith(prefix)]
    assert sum(row["state"] == "SUBMITTED" for row in rows) == 2, rows
    resumed = request(f"{APP}/nightly?prefix={prefix}&stopAfter=0", b"")
    assert resumed["batchStatus"] == "COMPLETED", resumed
    assert request(f"{SMTP}/stats")["count"] == before + 5, "Restart resent an already accepted email"
    print(json.dumps({"scenario": "restart", "submittedBeforeInterruption": 2, "submittedAfterResumption": 5, "duplicates": 0}), flush=True)


if __name__ == "__main__":
    await_value(lambda: request(f"{APP}/actuator/health"), lambda health: health["status"] == "UP", "application readiness", 180)
    selected = sys.argv[1] if len(sys.argv) > 1 else "all"
    if selected == "restart":
        restart()
    else:
        for kind in (("happy", "partial", "unknown", "delayed") if selected == "all" else (selected,)):
            scenario(kind)
