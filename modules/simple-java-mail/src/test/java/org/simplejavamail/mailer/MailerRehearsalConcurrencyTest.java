package org.simplejavamail.mailer;

import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailerRehearsalConcurrencyTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void callPinsItsOwnerBeforeGovernanceInvokesBorrowedMetadata() throws Exception {
        final Session shared = MailerRehearsalLifetimeTest.session(new Properties());
        shared.getProperties().setProperty("mail.from", "message@original.example");
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(MailerRehearsalLifetimeTest.defaults("original@example.org")).buildMailer();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean();
        final DataSource source = new Source() {
            @Override public String getName() {
                if (armed.get()) { entered.countDown(); await(release); }
                return "document.bin";
            }
        };
        final Email email = MAIL.emailBuilder().copying(MailerRehearsalLifetimeTest.draft()).withAttachment("document.bin", source).buildEmail();
        final ExecutorService application = Executors.newSingleThreadExecutor();
        try {
            armed.set(true);
            final CompletableFuture<MailRehearsal> preview = CompletableFuture.supplyAsync(() -> original.rehearse(email), application);
            assertThat(entered.await(5, SECONDS)).isTrue();
            original.closeAsync().get(2, SECONDS);
            shared.getProperties().setProperty("mail.from", "message@replacement.example");
            try (Mailer replacement = MAIL.mailerBuilder(shared).withEmailDefaults(MailerRehearsalLifetimeTest.defaults("replacement@example.org"))
                    .withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(1).build()).buildMailer()) {
                release.countDown();
                final MailRehearsal result = preview.get(5, SECONDS);
                assertThat(result.getEmailId()).endsWith("@original.example>");
                assertThat(result.getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("original@example.org");
            }
            assertThat(application.isShutdown()).isFalse();
        } finally {
            release.countDown();
            original.close();
            application.shutdownNow();
            assertThat(application.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void blockedContentDoesNotRetainTheSendingSessionOrDelayShutdown() throws Exception {
        final Session shared = MailerRehearsalLifetimeTest.session(new Properties());
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(MailerRehearsalLifetimeTest.defaults("original@example.org")).buildMailer();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final DataSource source = new Source() {
            @Override public InputStream getInputStream() {
                opened.incrementAndGet();
                entered.countDown();
                await(release);
                return new ByteArrayInputStream(new byte[] {1, 2, 3}) {
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        final Email email = MAIL.emailBuilder().copying(MailerRehearsalLifetimeTest.draft()).withAttachment("document.bin", source).buildEmail();
        final ExecutorService application = Executors.newSingleThreadExecutor();
        try {
            final CompletableFuture<MailRehearsal> preview = CompletableFuture.supplyAsync(() -> original.rehearse(email), application);
            assertThat(entered.await(5, SECONDS)).isTrue();
            original.closeAsync().get(2, SECONDS);
            try (Mailer replacement = MAIL.mailerBuilder(shared).buildMailer()) {
                assertThat(preview).isNotDone();
                release.countDown();
                assertThat(preview.get(5, SECONDS).getEmlBytes()).isNotEmpty();
            }
            assertThat(opened.get()).isPositive();
            assertThat(closed).hasValue(opened.get());
            assertThat(application.isShutdown()).isFalse();
        } finally {
            release.countDown();
            original.close();
            application.shutdownNow();
            assertThat(application.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void standaloneMetadataProviderCanCloseTheMailerAndCompleteItsPreparation() throws Exception {
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25)
                .withEmailDefaults(MailerRehearsalLifetimeTest.defaults("original@example.org")).buildMailer();
        final AtomicBoolean armed = new AtomicBoolean();
        final DataSource source = new Source() {
            @Override public String getName() {
                if (armed.get()) {
                    try { mailer.closeAsync().get(2, SECONDS); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
                return "document.bin";
            }
        };
        final Email email = MAIL.emailBuilder().copying(MailerRehearsalLifetimeTest.draft()).withAttachment("document.bin", source).buildEmail();
        try {
            armed.set(true);
            assertThat(mailer.rehearse(email).getEmlBytes()).isNotEmpty();
            assertThat(mailer.closeAsync()).isCompleted();
        } finally {
            mailer.close();
        }
    }

    @Test
    void contentFailuresAfterClosingRetainTheirCauseAndCloseOwnedStreams() throws Exception {
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25)
                .withEmailDefaults(MailerRehearsalLifetimeTest.defaults("original@example.org")).buildMailer();
        final IOException original = new IOException("borrowed content unavailable");
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final DataSource source = new Source() {
            @Override public InputStream getInputStream() {
                opened.incrementAndGet();
                return new InputStream() {
                    @Override public int read() throws IOException { throw original; }
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        final Email email = MAIL.emailBuilder().copying(MailerRehearsalLifetimeTest.draft()).withAttachment("document.bin", source).buildEmail();
        mailer.close();
        assertThatThrownBy(() -> mailer.rehearse(email)).isInstanceOf(MailException.class).hasRootCause(original);
        assertThat(opened.get()).isPositive();
        assertThat(closed).hasValue(opened.get());
    }

    @Test
    void preparationDuringSendDrainUsesTheFrozenViewWithoutCallingTheCustomSenderOrObserver() throws Exception {
        final CountDownLatch sending = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger submissions = new AtomicInteger();
        final AtomicInteger observations = new AtomicInteger();
        final ExecutorService application = Executors.newSingleThreadExecutor();
        final Session session = MailerRehearsalLifetimeTest.session(new Properties());
        session.getProperties().setProperty("mail.from", "message@closing.example");
        final Mailer mailer = MAIL.mailerBuilder(session).withEmailDefaults(MailerRehearsalLifetimeTest.defaults("sender@example.org"))
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(application).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> observations.incrementAndGet()).build())
                .withCustomMailer(new CustomMailer() {
                    @Override public void testConnection(final OperationalConfig config, final Session provided) { throw new AssertionError("No connection test requested"); }
                    @Override public void sendMessage(final OperationalConfig config, final Session provided, final Email email, final MimeMessage message) {
                        submissions.incrementAndGet();
                        sending.countDown();
                        await(release);
                    }
                }).buildMailer();
        try {
            final CompletableFuture<?> sent = mailer.async().sendMail(MailerRehearsalLifetimeTest.draft()).getCompletion();
            assertThat(sending.await(5, SECONDS)).isTrue();
            final CompletableFuture<Void> shutdown = mailer.closeAsync();
            assertThat(shutdown).isNotDone();
            session.getProperties().setProperty("mail.from", "message@after-closing.example");
            assertThat(mailer.rehearse(MailerRehearsalLifetimeTest.draft()).getEmailId()).endsWith("@closing.example>");
            mailer.validate(MailerRehearsalLifetimeTest.draft(), false);
            assertThat(observations).hasValue(0);
            assertThat(submissions).hasValue(1);
            release.countDown();
            sent.get(5, SECONDS);
            shutdown.get(5, SECONDS);
            assertThat(observations).hasValue(1);
            assertThat(application.isShutdown()).isFalse();
        } finally {
            release.countDown();
            mailer.close();
            application.shutdownNow();
            assertThat(application.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void explicitlyUsingAnOwnedWorkerStillRetainsItsExistingCloseSelfWaitGuard() throws Exception {
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25)
                .withEmailDefaults(MailerRehearsalLifetimeTest.defaults("sender@example.org")).buildMailer();
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<IllegalStateException> rejected = new AtomicReference<>();
        final DataSource source = new Source() {
            @Override public String getName() {
                if (armed.get()) {
                    try { mailer.close(); throw new AssertionError("Owned worker closed itself"); }
                    catch (IllegalStateException expected) { rejected.set(expected); }
                    catch (Exception unexpected) { throw new AssertionError(unexpected); }
                }
                return "document.bin";
            }
        };
        final Email email = MAIL.emailBuilder().copying(MailerRehearsalLifetimeTest.draft()).withAttachment("document.bin", source).buildEmail();
        try {
            armed.set(true);
            assertThat(mailer.getOperationalConfig().getExecutorService().submit(() -> mailer.rehearse(email)).get(5, SECONDS)
                    .getEmlBytes()).isNotEmpty();
            assertThat(rejected.get()).hasMessageContaining("own shutdown");
            assertThat(mailer.getOperationalConfig().getExecutorService().isShutdown()).isFalse();
        } finally {
            mailer.close();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) { throw new AssertionError("Rehearsal fixture was not released"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static class Source implements DataSource {
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[] {1, 2, 3}); }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "document.bin"; }
    }
}
