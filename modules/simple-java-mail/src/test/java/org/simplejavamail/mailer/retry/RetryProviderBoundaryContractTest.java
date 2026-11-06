package org.simplejavamail.mailer.retry;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import jakarta.mail.Message.RecipientType;

import jakarta.mail.Session;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import org.simplejavamail.MailException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.MailerException;
import org.simplejavamail.mailer.internal.ConnectionSetupFailure;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.simplejavamail.recipient.internal.RecipientBuilderImpl;
import testutil.retry.RetryProviderTransport;

import java.time.Duration;
import java.util.List;
import java.util.Arrays;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.BindException;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

@Timeout(30)
class RetryProviderBoundaryContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    private Mailer mailer(final RetryProviderTransport.State state, final List<MailSendOutcome> outcomes) throws Exception {
        final Session session = RetryProviderTransport.session(state);
        return mail.mailerBuilder(session).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).withExpireAfter(Duration.ofMillis(60000)).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofMillis(1)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer();
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("first@example.test").withType(RecipientType.TO).build(), SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("second@example.test").withType(RecipientType.TO).build())
                .withPlainText("provider-neutral body").buildEmail();
    }
    @Test
    void independentProviderFreezesOnceAndRetriesUsingStructuredEvidence() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mailer(state, outcomes)) {
            final var receipt = mailer.async().sendMail(email()).getCompletion().get(10, SECONDS);
            assertThat(state.invocations).hasValue(2);
            assertThat(state.captures).hasValue(1);
            assertThat(state.content.get(1)).isEqualTo(state.content.get(0));
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("uncheckedRetryFailures")
    void laterUncheckedInvocationFailureDoesNotReuseTheEarlierRefusal(final String scope, final Throwable providerFailure) throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.laterInvocationFailure = providerFailure;
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mailer(state, outcomes)) {
            final Throwable failure;
            switch (scope) {
                case "async":
                    failure = mailer.async().sendMail(email()).getCompletion().handle((value, cause) -> cause).get(10, SECONDS);
                    break;
                case "batch":
                    failure = catchThrowable(() -> mailer.sync().sendMailsInSimpleBatch(List.of(email(), email())));
                    break;
                case "open connection":
                    failure = catchThrowable(() -> mailer.withOpenConnection(sender -> sender.sendMail(email())));
                    break;
                default:
                    failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            }
            assertThat(failure).isSameAs(providerFailure);
            assertThat(state.invocations).hasValue(2);
            assertThat(outcomes).hasSize(1);
            final MailSendOutcome outcome = outcomes.get(0);
            assertThat(outcome.getFailure()).containsSame(providerFailure);
            assertThat(outcome.getSubmissionReceipt()).isEmpty();
            final var history = outcome.getDiagnostics().orElseThrow().getSubmissionHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getSubmissionStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
            assertThat(history.get(0).getSmtpReplyCode()).isEqualTo(451);
            assertThat(history.get(0).getRetryDelay()).isNotNull();
            assertThat(history.get(1).getSubmissionStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
            assertThat(history.get(1).getRetryDelay()).isNull();
        }
    }

    private static Stream<Arguments> uncheckedRetryFailures() {
        return Stream.of("sync", "async", "batch", "open connection").flatMap(scope -> Stream.of(
                Arguments.of(scope, new IllegalStateException("provider failed during its second invocation")),
                Arguments.of(scope, new MailerException(MailFailureKind.SUBMISSION, "provider failed without a second receipt", null)),
                Arguments.of(scope, new AssertionError("provider failed during its second invocation"))));
    }

    @ParameterizedTest
    @ValueSource(strings = { "sync", "async" })
    void failedReacquisitionKeepsTheLastRefusalWithoutInventingAnotherSubmission(final String scope) throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.laterConnectionFailure = new MessagingException("fixture reconnect failed");
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mailer(state, outcomes)) {
            final Throwable failure = scope.equals("async")
                    ? mailer.async().sendMail(email()).getCompletion().handle((value, cause) -> cause).get(10, SECONDS)
                    : catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(ConnectionSetupFailure.class).hasRootCause(state.laterConnectionFailure);
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONNECTION);
            assertThat(state.invocations).hasValue(1);
            assertThat(outcomes).hasSize(1);
            final var receipt = outcomes.get(0).getSubmissionReceipt().orElseThrow();
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
            assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.SAFE_TO_RETRY_ALL);
            assertThat(((MailException) failure).getSubmissionReceipt()).containsSame(receipt);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "sync", "batch", "open connection" })
    void interruptedRetryWaitKeepsTheLastRefusalAndTheInterruption(final String scope) throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final CountDownLatch retryWaiting = new CountDownLatch(1);
        final AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        final AtomicBoolean callerInterrupted = new AtomicBoolean();
        try (Mailer mailer = mail.mailerBuilder(RetryProviderTransport.session(state))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2)
                        .withRetryDelay(Duration.ofHours(1)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add)
                        .withProgressObserver(progress -> {
                            if (progress.getStage() == MailSendProgressStage.WAITING_TO_RETRY) { retryWaiting.countDown(); }
                        }).build()).buildMailer()) {
            final Thread sender = new Thread(() -> {
                try {
                    callerFailure.set(catchThrowable(() -> {
                        switch (scope) {
                            case "batch": mailer.sync().sendMailsInSimpleBatch(List.of(email(), email())); break;
                            case "open connection": mailer.withOpenConnection(connection -> connection.sendMail(email())); break;
                            default: mailer.sync().sendMail(email());
                        }
                    }));
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
            }, "interrupted-retry-" + scope);
            sender.start();
            try {
                assertThat(retryWaiting.await(10, SECONDS)).as("the first refusal entered retry waiting").isTrue();
                sender.interrupt();
                sender.join(10000);
                assertThat(sender.isAlive()).isFalse();
            } finally {
                if (sender.isAlive()) { sender.interrupt(); sender.join(10000); }
            }
            final Throwable failure = callerFailure.get();
            assertThat(failure).isInstanceOf(MailException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CAPACITY);
            assertThat(callerInterrupted).isTrue();
            assertThat(state.invocations).hasValue(1);
            assertThat(outcomes).hasSize(1);
            final var receipt = outcomes.get(0).getSubmissionReceipt().orElseThrow();
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
            assertThat(((MailException) failure).getSubmissionReceipt()).containsSame(receipt);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory()).hasSize(1);
        }
    }

    @Test
    @Timeout(45)
    void failedPooledReacquisitionKeepsTheRefusalOnThePoolFailure() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final CountDownLatch retryWaiting = new CountDownLatch(1);
        final CountDownLatch connectionHeld = new CountDownLatch(1);
        final CountDownLatch releaseConnection = new CountDownLatch(1);
        state.laterInvocationAction = () -> {
            connectionHeld.countDown();
            try {
                if (!releaseConnection.await(20, SECONDS)) { throw new AssertionError("The competing connection was not released"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (Mailer mailer = mail.mailerBuilder(RetryProviderTransport.session(state))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1)
                        .withClaimTimeout(Duration.ofSeconds(5)).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(2).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2)
                        .withRetryDelay(Duration.ofSeconds(8)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add)
                        .withProgressObserver(progress -> {
                            if (progress.getStage() == MailSendProgressStage.WAITING_TO_RETRY) { retryWaiting.countDown(); }
                        }).build()).buildMailer()) {
            final var send = mailer.async().sendMail(email());
            assertThat(retryWaiting.await(10, SECONDS))
                    .as("first send reached backoff; terminal failure: %s", send.getCompletion().handle((value, failure) -> failure).getNow(null)).isTrue();
            final var competingSend = mailer.async().sendMail(email());
            try {
                assertThat(connectionHeld.await(5, SECONDS)).as("the competing provider invocation owns the only pooled connection").isTrue();
                assertThat(competingSend.getCompletion()).isNotDone();
                final Throwable failure = send.getCompletion().handle((value, cause) -> cause).get(15, SECONDS);
                assertThat(failure).isInstanceOf(BatchTransportException.class).hasMessageContaining("claim");
                assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CAPACITY);
                assertThat(state.invocations).hasValue(2);
                assertThat(outcomes).hasSize(1);
                final var receipt = outcomes.get(0).getSubmissionReceipt().orElseThrow();
                assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
                assertThat(((MailException) failure).getSubmissionReceipt()).containsSame(receipt);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory()).hasSize(1);
            } finally {
                releaseConnection.countDown();
                assertThat(competingSend.getCompletion().get(10, SECONDS).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
            }
            assertThat(state.invocations).hasValue(2);
            assertThat(outcomes).hasSize(2);
        }
    }

    @Test
    void failedBridgeRestartKeepsTheRefusalOnTheProxyFailure() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final CountDownLatch retryWaiting = new CountDownLatch(1);
        final InetAddress loopback = InetAddress.getByName("127.0.0.1");
        final int bridgePort;
        try (ServerSocket available = new ServerSocket(0, 1, loopback)) { bridgePort = available.getLocalPort(); }
        final Session session = RetryProviderTransport.session(state);
        session.getProperties().setProperty("mail.transport.protocol", "smtp");
        session.getProperties().setProperty("mail.smtp.host", "localhost");
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", RetryProviderTransport.class.getName(), "fixture", "1"));
        // The independent provider does not dial the remote proxy; this case exercises actual local bridge startup and binding.
        try (Mailer mailer = mail.mailerBuilder(session)
                .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(1080)
                        .withUsername("fixture-user").withPassword("fixture-password").withBridgePort(bridgePort).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(2)
                        .withRetryDelay(Duration.ofSeconds(3)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add)
                        .withProgressObserver(progress -> {
                            if (progress.getStage() == MailSendProgressStage.WAITING_TO_RETRY) { retryWaiting.countDown(); }
                        }).build()).buildMailer()) {
            final var send = mailer.async().sendMail(email());
            assertThat(retryWaiting.await(10, SECONDS))
                    .as("first send reached backoff; terminal failure: %s", send.getCompletion().handle((value, failure) -> failure).getNow(null)).isTrue();
            assertThat(state.invocations).hasValue(1);
            try (ServerSocket blocker = new ServerSocket()) {
                blocker.bind(new InetSocketAddress(loopback, bridgePort));
                final Throwable failure = send.getCompletion().handle((value, cause) -> cause).get(10, SECONDS);
                assertThat(failure).isInstanceOf(SocksException.class).hasCauseInstanceOf(BindException.class);
                assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONNECTION);
                assertThat(state.invocations).hasValue(1);
                assertThat(outcomes).hasSize(1);
                final var receipt = outcomes.get(0).getSubmissionReceipt().orElseThrow();
                assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
                assertThat(((MailException) failure).getSubmissionReceipt()).containsSame(receipt);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory()).hasSize(1);
            }
        }
    }
    @Test
    void unsupportedReplayPartialAndUncertainResultsNeverInvokeAgain() throws Exception {
        for (String scenario : new String[] { "unsupported replay", "partial acceptance", "uncertain acceptance", "permanent refusal" }) {
            final RetryProviderTransport.State state = new RetryProviderTransport.State();
            state.repeatable = !scenario.equals("unsupported replay");
            state.partial = scenario.equals("partial acceptance");
            state.uncertain = scenario.equals("uncertain acceptance");
            state.permanent = scenario.equals("permanent refusal");
            try (Mailer mailer = mailer(state, new CopyOnWriteArrayList<>())) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).as(scenario).isInstanceOf(MailException.class);
                assertThat(state.invocations).as(scenario).hasValue(state.repeatable ? 1 : 0);
            }
        }
    }

    @Test
    void waitingCapacityFailuresPreserveTruthAndReleaseTheirPermits() throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.temporaryRefusals = Integer.MAX_VALUE;
        final Session session = RetryProviderTransport.session(state);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder(session).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).withExpireAfter(Duration.ofMillis(60000)).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(1).build())
                .withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofHours(1))
                        .withWaitingCapacity(1).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            final var first = mailer.async().sendMail(email());
            // Wait for the first execution to return its worker, not merely for the provider to record a refusal.
            mailer.getOperationalConfig().getExecutorService().submit(() -> { }).get(10, SECONDS);
            final var second = mailer.async().sendMail(email());
            final Throwable failure = second.getCompletion().handle((value, cause) -> cause).get(10, SECONDS);
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            assertThat(failure.getSuppressed()).anySatisfy(reason -> assertThat(reason).hasMessageContaining("waiting capacity"));
            assertThat(state.invocations).hasValue(2);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory().get(0).getRetryDelay()).isNull();
            first.requestCancellation();
            first.getCompletion().handle((value, cause) -> cause).get(5, SECONDS);
            final var third = mailer.async().sendMail(email());
            mailer.getOperationalConfig().getExecutorService().submit(() -> { }).get(10, SECONDS);
            assertThat(third.getCompletion()).isNotDone();
            third.requestCancellation();
            third.getCompletion().handle((value, cause) -> cause).get(5, SECONDS);
            assertThat(state.invocations).hasValue(3);
        }
    }
    @Test
    void acceptedOrRefusedSubmissionFollowedByCleanupFailureNeverReplays() throws Exception {
        if (ModuleLoader.batchModuleAvailable()) {
            // Healthy pool return is not socket closure. Exercise real close failures in a fresh JVM without the optional batch module.
            verifyDirectCleanupInFreshJvm();
            return;
        }
        for (boolean accepted : new boolean[] { true, false }) {
            final RetryProviderTransport.State state = new RetryProviderTransport.State();
            state.temporaryRefusals = accepted ? 0 : 10;
            state.failCleanup = true;
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            try (Mailer mailer = mailer(state, outcomes)) {
                final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(failure).isNotNull();
                assertThat(state.invocations).hasValue(1);
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getFailure()).containsSame(failure);
                assertThat(outcomes.get(0).getSubmissionReceipt().orElseThrow().getStatus())
                        .isEqualTo(accepted ? MailSubmissionStatus.ACCEPTED : MailSubmissionStatus.REJECTED);
                assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getSubmissionHistory().get(0).getRetryDelay()).isNull();
                if (!accepted) { assertThat(failure).isInstanceOf(MailSubmissionException.class); }
            }
        }
    }

    private void verifyDirectCleanupInFreshJvm() throws Exception {
        final String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                .split(Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.replace('\\', '/').contains("/batch-module/") && !path.contains("batch-module-"))
                .collect(joining(File.pathSeparator));
        final Path output = Path.of("target", "756-direct-retry-provider.log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, getClass().getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertThat(child.waitFor(25, SECONDS)).isTrue();
        assertThat(child.exitValue()).as(Files.readString(output)).isZero();
    }

    public static void main(final String[] args) throws Exception {
        final RetryProviderBoundaryContractTest contracts = new RetryProviderBoundaryContractTest();
        contracts.independentProviderFreezesOnceAndRetriesUsingStructuredEvidence();
        contracts.acceptedOrRefusedSubmissionFollowedByCleanupFailureNeverReplays();
    }
}
