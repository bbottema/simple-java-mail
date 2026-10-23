package org.simplejavamail.examples.aws;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrespondenceWorkerTest {
    @Test
    void successfulSubmissionRetainsAcceptance() {
        assertFacts(true, MailSubmissionStatus.ACCEPTED, "SUBMITTED", "ACCEPTED");
    }

    @Test
    void cleanupFailureDoesNotEraseAcceptance() {
        assertFacts(false, MailSubmissionStatus.ACCEPTED, "HELD", "ACCEPTED");
    }

    @Test
    void partialAcceptanceIsHeldRatherThanAutomaticallyReplayed() {
        assertFacts(false, MailSubmissionStatus.PARTIALLY_ACCEPTED, "HELD", "PARTIALLY_ACCEPTED");
    }

    @Test
    void unknownAcceptanceRemainsExplicit() {
        assertFacts(false, MailSubmissionStatus.UNKNOWN, "HELD", "UNKNOWN");
    }

    @Test
    void refusalRemainsExplicit() {
        assertFacts(false, MailSubmissionStatus.REJECTED, "HELD", "REJECTED");
    }

    @Test
    void missingReceiptIsNotAnInventedRejection() {
        assertFacts(false, null, "HELD", "NO_RELIABLE_RECEIPT");
    }

    private static void assertFacts(boolean successful, MailSubmissionStatus submission, String operationStatus, String smtpStatus) {
        var facts = CorrespondenceWorker.terminalFacts(successful, submission);
        assertEquals(operationStatus, facts.get("status").s());
        assertEquals(smtpStatus, facts.get("smtpStatus").s());
    }
}
