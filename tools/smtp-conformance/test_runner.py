"""No Docker needed: check evidence, failure classification and the delivery completion marker."""

import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as XML

from capture import capture_delivery
from run import AssertionFailure, ConformanceRun, InfrastructureFailure, read_test_results, read_test_runtime, require_scenarios, write_sanitized_junit


class RunnerTest(unittest.TestCase):
    def test_capture_is_readable_by_the_host_runner_with_a_restrictive_mta_umask(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            previous_mask = os.umask(0o077)
            try:
                capture_delivery("fixture", "queue", "sender@conformance.test", "recipient@conformance.test",
                                 b"Message-ID: <permissions@conformance.test>\r\n\r\nFixture\r\n", destination)
            finally:
                os.umask(previous_mask)
            for capture in destination.iterdir():
                self.assertTrue(capture.stat().st_mode & stat.S_IROTH, capture.name)

    def test_capture_keeps_raw_content_and_marks_each_recipient_delivery_separately(self):
        content = b"Message-ID: <test@conformance.test>\r\nSubject: fixture\r\n\r\n.Body\r\n"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            for recipient in ("first@conformance.test", "josé@conformance.test"):
                capture_delivery("fixture", "one-queue-id", "sender@conformance.test", recipient, content, destination)
            manifests = list(destination.glob("*.xml"))
            self.assertEqual(2, len(manifests))
            recipients = []
            for manifest in manifests:
                values = {entry.get("key"): entry.text for entry in XML.parse(manifest).getroot()}
                recipients.append(values["recipient"])
                self.assertEqual("one-queue-id", values["queueId"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), values["sha256"])
                self.assertEqual(content, manifest.with_name(values["file"]).read_bytes())
            self.assertCountEqual(["first@conformance.test", "josé@conformance.test"], recipients)
            self.assertFalse(list(destination.glob("*.pending")))

    def test_evidence_omits_test_output_exception_details_and_environment_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "raw.xml"
            source.write_text('<testsuite><properties><property name="password" value="secret"/></properties>'
                              '<testcase classname="Fixture" name="failure" time="1.2"><failure>message-secret\n'
                              '\tat org.simplejavamail.Fixture.check(Fixture.java:42)\n'
                              '\tat org.simplejavamail.Fixture.check(password-secret)\n</failure>'
                              '<system-out>AUTH secret</system-out></testcase></testsuite>', encoding="utf-8")
            results = read_test_results([source])
            destination = Path(directory) / "safe.xml"
            write_sanitized_junit(results, destination)
            self.assertNotIn("secret", destination.read_text())
            self.assertEqual("failed", results[0]["status"])
            self.assertEqual(["org.simplejavamail.Fixture.check(Fixture.java:42)"], results[0]["failureLocations"])
            self.assertIn("Fixture.java:42", destination.read_text())
            self.assertEqual("1", XML.parse(destination).getroot().get("failures"))

    def test_missing_scenarios_and_skips_cannot_pass(self):
        with self.assertRaises(InfrastructureFailure):
            require_scenarios([], ["RequiredTest"])
        for status in ("failed", "skipped"):
            with self.assertRaises(AssertionFailure):
                require_scenarios([{"class": "example.RequiredTest", "status": status}], ["RequiredTest"])

    def test_runtime_records_the_test_jvm_without_copying_arbitrary_system_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "raw.xml"
            report.write_text('<testsuite><properties><property name="java.runtime.version" value="21-fixture"/>'
                              '<property name="java.vendor" value="test"/><property name="password" value="secret"/>'
                              '</properties></testsuite>', encoding="utf-8")
            self.assertEqual({"java.runtime.version": "21-fixture", "java.vendor": "test", "classpathJars": []}, read_test_runtime([report]))

    def test_cleanup_runs_after_startup_and_assertion_failures(self):
        for failure, code in ((InfrastructureFailure("startup"), 2), (AssertionFailure("scenario"), 1)):
            runner = ConformanceRun("real")
            with patch.object(runner, "prepare"), patch.object(runner, "start_servers", side_effect=failure), \
                    patch.object(runner, "cleanup") as cleanup:
                self.assertEqual(code, runner.run())
                cleanup.assert_called_once()

    def test_independent_verification_requires_every_unique_case_and_a_passing_result(self):
        checks = [{"server": server, "scenario": scenario, "status": "passed"} for server in ("postfix", "exim")
                  for scenario in ("dkim", "smime-signed", "smime-encrypted", "pgp-signed", "pgp-encrypted")]
        failed_checks = [dict(checks[0], status="failed"), *checks[1:]]
        for actual, failure in ((checks[:-1], InfrastructureFailure), (checks + [checks[0]], InfrastructureFailure),
                                (failed_checks, AssertionFailure), (checks, None)):
            with tempfile.TemporaryDirectory() as directory:
                runner = ConformanceRun("real")
                runner.evidence = Path(directory)
                (runner.evidence / "content-verification.json").write_text(json.dumps({"status": "passed", "checks": actual}), encoding="utf-8")
                with patch.object(runner, "compose_output"):
                    if failure:
                        with self.assertRaises(failure):
                            runner.verify_content()
                    else:
                        runner.verify_content()
                        self.assertEqual(10, len(runner.results))

    def test_cleanup_failure_blocks_an_otherwise_successful_run(self):
        runner = ConformanceRun("embedded")
        with patch.object(runner, "prepare"), patch.object(runner, "test"), \
                patch.object(runner, "cleanup", side_effect=InfrastructureFailure("cleanup")):
            self.assertEqual(2, runner.run())


if __name__ == "__main__":
    unittest.main()
