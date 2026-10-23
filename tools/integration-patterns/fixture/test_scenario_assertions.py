"""Keep the experiment's assertions stricter than its friendly example output."""
import unittest
from unittest.mock import patch

from scenario import verify_submission_evidence
from applications_scenario import prove_shared_limits_do_not_starve_urgent_work, result_write_failed_for


class SubmissionEvidenceTest(unittest.TestCase):
    def test_partial_acceptance_and_unknown_acceptance_cannot_be_swapped(self):
        for kind, wrong_status in (("partial", "UNKNOWN"), ("unknown", "PARTIALLY_ACCEPTED")):
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                verify_submission_evidence(kind, {"state": "HELD", "submission_status": wrong_status})

    def test_expected_partial_receipt_keeps_the_accepted_recipient(self):
        verify_submission_evidence("partial", {"state": "HELD", "submission_status": "PARTIALLY_ACCEPTED", "accepted": "reader@example.test"})

    def test_expected_unknown_receipt_does_not_invent_accepted_recipients(self):
        verify_submission_evidence("unknown", {"state": "HELD", "submission_status": "UNKNOWN", "accepted": ""})
        with self.assertRaises(AssertionError):
            verify_submission_evidence("unknown", {"state": "HELD", "submission_status": "UNKNOWN", "accepted": "reader@example.test"})

    def test_success_requires_a_successful_operation_and_accepted_receipt(self):
        verify_submission_evidence("happy", {"state": "SUBMITTED", "submission_status": "ACCEPTED", "accepted": "reader@example.test"})
        with self.assertRaises(AssertionError):
            verify_submission_evidence("happy", {"state": "SUBMITTED", "submission_status": "UNKNOWN"})

    def test_an_old_result_failure_cannot_prove_this_jobs_failure_was_visible(self):
        events = [{"kind": "RESULT_WRITE_FAILED", "detail": "requestId=old_job; attemptId=older; failure"}]
        self.assertFalse(result_write_failed_for(events, "new_job"))
        events.append({"kind": "RESULT_WRITE_FAILED", "detail": "requestId=new_job; attemptId=current; failure"})
        self.assertTrue(result_write_failed_for(events, "new_job"))

    def test_bulk_archive_proof_uses_an_actually_submitted_job_not_enqueue_order(self):
        rows = [{"id": "current_bulk_0", "state": "CLAIMED"}, {"id": "current_bulk_2", "state": "SUBMITTED"},
                {"id": "current_bulk_4", "state": "SUBMITTED"}]
        proof = {"sameBytes": True, "verification": "VALID", "readable": True}

        def respond(url, data=None, headers=None):
            if data is not None:
                return []
            if url.endswith("/limits"):
                return [{"workload": "BULK"}, {"workload": "BULK"}]
            self.assertTrue(url.endswith("/archive-proof?id=current_bulk_2"), url)
            return proof

        with patch("applications_scenario.await_value", return_value=rows), patch("applications_scenario.request", side_effect=respond) as request:
            self.assertEqual(proof, prove_shared_limits_do_not_starve_urgent_work("current"))
            bulk_inputs = [call.args[0] for call in request.call_args_list if "/jobs?id=current_bulk_" in call.args[0]]
            self.assertEqual(6, len(bulk_inputs))
            self.assertTrue(all(url.endswith("&workload=BULK&scenario=protected") for url in bulk_inputs))


if __name__ == "__main__":
    unittest.main()
