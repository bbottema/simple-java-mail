package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendCleanupException;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;
import testutil.ConfigLoaderTestHelper;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Timeout(20)
class LateCancellationCleanupFailureTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @BeforeAll
    @Timeout(20)
    static void initializeMimeAndMockingFixtures() throws Exception {
        try (MockedStatic<ModuleLoader> ignored = withoutPool()) {
            MAIL.converter().fromEmail(email()).withSession(session(new State(null))).toMimeMessage();
        }
    }

    @ParameterizedTest
    @EnumSource(CleanupFailure.class)
    void lateCancellationRetainsIndependentCleanupFailureAndObservedAcceptance(final CleanupFailure kind) throws Exception {
        final State state = new State(kind.problem());
        try (Scenario scenario = new Scenario(state)) {
            final MailSend<MailSubmissionReceipt> send = scenario.mailer.async().sendMail(email());
            assertThat(state.closing.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            state.release.countDown();

            final Throwable failure = failure(send);
            assertCleanupFailure(failure, state.closeFailure, true);
            assertThat(scenario.observed).singleElement().satisfies(outcome -> {
                assertThat(outcome.getFailure()).containsSame(failure);
                assertThat(outcome.getSubmissionReceipt()).get().satisfies(receipt ->
                        assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED));
            });
        }
    }

    @ParameterizedTest
    @EnumSource(CleanupFailure.class)
    void independentCleanupFailureAlsoRemainsVisibleWithoutAStop(final CleanupFailure kind) throws Exception {
        final State state = new State(kind.problem());
        try (Scenario scenario = new Scenario(state)) {
            final MailSend<MailSubmissionReceipt> send = scenario.mailer.async().sendMail(email());
            assertThat(state.closing.await(5, SECONDS)).isTrue();
            state.release.countDown();
            assertCleanupFailure(failure(send), state.closeFailure, false);
            assertThat(scenario.observed).singleElement().satisfies(outcome ->
                    assertThat(outcome.getSubmissionReceipt()).get().satisfies(receipt ->
                            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED)));
        }
    }

    @ParameterizedTest
    @EnumSource(CleanupFailure.class)
    void primarySubmissionFailureRetainsSecondaryCleanupRatherThanChangingItsIdentity(final CleanupFailure kind) throws Exception {
        final State state = new State(kind.problem());
        state.sendFailure = new MessagingException("submission rejected");
        try (Scenario scenario = new Scenario(state)) {
            final MailSend<MailSubmissionReceipt> send = scenario.mailer.async().sendMail(email());
            assertThat(state.closing.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            state.release.countDown();

            final Throwable failure = failure(send);
            assertThat(failure).isInstanceOf(MailSubmissionException.class).hasCause(state.sendFailure);
            assertThat(failure.getSuppressed()).containsExactly(state.closeFailure);
            assertThat(((MailException) failure).getSubmissionReceipt()).get().satisfies(receipt ->
                    assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN));
            assertThat(scenario.observed).singleElement().satisfies(outcome -> assertThat(outcome.getFailure()).containsSame(failure));
        }
    }

    @Test
    void lateCancellationStillCannotUndoAcceptanceWhenCleanupSucceeds() throws Exception {
        final State state = new State(null);
        try (Scenario scenario = new Scenario(state)) {
            final MailSend<MailSubmissionReceipt> send = scenario.mailer.async().sendMail(email());
            assertThat(state.closing.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            state.release.countDown();
            assertThat(send.getCompletion().get(5, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CleanupFailure.class, names = {"RUNTIME", "ERROR"})
    void lateCancellationCannotHideAnIndependentPooledReleaseFailure(final CleanupFailure kind) throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            final Throwable original = kind.problem();
            final AcquiredMailTransport acquired = pooled(control, original);
            assertThat(submitPrepared(acquired, control).getStatus())
                    .isEqualTo(MailSubmissionStatus.ACCEPTED);
            control.requestCancellation();
            assertThat(catchThrowable(acquired::close)).isSameAs(original);
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void deadlineDoesNotTurnIndependentPooledReleaseFailureIntoSuccess() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        final CountDownLatch stopped = new CountDownLatch(1);
        try (MailSendControl control = new MailSendControl(Duration.ofSeconds(5), watcher)) {
            final RuntimeException original = new IllegalStateException("pool release failed independently");
            final AcquiredMailTransport acquired = pooled(control, original);
            assertThat(submitPrepared(acquired, control).getStatus())
                    .isEqualTo(MailSubmissionStatus.ACCEPTED);
            control.onStop(stopped::countDown);
            assertThat(stopped.await(8, SECONDS)).isTrue();
            assertThat(catchThrowable(acquired::close)).isSameAs(original);
        } finally {
            watcher.shutdownNow();
        }
    }

    private static AcquiredMailTransport pooled(final MailSendControl control, final Throwable releaseFailure) throws Exception {
        final State state = new State(null);
        final Session session = session(state);
        final Transport transport = new CleanupTransport(session, null);
        transport.connect();
        final LifecycleDelegatingTransport lease = mock(LifecycleDelegatingTransport.class);
        when(lease.getSessionUsedToObtainTransport()).thenReturn(session);
        when(lease.getTransport()).thenReturn(transport);
        doThrow(releaseFailure).when(lease).signalTransportUsed();
        final SelectedPoolTransport selected = mock(SelectedPoolTransport.class);
        when(selected.acquireTransport(any(), any())).thenReturn(lease);
        return AcquiredMailTransport.acquire(session, selected, control, PoolConnectionObserver.NONE, MailSendDiagnosticsRecorder.unobserved());
    }

    private static MailSubmissionReceipt submitPrepared(final AcquiredMailTransport acquired, final MailSendControl control) throws Exception {
        final jakarta.mail.internet.MimeMessage message = MAIL.converter().fromEmail(email()).toMimeMessage();
        final PreparedMail prepared = PreparedMail.builder().withMimeMessage(message).withRecipients(message.getAllRecipients())
                .withDeliveryEnvelope(DeliveryEnvelope.builder().build()).withContentRequirement(ContentRequirement.NORMAL).build();
        return acquired.sendPrepared(prepared, null, null, control, MailSendDiagnosticsRecorder.unobserved(),
                mock(SendingAllowance.Reservation.class), () -> { });
    }

    private static void assertCleanupFailure(final Throwable failure, final Throwable original, final boolean cancelled) {
        if (original instanceof Error) {
            assertThat(failure).isSameAs(original);
        } else {
            assertThat(failure).isInstanceOf(cancelled ? MailSendCancelledException.class : MailSendCleanupException.class)
                    .hasRootCause(original);
            assertThat(((MailException) failure).getSubmissionReceipt()).get().satisfies(receipt ->
                    assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED));
        }
    }

    private static Throwable failure(final MailSend<?> send) throws Exception {
        try {
            send.getCompletion().get(5, SECONDS);
            throw new AssertionError("Expected failed completion");
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    private static Email email() {
        return MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").withPlainText("body").buildEmail();
    }

    private static Session session(final State state) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        // Keep reverse DNS out of the cancellation and cleanup timing boundary.
        properties.setProperty("mail.host", "localhost");
        properties.setProperty("mail.smtp.host", "fixture");
        properties.setProperty("mail.smtp.port", "25");
        properties.put(State.class.getName(), state);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", CleanupTransport.class.getName(), "fixture", "1"));
        return session;
    }

    private static MockedStatic<ModuleLoader> withoutPool() {
        final MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
        return modules;
    }

    private static final class Scenario implements AutoCloseable {
        private final State state;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "cleanup-cancellation-fixture");
            thread.setUncaughtExceptionHandler((unused, failure) -> { });
            return thread;
        });
        private final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        private final Mailer mailer;

        private Scenario(final State state) throws Exception {
            this.state = state;
            final ExecutorService executor = new AbstractExecutorService() {
                @Override public void execute(final Runnable task) {
                    worker.execute(() -> { try (MockedStatic<ModuleLoader> ignored = withoutPool()) { task.run(); } });
                }
                @Override public void shutdown() { worker.shutdown(); }
                @Override public List<Runnable> shutdownNow() { return worker.shutdownNow(); }
                @Override public boolean isShutdown() { return worker.isShutdown(); }
                @Override public boolean isTerminated() { return worker.isTerminated(); }
                @Override public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
                    return worker.awaitTermination(timeout, unit);
                }
            };
            try (MockedStatic<ModuleLoader> ignored = withoutPool()) {
                mailer = MAIL.mailerBuilder(session(state))
                        .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(executor).build())
                        .withSendHandlers(MailSendHandlers.builder().withResultObserver(observed::add).build()).buildMailer();
            }
        }

        @Override public void close() throws Exception {
            state.release.countDown();
            try { mailer.close(); }
            finally { worker.shutdownNow(); assertThat(worker.awaitTermination(5, SECONDS)).isTrue(); }
        }
    }

    private static final class State {
        private final Throwable closeFailure;
        private final CountDownLatch closing = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private MessagingException sendFailure;
        private State(final Throwable closeFailure) { this.closeFailure = closeFailure; }
    }

    public static final class CleanupTransport extends Transport {
        private final State state;
        public CleanupTransport(final Session session, final URLName name) {
            super(session, name);
            state = (State) session.getProperties().get(State.class.getName());
        }
        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) { return true; }
        @Override public void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
            if (state.sendFailure != null) throw state.sendFailure;
        }
        @Override public void close() throws MessagingException {
            state.closing.countDown();
            try {
                if (!state.release.await(10, SECONDS)) throw new AssertionError("Fixture cleanup was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MessagingException("Fixture cleanup interrupted", interrupted);
            }
            setConnected(false);
            if (state.closeFailure instanceof MessagingException) throw (MessagingException) state.closeFailure;
            if (state.closeFailure instanceof RuntimeException) throw (RuntimeException) state.closeFailure;
            if (state.closeFailure instanceof Error) throw (Error) state.closeFailure;
        }
    }

    private enum CleanupFailure {
        CHECKED { @Override Throwable problem() { return new MessagingException("independent provider cleanup failure"); } },
        RUNTIME { @Override Throwable problem() { return new IllegalStateException("independent provider cleanup defect"); } },
        ERROR { @Override Throwable problem() { return new AssertionError("independent provider cleanup error"); } };
        abstract Throwable problem();
    }
}
