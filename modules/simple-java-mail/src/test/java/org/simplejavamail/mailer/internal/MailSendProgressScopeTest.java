package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.TimeUnit.SECONDS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.mailer.MailSendProgressStage.*;
import static org.simplejavamail.mailer.internal.MailSendProgressDispatcherTest.take;

@Timeout(15)
class MailSendProgressScopeTest {
    @Test
    void blockedOldCallbackCannotOverwriteAReplacementGeneration() throws Exception {
        final BlockingQueue<MailSendProgress> events = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(events::add, null)) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, System::nanoTime);
            final MailSendProgressScope old = recorder.openScope(true);
            final CountDownLatch reporting = new CountDownLatch(1);
            final FutureTask<Void> lateCallback = new FutureTask<>(() -> {
                reporting.countDown();
                old.stageChanged(AUTHENTICATING);
                old.close();
                return null;
            });
            final MailSendProgressScope current;
            synchronized (recorder) {
                new Thread(lateCallback, "late-provider-progress").start();
                assertThat(reporting.await(5, SECONDS)).isTrue();
                // The callback cannot record until this monitor is released; meanwhile ownership advances.
                current = recorder.openScope(true);
                current.stageChanged(WAITING_FOR_GREETING);
            }
            lateCallback.get(5, SECONDS);
            assertThat(take(events).getStage()).isEqualTo(WAITING_FOR_GREETING);
            assertThat(events).isEmpty();
            current.close();
            assertThat(take(events).getStage()).isEqualTo(ACQUIRING_CONNECTION);
        }
    }

    @Test
    void retiredSetupCannotOverwriteReplacementOrOrchestration() throws Exception {
        final BlockingQueue<MailSendProgress> events = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(events::add, null)) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, new AtomicLong()::get);
            final MailSendProgressScope old = recorder.openScope(true);
            old.stageChanged(WAITING_FOR_GREETING);
            assertThat(take(events).getStage()).isEqualTo(WAITING_FOR_GREETING);
            final MailSendProgressScope current = recorder.openScope(true);
            current.stageChanged(AUTHENTICATING);
            assertThat(take(events).getStage()).isEqualTo(AUTHENTICATING);
            old.close();
            old.stageChanged(NEGOTIATING_TLS);
            old.contentTransferStarted(2L);
            assertThat(events).isEmpty();
            recorder.stage(PREPARING_MIME);
            assertThat(take(events).getStage()).isEqualTo(PREPARING_MIME);
            current.stageChanged(WAITING_FOR_GREETING);
            current.close();
            assertThat(events).isEmpty();
        }
    }

    @Test
    void providerScopesRejectUnownedStagesAndCannotFinishAnAttempt() throws Exception {
        final BlockingQueue<MailSendProgress> events = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(events::add, null)) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, System::nanoTime);
            try (MailSendProgressScope setup = recorder.openScope(true)) {
                setup.stageChanged(FINISHED);
                setup.stageChanged(SUBMITTING_SENDER);
                setup.stageChanged(null);
                setup.contentTransferStarted(20L);
                setup.waitingForResponse();
                assertThat(events).isEmpty();
                setup.stageChanged(NEGOTIATING_TLS);
                assertThat(take(events).getSubmissionAttempt()).isZero();
            }
            assertThat(take(events).getStage()).isEqualTo(ACQUIRING_CONNECTION);
            final MailSendProgressScope submission = recorder.openScope(false);
            submission.stageChanged(AUTHENTICATING);
            submission.stageChanged(FINISHED);
            assertThat(events).isEmpty();
            recorder.submissionInvoked();
            assertThat(take(events).getSubmissionAttempt()).isEqualTo(1);
            submission.stageChanged(SUBMITTING_RECIPIENTS);
            assertThat(take(events).getStage()).isEqualTo(SUBMITTING_RECIPIENTS);
            submission.contentTransferStarted(20L);
            assertThat(take(events).getStage()).isEqualTo(UPLOADING);
            submission.waitingForResponse();
            assertThat(take(events).getStage()).isEqualTo(WAITING_FOR_RESPONSE);
            submission.close();
            submission.contentTransferFinished(20);
            assertThat(events).isEmpty();
            recorder.finish(null);
            assertThat(take(events).getStage()).isEqualTo(FINISHED);
            submission.stageChanged(SUBMITTING);
            assertThat(events).isEmpty();
        }
    }

    @Test
    void cancellationFencesReportingWithoutWaitingForTransportUnregistration() throws Exception {
        final BlockingQueue<MailSendProgress> events = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(events::add, null);
             MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, System::nanoTime);
            final MailSendProgressScope setup = recorder.openScope(true);
            setup.observeCancellation(control);
            setup.stageChanged(WAITING_FOR_GREETING);
            take(events);
            control.requestCancellation();
            setup.stageChanged(AUTHENTICATING);
            setup.close();
            setup.close();
            assertThat(events).isEmpty();
            recorder.finish(null);
            assertThat(take(events).getStage()).isEqualTo(FINISHED);
        }
    }

    @Test
    void oldSubmissionCannotContaminateRetryByteCounts() throws Exception {
        final BlockingQueue<MailSendProgress> events = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(events::add, null)) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, UUID.randomUUID(), null, System::nanoTime);
            final MailSendProgressScope first = recorder.openScope(false);
            recorder.submissionInvoked();
            take(events);
            first.close();
            final MailSendProgressScope second = recorder.openScope(false);
            recorder.submissionInvoked();
            assertThat(take(events).getSubmissionAttempt()).isEqualTo(2);
            second.contentTransferStarted(4L);
            take(events);
            first.waitingForResponse();
            first.contentTransferStarted(100L);
            first.contentTransferFinished(100L);
            second.contentTransferFinished(4);
            final MailSendProgress last = take(events);
            assertThat(last.getStage()).isEqualTo(UPLOADING);
            assertThat(last.getBytesWritten()).isEqualTo(4L);
            assertThat(last.getTotalBytes()).isEqualTo(4L);
            second.close();
        }
    }
}
