package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.EmailHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static jakarta.mail.Message.RecipientType.TO;

@Timeout(20)
class MailSendQueueTest {
    @Test
    void queueDiagnosticsRequireAnExplicitMailerImplementation() throws NoSuchMethodException {
        assertThat(Mailer.class.getMethod("getAsyncQueueSnapshot").isDefault()).isFalse();
    }

    @Test
    void anExecutorBorrowedFromAnotherMailerRemainsCallerOwned() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        try (Mailer owner = builder(transport).withAsyncQueueCapacity(1).buildMailer()) {
            final ExecutorService executor = owner.getOperationalConfig().getExecutorService();
            try (Mailer borrower = builder(transport).withExecutorService(executor).buildMailer()) {
                assertThat(borrower.getAsyncQueueSnapshot()).isEmpty();
                assertThat(borrower.getOperationalConfig().isExecutorServiceIsUserProvided()).isTrue();
                assertThat(owner.getAsyncQueueSnapshot()).isPresent();
            }
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    @Test
    void queueBoundWorksWithoutTheBatchModuleAndKeepsTheSingleWorkerDefault() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final Mailer mailer;
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            mailer = builder(transport).withThreadPoolSize(4).withAsyncQueueCapacity(1).buildMailer();
        }
        try {
            final CompletableFuture<Void> first = mailer.sendMailAsync(email("first"));
            transport.awaitStarted();
            final CompletableFuture<Void> queued = mailer.sendMailAsync(email("queued"));
            assertThat(snapshot(mailer).getWorkerLimit()).isEqualTo(1);
            assertReason(failure(mailer.sendMailAsync(email("rejected"))), AsyncQueueRejectionReason.QUEUE_FULL);
            transport.release.countDown();
            CompletableFuture.allOf(first, queued).get(5, TimeUnit.SECONDS);
        } finally {
            transport.release.countDown();
            mailer.close();
        }
    }

    @Test
    void closeFromAnObserverCannotDeadlockItsOwnWorker() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        transport.release.countDown();
        final AtomicReference<Mailer> owner = new AtomicReference<>();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try (Mailer mailer = builder(transport).withMailSendObserver(outcome -> {
            try {
                owner.get().close();
            } catch (Exception failure) {
                closeFailure.set(failure);
            }
        }).buildMailer()) {
            owner.set(mailer);
            mailer.sendMailAsync(email("observed")).get(5, TimeUnit.SECONDS);
            assertThat(closeFailure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("own shutdown");
            assertThat(snapshot(mailer).isShutdown()).isFalse();
        }
    }

    @Test
    void defaultRemainsUnboundedAndDoesNotLimitTheApplicationToTheWorkerCount() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final Mailer mailer = builder(transport).buildMailer();
        try {
            final List<CompletableFuture<Void>> sends = new ArrayList<>();
            sends.add(mailer.sendMailAsync(email("first")));
            transport.awaitStarted();
            for (int index = 0; index < 25; index++) {
                sends.add(mailer.sendMailAsync(email("queued-" + index)));
            }
            assertThat(snapshot(mailer).getConfiguration().getCapacity()).isEqualTo(-1);
            assertThat(snapshot(mailer).getQueuedCount()).isEqualTo(25);
            transport.release.countDown();
            CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[0])).get(5, TimeUnit.SECONDS);
        } finally {
            transport.release.countDown();
            mailer.close();
        }
    }

    @Test
    void rejectionIsObservedOnCallerAndDoesNotOpenBatchOrConnectionTestResources() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final AtomicReference<Thread> observerThread = new AtomicReference<>();
        final Mailer mailer = builder(transport).withAsyncQueueCapacity(1).withMailSendObserver(outcome -> {
            outcomes.add(outcome);
            observerThread.set(Thread.currentThread());
        }).buildMailer();
        try {
            mailer.sendMailAsync(email("first"));
            transport.awaitStarted();
            mailer.sendMailAsync(email("second"));
            final Throwable failure = failure(mailer.sendMailAsync(email("rejected")));
            assertReason(failure, AsyncQueueRejectionReason.QUEUE_FULL);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getReadyAt()).isPresent();
            assertThat(outcomes.get(0).getStartedAt()).isEmpty();
            assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
            assertThat(observerThread).hasValue(Thread.currentThread());
            final AtomicBoolean iterated = new AtomicBoolean();
            assertReason(failure(mailer.sendMailsInSimpleBatch(() -> {
                iterated.set(true);
                throw new AssertionError("Rejected batch must not open its iterator");
            }, true)), AsyncQueueRejectionReason.QUEUE_FULL);
            assertReason(failure(mailer.testConnection(true)), AsyncQueueRejectionReason.QUEUE_FULL);
            assertThat(iterated).isFalse();
            assertThat(transport.tests).isEqualTo(0);
            final AsyncQueueSnapshot snapshot = snapshot(mailer);
            assertThat(snapshot.getActiveCount()).isEqualTo(1);
            assertThat(snapshot.getQueuedCount()).isEqualTo(1);
            assertThat(snapshot.getRejectionCounts()).containsEntry(AsyncQueueRejectionReason.QUEUE_FULL, 3L);
            assertThatThrownBy(() -> snapshot.getRejectionCounts().clear()).isInstanceOf(UnsupportedOperationException.class);
            transport.release.countDown();
            mailer.close();
            assertThat(transport.subjects).containsExactly("first", "second");
            assertThat(snapshot.getQueuedCount()).as("previous snapshots stay detached").isEqualTo(1);
        } finally {
            transport.release.countDown();
            mailer.close();
        }
    }

    @Test
    void boundedWaitAdmitsAfterCapacityIsFreedWithoutRunningOnTheSubmittingThread() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final Mailer mailer = builder(transport).withAsyncQueueCapacity(1)
                .withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY).withAsyncQueueWaitTimeoutMillis(3000).buildMailer();
        try {
            mailer.sendMailAsync(email("first"));
            transport.awaitStarted();
            mailer.sendMailAsync(email("second"));
            final CountDownLatch entering = new CountDownLatch(1);
            final AtomicReference<Thread> submittingThread = new AtomicReference<>();
            final Future<CompletableFuture<Void>> third = caller.submit(() -> {
                submittingThread.set(Thread.currentThread());
                entering.countDown();
                return mailer.sendMailAsync(email("third"));
            });
            assertThat(entering.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(third.isDone()).isFalse();
            transport.release.countDown();
            third.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertThat(transport.subjects).containsExactly("first", "second", "third");
            assertThat(transport.threads).doesNotContain(submittingThread.get());
        } finally {
            transport.release.countDown();
            caller.shutdownNow();
            mailer.close();
        }
    }

    @Test
    void zeroCapacityRejectsWhileBusyAndBoundedWaitingCanTimeOutOrBeInterrupted() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final Mailer mailer = builder(transport).withAsyncQueueCapacity(0)
                .withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY).withAsyncQueueWaitTimeoutMillis(60).buildMailer();
        try {
            mailer.sendMailAsync(email("first"));
            transport.awaitStarted();
            assertReason(failure(mailer.sendMailAsync(email("timed-out"))), AsyncQueueRejectionReason.CAPACITY_WAIT_TIMED_OUT);
            assertThat(snapshot(mailer).getQueuedCount()).isZero();
            Thread.currentThread().interrupt();
            final CompletableFuture<Void> interrupted = mailer.sendMailAsync(email("interrupted"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
            assertReason(failure(interrupted), AsyncQueueRejectionReason.CAPACITY_WAIT_INTERRUPTED);
        } finally {
            Thread.interrupted();
            transport.release.countDown();
            mailer.close();
        }
    }

    @Test
    void shutdownStopsAdmissionAndDrainsAcceptedWorkBeforeClosingPools() throws Exception {
        final BlockingMailer transport = new BlockingMailer();
        final Mailer mailer = builder(transport).withAsyncQueueCapacity(1).buildMailer();
        try {
            final CompletableFuture<Void> first = mailer.sendMailAsync(email("first"));
            transport.awaitStarted();
            final CompletableFuture<Void> queued = mailer.sendMailAsync(email("second"));
            final Future<Void> closing = mailer.shutdownConnectionPool();
            assertThat(closing.isDone()).isFalse();
            assertThat(mailer.shutdownConnectionPool()).isSameAs(closing);
            assertReason(failure(mailer.sendMailAsync(email("after-close"))), AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN);
            transport.release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertThat(first).isCompleted();
            assertThat(queued).isCompleted();
            assertThat(transport.subjects).containsExactly("first", "second");
        } finally {
            transport.release.countDown();
            mailer.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "DEFAULT_ASYNC_QUEUE_CAPACITY, 7, 7, REJECT, 1000",
            "DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, WAIT_FOR_CAPACITY, -1, WAIT_FOR_CAPACITY, 1000",
            "DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, 250, -1, REJECT, 250"
    })
    void partialQueuePropertiesKeepTheRemainingBuilderDefaults(final ConfigLoader.Property property, final String value,
                                                               final int capacity, final AsyncQueueOverflowPolicy overflowPolicy,
                                                               final int waitTimeoutMillis) {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("application", Map.of(property.key(), value)).load();
        final MailerRegularBuilder<?> configured = SimpleJavaMail.withConfig(config).mailerBuilder();
        assertThat(configured.getAsyncQueueConfig()).isEqualTo(new AsyncQueueConfig(capacity, overflowPolicy, waitTimeoutMillis));
    }

    @Test
    void configurationIsTypedDetachedOverridableAndCannotSilentlyConfigureACallerExecutor() throws Exception {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("application", Map.of(
                "simplejavamail.defaults.async.queue.capacity", "7",
                "simplejavamail.defaults.async.queue.overflowpolicy", "WAIT_FOR_CAPACITY",
                "simplejavamail.defaults.async.queue.waittimeoutmillis", "200")).load();
        final MailerRegularBuilder<?> configured = SimpleJavaMail.withConfig(config).mailerBuilder().withSMTPServer("localhost", 25);
        assertThat(configured.getAsyncQueueConfig()).isEqualTo(new AsyncQueueConfig(7, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY, 200));
        configured.withAsyncQueueCapacity(3);
        assertThat(SimpleJavaMail.withConfig(config).mailerBuilder().getAsyncQueueConfig().getCapacity()).isEqualTo(7);
        assertThat(builder(new BlockingMailer()).getAsyncQueueConfig()).isEqualTo(new AsyncQueueConfig(-1, AsyncQueueOverflowPolicy.REJECT, 1000));
        assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.EXECUTION_AND_POOLING)).hasSize(3);
        assertThatThrownBy(() -> configured.withAsyncQueueCapacity(-2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configured.withAsyncQueueWaitTimeoutMillis(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configured.withAsyncQueueOverflowPolicy(null)).isInstanceOf(NullPointerException.class);
        final ExecutorService callerOwned = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> configured.withExecutorService(callerOwned).buildMailer()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("caller-owned executor");
            try (Mailer mailer = configured.resetAsyncQueue().buildMailer()) {
                assertThat(mailer.getOperationalConfig().getExecutorService()).isSameAs(callerOwned);
                assertThat(mailer.getAsyncQueueSnapshot()).isEmpty();
                assertThat(mailer.getOperationalConfig().getAsyncQueueConfig())
                        .isEqualTo(new AsyncQueueConfig(-1, AsyncQueueOverflowPolicy.REJECT, 1000));
            }
            assertThat(callerOwned.isShutdown()).isFalse();
        } finally {
            callerOwned.shutdownNow();
        }
    }

    @Test
    void operationalConfigReceivesTheResolvedQueueSettingsAndRetainsItsSnapshot() throws Exception {
        assertThat(OperationalConfig.class.getMethod("getAsyncQueueConfig").isDefault()).isFalse();
        final MailerRegularBuilder<?> configured = builder(new BlockingMailer())
                .withAsyncQueueCapacity(3).withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY)
                .withAsyncQueueWaitTimeoutMillis(250);
        final AsyncQueueConfig supplied = configured.getAsyncQueueConfig();
        try (Mailer mailer = configured.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getAsyncQueueConfig()).isSameAs(supplied);
            configured.resetAsyncQueue();
            assertThat(configured.getAsyncQueueConfig()).isEqualTo(new AsyncQueueConfig(-1, AsyncQueueOverflowPolicy.REJECT, 1000));
            assertThat(mailer.getOperationalConfig().getAsyncQueueConfig()).isSameAs(supplied);
            assertThat(supplied).isEqualTo(new AsyncQueueConfig(3, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY, 250));
        }
    }

    private static MailerRegularBuilder<?> builder(final CustomMailer transport) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder()
                .withCustomMailer(transport).withThreadPoolSize(1);
    }

    private static Email email(final String subject) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank()
                .from("sender@example.org").withRecipients(EmailHelper.parsedRecipients(null, false, TO, "receiver@example.org"))
                .withSubject(subject).withPlainText("Queue test").buildEmail();
    }

    private static AsyncQueueSnapshot snapshot(final Mailer mailer) {
        return mailer.getAsyncQueueSnapshot().orElseThrow();
    }

    private static Throwable failure(final CompletableFuture<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Expected failure");
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static void assertReason(final Throwable failure, final AsyncQueueRejectionReason reason) {
        assertThat(failure).isInstanceOf(MailSendRejectedException.class);
        assertThat(((MailSendRejectedException) failure).getReason()).isEqualTo(reason);
    }

    private static final class BlockingMailer implements CustomMailer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> subjects = new CopyOnWriteArrayList<>();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private int tests;

        @Override
        public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
            subjects.add(email.getSubject());
            threads.add(Thread.currentThread());
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override
        public void testConnection(final OperationalConfig config, final Session session) {
            tests++;
        }

        void awaitStarted() throws InterruptedException {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
