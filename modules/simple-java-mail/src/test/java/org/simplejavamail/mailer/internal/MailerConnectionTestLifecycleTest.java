package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class MailerConnectionTestLifecycleTest {

    @Test
    void synchronousTestAfterCloseDoesNotInvokeCustomConnectionWork() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).buildMailer()) {
            mailer.close();

            assertThatThrownBy(mailer.sync()::testConnection)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("shutting down")
                    .hasMessageContaining("new Mailer");
            assertThat(delivery.connectionTests.get()).isZero();
        }
    }

    @Test
    void asynchronousTestAfterCloseDoesNotInvokeConnectionWorkOnAnApplicationExecutor() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor();
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withExecutorService(applicationExecutor).buildMailer()) {
            mailer.close();

            assertShutdownFailure(mailer.async().testConnection());
            assertThat(delivery.connectionTests.get()).isZero();
            assertThat(applicationExecutor.submit(() -> "still available").get(5, SECONDS)).isEqualTo("still available");
        } finally {
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void connectionTestsAreRejectedWhileAcceptedSendsAreStillDraining() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        delivery.blockSends = true;
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).buildMailer()) {
            final CompletableFuture<?> send = mailer.async().sendMail(email()).getCompletion();
            assertThat(delivery.sendEntered.await(5, SECONDS)).isTrue();
            final CompletableFuture<Void> closing = mailer.closeAsync();
            try {
                assertThat(closing).isNotDone();
                assertThatThrownBy(mailer.sync()::testConnection)
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("shutting down");
                assertShutdownFailure(mailer.async().testConnection());
                assertThat(delivery.connectionTests.get()).isZero();
            } finally {
                delivery.finishSend.countDown();
            }
            send.get(5, SECONDS);
            closing.get(5, SECONDS);
        } finally {
            delivery.finishSend.countDown();
        }
    }

    @Test
    void queuedConnectionTestRechecksShutdownBeforeStartingConnectionWork() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor();
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        applicationExecutor.execute(() -> {
            workerEntered.countDown();
            await(releaseWorker);
        });
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withExecutorService(applicationExecutor).buildMailer()) {
            assertThat(workerEntered.await(5, SECONDS)).isTrue();
            final CompletableFuture<Void> test = mailer.async().testConnection();
            assertThat(test).isNotDone();

            // Helper checks are not admitted sends: closing need not wait for an unstarted check on an application executor.
            mailer.closeAsync().get(5, SECONDS);
            releaseWorker.countDown();

            assertShutdownFailure(test);
            assertThat(delivery.connectionTests.get()).isZero();
        } finally {
            releaseWorker.countDown();
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void openMailerStillTestsSynchronouslyAndOnAnApplicationExecutor() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        final ExecutorService applicationExecutor = Executors.newSingleThreadExecutor();
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withExecutorService(applicationExecutor).buildMailer()) {
            mailer.sync().testConnection();
            mailer.async().testConnection().get(5, SECONDS);

            assertThat(delivery.connectionTests.get()).isEqualTo(2);
        } finally {
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void openConnectionFailureRetainsItsOriginalIdentity() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        delivery.connectionFailure = new IllegalStateException("application connection failure");
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).buildMailer()) {
            assertThatThrownBy(mailer.sync()::testConnection).isSameAs(delivery.connectionFailure);
            assertThatThrownBy(() -> mailer.async().testConnection().get(5, SECONDS)).hasCauseReference(delivery.connectionFailure);
            assertThat(delivery.connectionTests.get()).isEqualTo(2);
        }
    }

    @Test
    void applicationExecutorMayWaitForItsWorkerToFinishBeforeReturningFromExecute() throws Exception {
        final CountingCustomMailer delivery = new CountingCustomMailer();
        final ExecutorService applicationExecutor = new JoiningExecutor();
        try (Mailer mailer = mail().mailerBuilder().withCustomMailer(delivery).withExecutorService(applicationExecutor).buildMailer()) {
            mailer.async().testConnection().get(5, SECONDS);

            assertThat(delivery.connectionTests.get()).isEqualTo(1);
        } finally {
            applicationExecutor.shutdownNow();
            assertThat(applicationExecutor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    private static void assertShutdownFailure(final CompletableFuture<Void> test) {
        assertThatThrownBy(() -> test.get(5, SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("This Mailer is shutting down. Build a new Mailer before testing another connection.");
    }

    private static SimpleJavaMail mail() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    }

    private static Email email() {
        final SimpleJavaMail mail = mail();
        return mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "receiver@example.org"))
                .fixingMessageId("<connection-lifecycle@example.org>")
                .withPlainText("Hold shutdown open").buildEmail();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class CountingCustomMailer implements CustomMailer {
        private final AtomicInteger connectionTests = new AtomicInteger();
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch finishSend = new CountDownLatch(1);
        private boolean blockSends;
        private RuntimeException connectionFailure;

        @Override
        public void testConnection(final OperationalConfig config, final Session session) {
            connectionTests.incrementAndGet();
            if (connectionFailure != null) {
                throw connectionFailure;
            }
        }

        @Override
        public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
            sendEntered.countDown();
            if (blockSends) {
                await(finishSend);
            }
        }
    }

    /** Exposes cross-thread monitor ownership without leaving a blocked test behind if submission holds the Mailer monitor. */
    private static final class JoiningExecutor extends AbstractExecutorService {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();

        @Override
        public void execute(final Runnable command) {
            final Future<?> completion = worker.submit(command);
            try {
                completion.get(2, SECONDS);
            } catch (TimeoutException failure) {
                completion.cancel(true);
                throw new RejectedExecutionException("Application executor could not finish its worker before returning from execute()", failure);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(failure);
            } catch (ExecutionException failure) {
                throw new RejectedExecutionException(failure.getCause());
            }
        }

        @Override
        public void shutdown() {
            worker.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return worker.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return worker.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return worker.isTerminated();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return worker.awaitTermination(timeout, unit);
        }
    }
}
