package org.simplejavamail.examples.patterns;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JobStoreTest {
    private final JdbcTemplate database = mock(JdbcTemplate.class);
    private final JobStore jobs = new JobStore(database);

    @Test
    void acceptanceSurvivesAnUnsuccessfulCleanup() {
        jobs.recordMailOutcome(outcome(false, MailSubmissionStatus.ACCEPTED));

        verify(database).update(anyString(), eq("HELD"), eq("ACCEPTED"), eq("reader@example.test"), eq("job-1"));
    }

    @Test
    void successfulSubmissionAndNoReceiptRemainDifferentFacts() {
        jobs.recordMailOutcome(outcome(true, MailSubmissionStatus.ACCEPTED));
        jobs.recordMailOutcome(outcome(false, null));

        verify(database).update(anyString(), eq("SUBMITTED"), eq("ACCEPTED"), eq("reader@example.test"), eq("job-1"));
        verify(database).update(anyString(), eq("HELD"), eq("NO_RELIABLE_RECEIPT"), eq(""), eq("job-1"));
    }

    @Test
    void partialAndUnknownReceiptsAreNotRewrittenAsMissing() {
        jobs.recordMailOutcome(outcome(false, MailSubmissionStatus.PARTIALLY_ACCEPTED));
        jobs.recordMailOutcome(outcome(false, MailSubmissionStatus.UNKNOWN));

        verify(database).update(anyString(), eq("HELD"), eq("PARTIALLY_ACCEPTED"), eq("reader@example.test"), eq("job-1"));
        verify(database).update(anyString(), eq("HELD"), eq("UNKNOWN"), eq("reader@example.test"), eq("job-1"));
    }

    @Test
    void failedEvidencePersistenceDoesNotTriggerAFallbackThatErasesAcceptance() {
        RuntimeException unavailable = new IllegalStateException("SQL is unavailable");
        doThrow(unavailable).when(database).update(anyString(), eq("HELD"), eq("ACCEPTED"), eq("reader@example.test"), eq("job-1"));

        assertSame(unavailable, assertThrows(RuntimeException.class,
                () -> jobs.recordMailOutcome(outcome(false, MailSubmissionStatus.ACCEPTED))));

        verify(database).update(anyString(), eq("HELD"), eq("ACCEPTED"), eq("reader@example.test"), eq("job-1"));
        verifyNoMoreInteractions(database);
    }

    private MailSendOutcome outcome(boolean successful, MailSubmissionStatus status) {
        MailSendOutcome outcome = mock(MailSendOutcome.class);
        when(outcome.getInitialMessageId()).thenReturn("<job-1@patterns.example.test>");
        when(outcome.isSuccessful()).thenReturn(successful);
        if (status == null) {
            when(outcome.getSubmissionReceipt()).thenReturn(Optional.empty());
        } else {
            MailSubmissionReceipt receipt = mock(MailSubmissionReceipt.class);
            when(receipt.getStatus()).thenReturn(status);
            when(receipt.getAcceptedRecipients()).thenReturn(List.of("reader@example.test"));
            when(outcome.getSubmissionReceipt()).thenReturn(Optional.of(receipt));
        }
        return outcome;
    }
}
