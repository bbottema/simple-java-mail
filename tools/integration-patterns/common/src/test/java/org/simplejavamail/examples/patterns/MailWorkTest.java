package org.simplejavamail.examples.patterns;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MailWorkTest {
    private final JobStore jobs = mock(JobStore.class);
    private final Mailer mailer = mock(Mailer.class);
    private final Mailer.Sync synchronous = mock(Mailer.Sync.class);
    private final MailWork work = new MailWork(SimpleJavaMail.withConfig(ConfigLoader.builder().load()), mailer, jobs);

    @Test
    void duplicateFrameworkDeliveryDoesNotInvokeTheMailer() {
        when(jobs.beginSubmission("job-1")).thenReturn(false);

        work.deliver("job-1");

        verifyNoInteractions(mailer);
        verify(jobs).beginSubmission("job-1");
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void constructionFailureIsHeldBeforeAnyObserverCouldRun() {
        when(jobs.beginSubmission("job-1")).thenReturn(true);
        when(jobs.get("job-1")).thenThrow(new IllegalStateException("Unable to read the job"));

        work.deliver("job-1");

        verify(jobs).recordPreparationFailure("job-1");
        verifyNoInteractions(mailer);
    }

    @Test
    void throwingSendDoesNotOverwriteTheObserversReceipt() {
        when(jobs.beginSubmission("job-1")).thenReturn(true);
        when(jobs.get("job-1")).thenReturn(Map.of("attempt_id", "attempt-1", "scenario", "happy"));
        when(mailer.sync()).thenReturn(synchronous);
        when(synchronous.sendMail(any(Email.class))).thenThrow(new IllegalStateException("Cleanup failed after acceptance"));

        work.deliver("job-1");

        ArgumentCaptor<Email> sent = ArgumentCaptor.forClass(Email.class);
        verify(synchronous).sendMail(sent.capture());
        assertEquals("<job-1@patterns.example.test>", sent.getValue().getId());
        verify(jobs).event(eq("job-1"), eq("SUBMISSION_NEEDS_REVIEW"), anyString());
        verify(jobs, never()).recordPreparationFailure(anyString());
        verify(jobs).beginSubmission("job-1");
        verify(jobs).get("job-1");
        verifyNoMoreInteractions(jobs);
    }
}
