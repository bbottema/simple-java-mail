package org.simplejavamail.examples.handoff;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandoffApplicationTest {
    @Test
    void failedCompletionRetainsTheObserversReceipt() {
        MailSendOutcome outcome = mock(MailSendOutcome.class);
        MailSubmissionReceipt receipt = mock(MailSubmissionReceipt.class);
        when(outcome.getSubmissionReceipt()).thenReturn(Optional.of(receipt));
        when(receipt.getStatus()).thenReturn(MailSubmissionStatus.UNKNOWN);
        Mailer mailer = mock(Mailer.class);
        Mailer.Sync sync = mock(Mailer.Sync.class);
        Email email = mock(Email.class);
        when(mailer.sync()).thenReturn(sync);
        var recorded = new AtomicReference<Map<String, Object>>();
        when(sync.sendMail(email)).thenAnswer(invocation -> {
            recorded.set(HandoffApplication.terminalFacts("job", outcome));
            throw new IllegalStateException("cleanup failed after the callback");
        });

        HandoffApplication.submitPreparedMail(mailer, email);

        assertEquals("UNKNOWN", recorded.get().get("smtpAcceptance"));
        assertEquals("HELD", recorded.get().get("state"));
        verify(sync, times(1)).sendMail(email);
    }

    @Test
    void successIsAHandoffRatherThanAnInventedSmtpAcceptance() {
        MailSendOutcome outcome = mock(MailSendOutcome.class);
        MailSubmissionReceipt receipt = mock(MailSubmissionReceipt.class);
        when(outcome.isSuccessful()).thenReturn(true);
        when(outcome.getSubmissionReceipt()).thenReturn(Optional.of(receipt));
        when(receipt.getStatus()).thenReturn(MailSubmissionStatus.UNKNOWN);

        var facts = HandoffApplication.terminalFacts("job", outcome);

        assertEquals(true, facts.get("completed"));
        assertEquals("HANDED_OFF", facts.get("state"));
        assertEquals("UNKNOWN", facts.get("smtpAcceptance"));
    }

    @Test
    void absentReceiptIsNotAnInventedRejection() {
        MailSendOutcome outcome = mock(MailSendOutcome.class);
        when(outcome.getSubmissionReceipt()).thenReturn(Optional.empty());
        assertEquals("NO_RECEIPT", HandoffApplication.terminalFacts("job", outcome).get("smtpAcceptance"));
    }
}
