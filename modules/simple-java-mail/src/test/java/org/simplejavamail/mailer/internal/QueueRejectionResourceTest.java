package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedConstruction;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import testutil.EmailHelper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;

/** Scheduling rejection must precede closure construction, which registers proxy accounting even before SMTP starts. */
@Timeout(30) // Includes cold constructor-instrumentation startup alongside the reactor's other test forks.
class QueueRejectionResourceTest {
    @Test
    void rejectedSendsAndConnectionTestsDoNotConstructResourceOwningClosures() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(EmailHelper.parsedRecipients(null, false, TO, "receiver@example.org"))
                .withPlainText("Rejected before resource accounting").buildEmail();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25)
                .withThreadPoolSize(1).withAsyncQueueCapacity(0).buildMailer();
             MockedConstruction<SendMailClosure> sends = mockConstruction(SendMailClosure.class);
             MockedConstruction<TestConnectionClosure> tests = mockConstruction(TestConnectionClosure.class)) {
            final CompletableFuture<Void> occupyingWorker = CompletableFuture.runAsync(() -> {
                started.countDown();
                try {
                    // Only the test may free capacity; a timer could accidentally admit SMTP work during a slow run.
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, mailer.getOperationalConfig().getExecutorService());
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mailer.sendMailAsync(email).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MailSendRejectedException.class);
                assertThatThrownBy(() -> mailer.testConnection(true).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MailSendRejectedException.class);
                assertThat(sends.constructed()).isEmpty();
                assertThat(tests.constructed()).isEmpty();
            } finally {
                release.countDown();
                occupyingWorker.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
