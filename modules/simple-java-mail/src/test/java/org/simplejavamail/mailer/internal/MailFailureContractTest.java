package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.retry.RetryProviderTransport;

import java.net.ConnectException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Exercises application catches, retained facts and extension controls rather than exception getter construction alone. */
@Timeout(20)
class MailFailureContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sizeRejectionKeepsItsSpecificCatchAndCommonContract(final boolean async) throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(1).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = fail(mailer, email(), async);
            assertThat(failure).isInstanceOf(EmailTooBigException.class).isInstanceOf(MailException.class);
            assertFailure(failure, "MESSAGE_SIZE", observed, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validationFailureKeepsItsSubtypeAndObserverIdentity(final boolean async) throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = fail(mailer, mail.emailBuilder().startingBlank().from("sender@example.org").buildEmail(), async);
            assertThat(failure).isInstanceOf(org.simplejavamail.mailer.MailCompletenessException.class);
            assertFailure(failure, "VALIDATION", observed, false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"connection", "submission", "cleanup"})
    void syncAndAsyncFailuresPreserveCauseAndSubmissionFacts(final String stage) throws Exception {
        for (final boolean async : new boolean[]{false, true}) {
            final State state = new State();
            final MessagingException original = new MessagingException(stage, new ConnectException("synthetic"));
            if (stage.equals("connection")) { state.connect = original; }
            if (stage.equals("submission")) { state.send = original; }
            if (stage.equals("cleanup")) { state.close = original; }
            final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
            try (MockedStatic<ModuleLoader> ignored = withoutPool();
                    Mailer mailer = mail.mailerBuilder(session(state))
                            .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                            .withSendHandlers(handlers(observed)).buildMailer()) {
                final Throwable failure = fail(mailer, email(), async);
                assertThat(failure).isInstanceOf(MailException.class).hasCause(original);
                assertFailure(failure, stage.toUpperCase(java.util.Locale.ROOT), observed, !stage.equals("connection"));
                if (stage.equals("cleanup")) {
                    assertThat(receipt(failure).orElseThrow().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                }
            }
        }
    }

    @Test
    void externalExecutorRejectionHasTheCommonRootAndRetainsTheOriginalCause() throws Exception {
        final RejectedExecutionException original = new RejectedExecutionException("external capacity exhausted");
        final ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { throw original; }).when(executor).execute(any());
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(executor).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = fail(mailer, email(), true);
            assertThat(failure).isInstanceOf(MailSendRejectedException.class).isInstanceOf(MailException.class).hasCause(original);
            assertFailure(failure, "CAPACITY", observed, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closedMailerRejectionIsACapacityFailureEvenWithABorrowedExecutor(final boolean async) throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        final Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                .withSendHandlers(handlers(observed)).buildMailer();
        mailer.close();
        final Throwable failure = fail(mailer, email(), async);
        assertThat(failure).isInstanceOf(MailSendRejectedException.class).isInstanceOf(MailException.class);
        assertFailure(failure, "CAPACITY", observed, false);
    }

    @Test
    void cancellationBeforeExecutionSharesTheSameFailureWithObserverAndCompletion() throws Exception {
        final ExecutorService executor = mock(ExecutorService.class);
        final List<Runnable> queued = new CopyOnWriteArrayList<>();
        doAnswer(call -> { queued.add(call.getArgument(0)); return null; }).when(executor).execute(any());
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(executor).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
            send.requestCancellation();
            final Throwable failure = completionFailure(send);
            assertThat(failure).isInstanceOf(MailSendCancelledException.class);
            assertFailure(failure, "CANCELLED", observed, false);
            queued.forEach(Runnable::run);
            assertThat(observed).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "unknown"})
    void partialAndUnknownFailuresExposeTheirGenuineReceiptThroughTheRoot(final String scenario) throws Exception {
        final RetryProviderTransport.State state = new RetryProviderTransport.State();
        state.partial = scenario.equals("partial"); state.uncertain = scenario.equals("unknown");
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (MockedStatic<ModuleLoader> ignored = withoutPool();
                Mailer mailer = mail.mailerBuilder(RetryProviderTransport.session(state))
                        .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = fail(mailer, email(), false);
            assertFailure(failure, "SUBMISSION", observed, true);
            assertThat(receipt(failure).orElseThrow().getStatus())
                    .isEqualTo(state.partial ? MailSubmissionStatus.PARTIALLY_ACCEPTED : MailSubmissionStatus.UNKNOWN);
        }
    }

    @Test
    void anUnstartedDeadlineIsACommonFailureWithoutInventedSubmissionFacts() throws Exception {
        final ExecutorService executor = mock(ExecutorService.class);
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withMailSendTimeout(java.time.Duration.ofMillis(50))
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(executor).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = completionFailure(mailer.async().sendMail(email()));
            assertThat(failure).isInstanceOf(MailSendTimeoutException.class);
            assertFailure(failure, "TIMEOUT", observed, false);
            assertThat(observed.get(0).getStartedAt()).isNull();
        }
    }

    @Test
    void unsupportedDeadlineConfigurationUsesTheCommonRootBeforeConnecting() throws Exception {
        final State state = new State();
        try (MockedStatic<ModuleLoader> ignored = withoutPool();
                Mailer mailer = mail.mailerBuilder(session(state)).withMailSendTimeout(java.time.Duration.ofSeconds(1)).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class).hasMessageContaining("can't stop its network calls");
            assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONFIGURATION);
            assertThat(((MailException) failure).getSubmissionReceipt()).isEmpty();
            assertThat(state.connections).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableAttachmentContentIsAnOwnedPreparationFailure(final boolean async) throws Exception {
        final java.io.IOException original = new java.io.IOException("attachment unavailable");
        final jakarta.mail.util.ByteArrayDataSource source = new jakarta.mail.util.ByteArrayDataSource(new byte[]{1}, "application/octet-stream") {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException { throw original; }
        };
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (MockedStatic<ModuleLoader> ignored = withoutPool();
                Mailer mailer = mail.mailerBuilder(session(new State()))
                        .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                        .withSendHandlers(handlers(observed)).buildMailer()) {
            final Throwable failure = fail(mailer, mail.emailBuilder().copying(email()).withAttachment("unavailable.bin", source).buildEmail(), async);
            assertFailure(failure, "PREPARATION", observed, false);
            assertThat(failure).hasRootCause(original);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customMailerRuntimeExceptionsAreNotReclassifiedAsLibraryFailures(final boolean async) throws Exception {
        final RuntimeException original = new IllegalStateException("application integration defect");
        final CustomMailer custom = new CustomMailer() {
            @Override public void testConnection(final OperationalConfig config, final Session session) { }
            @Override public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                throw original;
            }
        };
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withProperty("mail.from", "sender@example.org").withCustomMailer(custom)
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(inline()).build())
                .withSendHandlers(handlers(observed)).buildMailer()) {
            assertThat(fail(mailer, email(), async)).isSameAs(original);
            assertThat(observed).singleElement().satisfies(outcome -> assertThat(outcome.getFailure()).containsSame(original));
        }
    }

    @ParameterizedTest
    @CsvSource({"ACCEPTED,true,true", "PARTIALLY_ACCEPTED,true,false", "REJECTED,false,false", "UNKNOWN,false,false"})
    void acceptancePredicatesExpressDifferentSubmissionFacts(final MailSubmissionStatus status, final boolean anyAccepted,
            final boolean fullyAccepted) throws Exception {
        final MailSubmissionReceipt receipt = MailSubmissionReceiptImpl.builder().status(status).submittedAt(Instant.EPOCH)
                .recipientResults(List.of()).retryDisposition(MailRetryDisposition.CALLER_POLICY_REQUIRED).build();
        assertThat(receipt.isAcceptedForAtLeastOneRecipient()).isEqualTo(anyAccepted);
        assertThat(receipt.isFullyAcceptedForSubmission()).isEqualTo(fullyAccepted);
        assertThat(status.isFullyAcceptedForSubmission()).isEqualTo(fullyAccepted);
    }

    private static void assertFailure(final Throwable failure, final String kind, final List<MailSendOutcome> observed,
            final boolean hasReceipt) throws Exception {
        assertThat(failure).isInstanceOf(MailException.class);
        assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.valueOf(kind));
        assertThat(observed).singleElement().satisfies(outcome -> assertThat(outcome.getFailure()).containsSame(failure));
        assertThat(receipt(failure).isPresent()).isEqualTo(hasReceipt);
        assertThat(receipt(failure)).isEqualTo(observed.get(0).getSubmissionReceipt());
    }

    private static Optional<MailSubmissionReceipt> receipt(final Throwable failure) throws Exception {
        return ((MailException) failure).getSubmissionReceipt();
    }

    private static Throwable fail(final Mailer mailer, final Email email, final boolean async) throws Exception {
        return async ? completionFailure(mailer.async().sendMail(email)) : catchThrowable(() -> mailer.sync().sendMail(email));
    }

    private static Throwable completionFailure(final MailSend<?> send) throws Exception {
        try { send.getCompletion().get(5, TimeUnit.SECONDS); throw new AssertionError("Expected failed completion"); }
        catch (ExecutionException failure) { return failure.getCause(); }
    }

    private static MailSendHandlers handlers(final List<MailSendOutcome> observed) {
        return MailSendHandlers.builder().withResultObserver(observed::add).build();
    }

    private static ExecutorService inline() {
        final ExecutorService executor = mock(ExecutorService.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        return executor;
    }

    private static MockedStatic<ModuleLoader> withoutPool() {
        final MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false); return modules;
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("first@example.org").to("second@example.org")
                .fixingMessageId("failure-contract@example.org").fixingSentDate(Instant.EPOCH).withPlainText("body").buildEmail();
    }

    private static Session session(final State state) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp"); properties.setProperty("mail.smtp.host", "fixture");
        properties.setProperty("mail.smtp.port", "25"); properties.setProperty("mail.from", "sender@example.org");
        properties.put(State.class.getName(), state);
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", FailureTransport.class.getName(), "fixture", "1"));
        return session;
    }

    private static final class State { private MessagingException connect, send, close; private int connections; }

    public static final class FailureTransport extends Transport {
        private final State state;
        public FailureTransport(final Session session, final URLName name) { super(session, name); state = (State) session.getProperties().get(State.class.getName()); }
        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password) throws MessagingException {
            state.connections++;
            if (state.connect != null) { throw state.connect; } return true;
        }
        @Override public void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            if (state.send != null) { throw state.send; }
        }
        @Override public void close() throws MessagingException { setConnected(false); if (state.close != null) { throw state.close; } }
    }
}
