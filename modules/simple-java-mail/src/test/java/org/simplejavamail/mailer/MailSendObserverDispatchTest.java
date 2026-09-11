package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import testutil.ConfigLoaderTestHelper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.recipient.RecipientBuilder.to;

class MailSendObserverDispatchTest {
    @Test
    void outcomeIsHandedOffBeforeCompletionWithoutWaitingForItsApplicationExecutor() throws Exception {
        final List<Runnable> scheduled = new ArrayList<>();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final MailSubmissionReceipt receipt;
        try (Mailer mailer = builder().withMailSendObserver(outcomes::add, scheduled::add).buildMailer()) {
            receipt = mailer.sendMailAndGetReceiptSync(email());
            assertThat(scheduled).hasSize(1);
            assertThat(outcomes).isEmpty();
        }
        scheduled.get(0).run();
        assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
    }

    @Test
    void rejectedHandoffDoesNotReplaceSuccessfulOrFailedSends() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (Mailer mailer = builder().withMailSendObserver(outcome -> calls.incrementAndGet(), action -> {
            throw new RejectedExecutionException("application executor full");
        }).buildMailer()) {
            assertThat(mailer.sendMailAndGetReceiptSync(email())).isNotNull();
            final Email invalid = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank().buildEmail();
            final Throwable failure = mailer.sendMailAsync(invalid).getCompletion().handle((value, cause) -> cause).join();
            assertThat(failure).isNotInstanceOf(RejectedExecutionException.class);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void replacingWithTheOneArgumentOverloadRestoresInlineNotification() throws Exception {
        final List<Runnable> abandonedExecutor = new ArrayList<>();
        final AtomicInteger abandonedObserver = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        try (Mailer mailer = builder().withMailSendObserver(outcome -> abandonedObserver.incrementAndGet(), abandonedExecutor::add)
                .withMailSendObserver(outcomes::add).buildMailer()) {
            mailer.sendMailSync(email());
            assertThat(outcomes).hasSize(1);
            assertThat(abandonedExecutor).isEmpty();
            assertThat(abandonedObserver).hasValue(0);
        }
    }

    @Test
    void directExecutorRunsInlineAndCallbackRuntimeExceptionsRemainIsolated() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (Mailer mailer = builder().withMailSendObserver(outcome -> {
            calls.incrementAndGet();
            throw new IllegalStateException("observer failure");
        }, Runnable::run).buildMailer()) {
            mailer.sendMailSync(email());
            mailer.sendMailSync(email());
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void nullReplacementDoesNotDiscardTheExistingRegistration() throws Exception {
        final List<MailSendOutcome> outcomes = new ArrayList<>();
        final MailerRegularBuilder<?> builder = builder().withMailSendObserver(outcomes::add);
        assertThatThrownBy(() -> builder.withMailSendObserver(null, Runnable::run)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.withMailSendObserver(outcome -> { }, null)).isInstanceOf(NullPointerException.class);
        try (Mailer mailer = builder.buildMailer()) {
            mailer.sendMailSync(email());
            assertThat(outcomes).hasSize(1);
        }
    }

    @Test
    void deadlineCanBeResetAndInvalidValuesNeverReplaceIt() throws Exception {
        final MailerRegularBuilder<?> builder = builder().withMailSendTimeout(Duration.ofSeconds(2));
        assertThatThrownBy(() -> builder.withMailSendTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.getMailSendTimeout()).isEqualTo(Duration.ofSeconds(2));
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getMailSendTimeout()).isEqualTo(Duration.ofSeconds(2));
        }
        assertThat(builder.resetMailSendTimeout().getMailSendTimeout()).isNull();
    }

    private static MailerRegularBuilder<?> builder() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withTransportModeLoggingOnly(true);
    }

    private static Email email() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").withRecipients(to(null, "receiver@example.org"))
                .withPlainText("Observer dispatch test").buildEmail();
    }
}
