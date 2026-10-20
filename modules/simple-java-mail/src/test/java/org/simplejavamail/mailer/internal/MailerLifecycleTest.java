package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.EmailHelper;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;

@Timeout(15)
class MailerLifecycleTest {

    @Test
    void asynchronousCloseComposesAndClosesOwnedExecutorWithoutBatchSupport() throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            final Mailer mailer = mail().mailerBuilder().withSMTPServer("localhost", 25).buildMailer();
            final ExecutorService ownedExecutor = mailer.getOperationalConfig().getExecutorService();
            final AtomicInteger completions = new AtomicInteger();
            try {
                mailer.closeAsync().thenRun(completions::incrementAndGet).get(5, SECONDS);
                mailer.closeAsync().thenRun(completions::incrementAndGet).get(5, SECONDS);
                assertThat(completions.get()).isEqualTo(2);
                assertThat(ownedExecutor.isTerminated()).isTrue();
            } finally {
                mailer.close();
            }
        }
    }

    @Test
    void asynchronousCloseLeavesApplicationExecutorRunning() throws Exception {
        final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor();
        try (Mailer mailer = mail().mailerBuilder().withSMTPServer("localhost", 25).withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(applicationExecutor).build()).buildMailer()) {
            mailer.closeAsync().get(5, SECONDS);
            assertThat(applicationExecutor.submit(() -> "still application-owned").get(5, SECONDS)).isEqualTo("still application-owned");
        } finally {
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void completionViewsCannotCancelOrCompleteTheActualCleanup() throws Exception {
        final BlockingCustomMailer delivery = new BlockingCustomMailer();
        final Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(1).build()).buildMailer();
        try {
            final CompletableFuture<?> send = mailer.async().sendMail(email()).getCompletion();
            assertThat(delivery.entered.await(5, SECONDS)).isTrue();

            final CompletableFuture<Void> cancelled = mailer.closeAsync();
            final CompletableFuture<Void> completed = mailer.closeAsync();
            final CompletableFuture<Void> failed = mailer.closeAsync();
            final CompletableFuture<Void> actualCleanup = mailer.closeAsync();
            assertThat(cancelled.cancel(true)).isTrue();
            assertThat(completed.complete(null)).isTrue();
            assertThat(failed.completeExceptionally(new IllegalStateException("application changed its own view"))).isTrue();
            assertThat(actualCleanup).isNotDone();
            assertThat(send).isNotDone();

            delivery.finish.countDown();
            send.get(5, SECONDS);
            actualCleanup.get(5, SECONDS);
            mailer.close();
            assertThat(mailer.getOperationalConfig().getExecutorService().isTerminated()).isTrue();
        } finally {
            delivery.finish.countDown();
            mailer.close();
        }
    }

    @Test
    void cleanupFailureKeepsItsIdentityAcrossViewsAndBlockingClose() throws Exception {
        final OperationalConfig supplied = EmailHelper.createDummyOperationalConfig(Collections.emptyList(), false, true);
        final ExecutorService originalExecutor = supplied.getExecutorService();
        final OperationalConfig configuration = spy(supplied);
        final ExecutorService failingExecutor = mock(ExecutorService.class);
        final IllegalStateException failure = new IllegalStateException("test executor cleanup failure");
        doReturn(failingExecutor).when(configuration).getExecutorService();
        doThrow(failure).when(failingExecutor).shutdown();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            final Mailer mailer = new MailerImpl(null, SMTP, EmailGovernanceImpl.NO_GOVERNANCE(),
                    new ProxyConfigImpl(null, null, null, null, null), Session.getInstance(new java.util.Properties()), configuration);
            final CompletableFuture<Void> first = mailer.closeAsync();
            assertThatThrownBy(() -> first.get(5, SECONDS)).isInstanceOf(ExecutionException.class).hasCauseReference(failure);
            assertThatThrownBy(() -> mailer.closeAsync().get(5, SECONDS)).isInstanceOf(ExecutionException.class).hasCauseReference(failure);
            assertThatThrownBy(mailer::close).isInstanceOf(ExecutionException.class).hasCauseReference(failure);
            verify(failingExecutor, times(1)).shutdown();
        } finally {
            originalExecutor.shutdownNow();
            assertThat(originalExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedCloseWaitDoesNotCancelDrainingOrTakeTheApplicationExecutor() throws Exception {
        final BlockingCustomMailer delivery = new BlockingCustomMailer();
        final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor();
        final Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(applicationExecutor).build()).buildMailer();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeReturned = new CountDownLatch(1);
        final Thread closingThread = new Thread(() -> {
            closeStarted.countDown();
            try {
                mailer.close();
                closeFailure.set(new AssertionError("Close cannot finish while the accepted send is blocked"));
            } catch (ExecutionException | InterruptedException failure) {
                closeFailure.set(failure);
            } finally {
                closeReturned.countDown();
            }
        }, "test-mailer-close-waiter");
        try {
            final CompletableFuture<?> send = mailer.async().sendMail(email()).getCompletion();
            assertThat(delivery.entered.await(5, SECONDS)).isTrue();
            closingThread.start();
            assertThat(closeStarted.await(5, SECONDS)).isTrue();
            closingThread.interrupt();
            assertThat(closeReturned.await(5, SECONDS)).isTrue();
            assertThat(closeFailure.get()).isInstanceOf(InterruptedException.class);
            final CompletableFuture<Void> cleanup = mailer.closeAsync();
            assertThat(cleanup).isNotDone();
            assertThat(send).isNotDone();
            assertThat(applicationExecutor.isShutdown()).isFalse();
            delivery.finish.countDown();
            send.get(5, SECONDS);
            cleanup.get(5, SECONDS);
            mailer.close();
            assertThat(applicationExecutor.submit(() -> "still application-owned").get(5, SECONDS)).isEqualTo("still application-owned");
        } finally {
            delivery.finish.countDown();
            closingThread.join(5000);
            mailer.close();
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
            assertThat(closingThread.isAlive()).isFalse();
        }
    }

    @Test
    void inlineObserverCanInitiateAsyncCloseButCannotWaitForItself() throws Exception {
        final BlockingCustomMailer delivery = new BlockingCustomMailer();
        delivery.finish.countDown();
        final AtomicReference<Mailer> owner = new AtomicReference<>();
        final AtomicReference<CompletableFuture<Void>> closing = new AtomicReference<>();
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
            closing.set(owner.get().closeAsync());
            assertThat(closing.get()).isNotDone();
            assertThatThrownBy(owner.get()::close).isInstanceOf(IllegalStateException.class).hasMessageContaining("own shutdown");
        }).build()).buildMailer()) {
            owner.set(mailer);
            mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
            closing.get().get(5, SECONDS);
        }
    }

    private static SimpleJavaMail mail() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    private static Email email() {
        return mail().emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(EmailHelper.parsedRecipients(null, false, TO, "receiver@example.org"))
                .fixingMessageId("<lifecycle@example.org>").withPlainText("Lifecycle test").buildEmail();
    }

    private static final class BlockingCustomMailer implements CustomMailer {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch finish = new CountDownLatch(1);

        @Override
        public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
            entered.countDown();
            try {
                assertThat(finish.await(5, SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void testConnection(final OperationalConfig config, final Session session) {
        }
    }
}
