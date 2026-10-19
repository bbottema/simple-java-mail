package org.simplejavamail.mailer.failover;

import jakarta.mail.Message.RecipientType;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;


/** Public API setup shared by socket tests; deliberately has no access to mailer.internal or pool implementations. */
final class FailoverContractSupport {
    static final boolean DIRECT = Boolean.getBoolean("sjm.failover.direct");
    static final SimpleJavaMail MAIL = factory();

    private FailoverContractSupport() {
    }

    static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    static MailerRegularBuilder<?> builder(final int port) {
        return builder(MAIL, port);
    }

    static MailerRegularBuilder<?> builder(final SimpleJavaMail factory, final int port) {
        return factory.mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(SMTP)
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(Duration.ofMillis(30000))
                // Message-ID generation must not spend this fixture's barrier budget resolving the workstation's hostname.
                .withProperty("mail.host", "localhost")
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(2).withConnectionPoolClaimTimeout(Duration.ofMillis(6000))
                .withThreadPoolSize(4);
    }

    static FailoverPolicy policy() {
        return FailoverPolicy.builder().build();
    }

    static Email email() {
        return MAIL.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("receiver@example.test").withType(RecipientType.TO).build()).withSubject("failover regression").withPlainText("one message").buildEmail();
    }

    static MailSubmissionReceipt send(final Mailer mailer, final Email email, final boolean asynchronous) throws Exception {
        if (!asynchronous) {
            return mailer.sync().sendMail(email);
        }
        try {
            return mailer.async().sendMail(email).getCompletion().get(12, SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception) {
                throw (Exception) failure.getCause();
            }
            throw failure;
        }
    }

    static void await(final CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, SECONDS)).as("test barrier reached").isTrue();
    }

    static void eventually(final BooleanSupplier condition) throws InterruptedException {
        final long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).as("observable condition reached within the test deadline").isTrue();
    }

    static void assertSuccessfulOutcome(final List<MailSendOutcome> outcomes, final MailSubmissionReceipt receipt) {
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).isSuccessful()).isTrue();
        assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
        assertThat(outcomes.get(0).getFailure()).isEmpty();
    }
}
