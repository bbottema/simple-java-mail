package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailSendHandlers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Clearing is builder-local: each observer and already-built Mailer keeps its independent registration. */
@Timeout(15)
class MailSendObserverClearingTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void clearingResultsPreservesProgressAndItsApplicationExecutor() throws Exception {
        final List<Runnable> abandonedResults = new ArrayList<>();
        final AtomicInteger resultCalls = new AtomicInteger();
        final BlockingQueue<Runnable> progressTasks = new LinkedBlockingQueue<>();
        final List<MailSendProgress> progress = new ArrayList<>();
        final MailerRegularBuilder<?> builder = builder()
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(ignored -> resultCalls.incrementAndGet(), abandonedResults::add).withProgressObserver(progress::add, progressTasks::add).build());

        assertThat(builder.withSendHandlers(builder.getSendHandlers().toBuilder().clearResultObserver().build())).isSameAs(builder);
        builder.withSendHandlers(builder.getSendHandlers().toBuilder().clearResultObserver().build());
        try (Mailer mailer = builder.buildMailer()) {
            mailer.sync().sendMail(email());

            assertThat(abandonedResults).isEmpty();
            assertThat(resultCalls).hasValue(0);
            assertThat(progress).isEmpty();
            runNext(progressTasks);
            assertThat(progress).hasSize(1);
        }
    }

    @Test
    void clearingProgressPreservesResultsAndTheirApplicationExecutor() throws Exception {
        final List<Runnable> resultTasks = new ArrayList<>();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final BlockingQueue<Runnable> abandonedProgress = new LinkedBlockingQueue<>();
        final AtomicInteger progressCalls = new AtomicInteger();
        final MailerRegularBuilder<?> builder = builder()
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add, resultTasks::add).withProgressObserver(ignored -> progressCalls.incrementAndGet(), abandonedProgress::add).build());

        assertThat(builder.withSendHandlers(builder.getSendHandlers().toBuilder().clearProgressObserver().build())).isSameAs(builder);
        builder.withSendHandlers(builder.getSendHandlers().toBuilder().clearProgressObserver().build());
        try (Mailer mailer = builder.buildMailer()) {
            mailer.sync().sendMail(email());

            assertThat(resultTasks).hasSize(1);
            assertThat(outcomes).isEmpty();
            resultTasks.get(0).run();
            assertThat(outcomes).hasSize(1);
            assertThat(abandonedProgress).isEmpty();
            assertThat(progressCalls).hasValue(0);
        }
    }

    @Test
    void clearingBothRestoresTheUnobservedFastPath() {
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoaderTestHelper.emptyConfig())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(ignored -> { }, Runnable::run).withProgressObserver(ignored -> { }, Runnable::run).clearResultObserver().clearProgressObserver().build());
        final MailSendObserverNotifier notifier = builder.buildObserverNotifier();

        assertThat(notifier.observesResults(null)).isFalse();
        assertThat(notifier.progressRecorder(UUID.randomUUID(), null, null)).isNull();
        assertThat(notifier.beginAttempt(email()).diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
    }

    @Test
    void clearingBuilderRegistrationsDoesNotChangeAnAlreadyBuiltMailer() throws Exception {
        final List<Runnable> resultTasks = new ArrayList<>();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final BlockingQueue<Runnable> progressTasks = new LinkedBlockingQueue<>();
        final List<MailSendProgress> progress = new ArrayList<>();
        final MailerRegularBuilder<?> builder = builder()
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add, resultTasks::add).withProgressObserver(progress::add, progressTasks::add).build());

        try (Mailer alreadyBuilt = builder.buildMailer();
                Mailer cleared = builder.withSendHandlers(MailSendHandlers.builder().clearResultObserver().clearProgressObserver().build()).buildMailer()) {
            cleared.sync().sendMail(email());
            assertThat(resultTasks).isEmpty();
            assertThat(progressTasks).isEmpty();

            alreadyBuilt.sync().sendMail(email());
            assertThat(resultTasks).hasSize(1);
            resultTasks.get(0).run();
            runNext(progressTasks);
            assertThat(outcomes).hasSize(1);
            assertThat(progress).hasSize(1);
        }
    }

    @Test
    void observersCanBeRegisteredAgainAfterClearing() throws Exception {
        final List<Runnable> abandonedResults = new ArrayList<>();
        final BlockingQueue<Runnable> abandonedProgress = new LinkedBlockingQueue<>();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final BlockingQueue<MailSendProgress> progress = new LinkedBlockingQueue<>();
        try (Mailer mailer = builder()
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(ignored -> { }, abandonedResults::add).withProgressObserver(ignored -> { }, abandonedProgress::add).clearResultObserver().clearProgressObserver().withResultObserver(outcomes::add).withProgressObserver(progress::add).build()).buildMailer()) {
            mailer.sync().sendMail(email());

            assertThat(outcomes).hasSize(1);
            assertThat(progress.poll(5, SECONDS)).isNotNull();
            assertThat(abandonedResults).isEmpty();
            assertThat(abandonedProgress).isEmpty();
        }
    }

    private MailerRegularBuilder<?> builder() {
        return mail.mailerBuilder().withTransportModeLoggingOnly(true);
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().withAddress("receiver@example.org")
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .withPlainText("Observer clearing test").buildEmail();
    }

    private static void runNext(final BlockingQueue<Runnable> tasks) throws InterruptedException {
        final Runnable task = tasks.poll(5, SECONDS);
        assertThat(task).isNotNull();
        task.run();
    }
}
