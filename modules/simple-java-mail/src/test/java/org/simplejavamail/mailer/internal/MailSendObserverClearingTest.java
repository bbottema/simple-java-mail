package org.simplejavamail.mailer.internal;

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
                .withMailSendResultObserver(ignored -> resultCalls.incrementAndGet(), abandonedResults::add)
                .withMailSendProgressObserver(progress::add, progressTasks::add);

        assertThat(builder.clearMailSendResultObserver()).isSameAs(builder);
        builder.clearMailSendResultObserver();
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
                .withMailSendResultObserver(outcomes::add, resultTasks::add)
                .withMailSendProgressObserver(ignored -> progressCalls.incrementAndGet(), abandonedProgress::add);

        assertThat(builder.clearMailSendProgressObserver()).isSameAs(builder);
        builder.clearMailSendProgressObserver();
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
                .withMailSendResultObserver(ignored -> { }, Runnable::run)
                .withMailSendProgressObserver(ignored -> { }, Runnable::run)
                .clearMailSendResultObserver()
                .clearMailSendProgressObserver();
        final MailSendObserverNotifier notifier = builder.buildObserverNotifier();

        assertThat(notifier.observesResults()).isFalse();
        assertThat(notifier.progressRecorder(UUID.randomUUID(), null)).isNull();
        assertThat(notifier.beginAttempt(email()).diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
    }

    @Test
    void clearingBuilderRegistrationsDoesNotChangeAnAlreadyBuiltMailer() throws Exception {
        final List<Runnable> resultTasks = new ArrayList<>();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final BlockingQueue<Runnable> progressTasks = new LinkedBlockingQueue<>();
        final List<MailSendProgress> progress = new ArrayList<>();
        final MailerRegularBuilder<?> builder = builder()
                .withMailSendResultObserver(outcomes::add, resultTasks::add)
                .withMailSendProgressObserver(progress::add, progressTasks::add);

        try (Mailer alreadyBuilt = builder.buildMailer();
                Mailer cleared = builder.clearMailSendResultObserver().clearMailSendProgressObserver().buildMailer()) {
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
                .withMailSendResultObserver(ignored -> { }, abandonedResults::add)
                .withMailSendProgressObserver(ignored -> { }, abandonedProgress::add)
                .clearMailSendResultObserver().clearMailSendProgressObserver()
                .withMailSendResultObserver(outcomes::add)
                .withMailSendProgressObserver(progress::add).buildMailer()) {
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
