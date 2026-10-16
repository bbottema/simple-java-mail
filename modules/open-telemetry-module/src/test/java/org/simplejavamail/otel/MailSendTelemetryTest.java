package org.simplejavamail.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.mail.Session;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.MimeMessage;
import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.net.ServerSocket;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@Timeout(25)
class MailSendTelemetryTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    private MailerRegularBuilder<?> builder(final int port, final TelemetryTestSdk telemetry) {
        return mail.mailerBuilder()
                .withSMTPServer("localhost", port)
                .withTransportStrategy(TransportStrategy.SMTP)
                .withOpportunisticTLS(false)
                .withSmtpClientHostname("telemetry.example.test")
                .withProperty("mail.host", "localhost")
                .withConnectionPoolCoreSize(0)
                .withConnectionPoolMaxSize(2)
                .withSessionTimeout(5000)
                .withMailSendTelemetry(OtelAdapter.wrap(telemetry.sdk));
    }

    private Email email() {
        return mail.emailBuilder().startingBlank()
                .from("private-sender@example.test")
                .withRecipients(mail.recipientBuilder()
                        .withAddress("private-recipient@example.test")
                        .withType(Message.RecipientType.TO)
                        .build())
                .withSubject("private subject")
                .withPlainText("private body")
                .buildEmail();
    }

    @Test
    void syncAndAsyncCarryTheInitiatingContextAndFinishBeforeTheResultObserver() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
            final Span parent = telemetry.sdk.getTracer("application").spanBuilder("request").startSpan();
            try (Mailer mailer = builder(server.port(), telemetry).withMailSendResultObserver(outcome -> {
                assertThat(telemetry.sends()).hasSize(outcomes.size() + 1);
                assertThat(Span.current().getSpanContext().getSpanId()).isNotEqualTo(telemetry.sends().get(outcomes.size()).getSpanId());
                outcomes.add(outcome);
            }).buildMailer()) {
                try (Scope ignored = parent.makeCurrent()) {
                    final MailSubmissionReceipt first = mailer.sync().sendMail(email());
                    assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(first);
                    mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
                    assertThat(Span.current()).isSameAs(parent);
                }
            } finally {
                parent.end();
            }

            assertThat(telemetry.sends()).hasSize(2).allSatisfy(span -> {
                assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
                assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            });
            assertThat(telemetry.submissions()).hasSize(2).allSatisfy(span -> {
                assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
                assertThat(span.getAttributes().get(AttributeKey.stringKey("simplejavamail.submission.status"))).isEqualTo("accepted");
                assertThat(span.getAttributes().get(AttributeKey.stringKey("server.address"))).isNull();
            });
            assertThat(telemetry.sends().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.observation.id")))
                    .isEqualTo(outcomes.get(0).getObservationId().toString());
            assertThat(telemetry.sends().get(1).getAttributes().get(AttributeKey.stringKey("simplejavamail.observation.id")))
                    .isNotEqualTo(telemetry.sends().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.observation.id")));
            assertBalanced(telemetry, 2, 2);
            assertPrivate(telemetry);
        }
    }

    @Test
    void retryResumptionKeepsOneParentAndCountsEveryInvocationWithoutPublicHistories() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(attempt -> attempt == 1 ? "451 retry later" : "250 queued", false);
             TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).withMailSendRetryPolicy(MailSendRetryPolicy.builder()
                     .withMaximumAttempts(3)
                     .withRetryDelay(Duration.ofMillis(20))
                     .build()).buildMailer()) {
            mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
            assertThat(telemetry.sends()).hasSize(1);
            assertThat(telemetry.sends().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(telemetry.submissions()).hasSize(2);
            assertThat(telemetry.submissions().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(telemetry.submissions().get(1).getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(telemetry.submissions()).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(telemetry.sends().get(0).getSpanId()));
            assertThat(server.messages).hasSize(2);
            assertThat(server.messages.get(0)).isEqualTo(server.messages.get(1));
            assertBalanced(telemetry, 1, 2);
            assertThat(telemetry.metric("simplejavamail.mail.send.phase.duration").getHistogramData().getPoints())
                    .anySatisfy(point -> assertThat(point.getAttributes().get(AttributeKey.stringKey("simplejavamail.send.phase"))).isEqualTo("retry_wait"));
        }
    }

    @Test
    void failoverUsesTheInitiatingSdkAndOnlyTheActuallySelectedEndpoint() throws Exception {
        final int unavailablePort;
        try (ServerSocket closed = new ServerSocket(0)) {
            unavailablePort = closed.getLocalPort();
        }
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             TelemetryTestSdk unusedDestinationTelemetry = new TelemetryTestSdk();
             Mailer destination = builder(server.port(), unusedDestinationTelemetry).buildMailer();
             Mailer initiator = builder(unavailablePort, telemetry).withMailSendTelemetry(OtelAdapter.wrap(telemetry.sdk))
                     .withMailSendTelemetrySmtpEndpointAttributes(true)
                     .withFailoverMailer(destination, FailoverPolicy.builder().build()).buildMailer()) {
            initiator.sync().sendMail(email());
            assertBalanced(telemetry, 1, 1);
            assertThat(unusedDestinationTelemetry.spans.getFinishedSpanItems()).isEmpty();
            assertThat(telemetry.submissions().get(0).getAttributes().get(AttributeKey.stringKey("server.address"))).isEqualTo("localhost");
            // Angus's URLName can retain -1 even when its Session supplied a port. Do not guess or copy the initiating Mailer's port.
            assertThat(telemetry.submissions().get(0).getAttributes().get(AttributeKey.longKey("server.port"))).isNull();
            assertPrivate(telemetry);
        }
    }

    @Test
    void lostFinalReplyIsAnUnsuccessfulChildWithUnknownAcceptanceAndNoReplay() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(ignored -> null, false); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(MailSubmissionException.class);
            assertThat(server.submissions).hasValue(1);
            assertThat(telemetry.sends().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(telemetry.submissions().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(telemetry.submissions().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.submission.status"))).isEqualTo("unknown");
            assertBalanced(telemetry, 1, 1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedSubmissionAndFailedDirectCleanupStayDistinctInRealSendTelemetry(final boolean asynchronous) throws Exception {
        final AtomicInteger submissions = new AtomicInteger();
        final MessagingException cleanupFailure = new MessagingException("private cleanup detail");
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.port", "2525");
        properties.put(AtomicInteger.class.getName(), submissions);
        properties.put(MessagingException.class.getName(), cleanupFailure);
        final Session session = Session.getInstance(properties);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", CloseFailingTransport.class.getName(), "test", "1");
        session.addProvider(provider);
        session.setProvider(provider);
        final AtomicReference<MailSendOutcome> outcome = new AtomicReference<>();
        // Keep the direct-provider fault injection on this thread while exercising both public completion contracts.
        final ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk();
             MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer mailer = mail.mailerBuilder(session).withExecutorService(executor).withMailSendTelemetry(OtelAdapter.wrap(telemetry.sdk))
                    .withMailSendResultObserver(outcome::set).buildMailer()) {
                final Throwable failure = asynchronous
                        ? mailer.async().sendMail(email()).getCompletion().handle((receipt, cause) -> cause).get(5, SECONDS)
                        : catchThrowable(() -> mailer.sync().sendMail(email()));
                assertThat(failure).isNotNull().hasCause(cleanupFailure);
                assertThat(outcome.get().getFailure()).containsSame(failure);
                assertThat(outcome.get().getSubmissionReceipt().orElseThrow().getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
                assertThat(outcome.get().getDiagnostics().orElseThrow().getCleanup().isFailureObservedHere()).isTrue();
            }
            assertThat(submissions).hasValue(1);
            assertThat(telemetry.submissions()).singleElement().satisfies(span -> {
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
                assertThat(span.getAttributes().get(AttributeKey.stringKey("simplejavamail.submission.status"))).isEqualTo("accepted");
            });
            assertThat(telemetry.sends()).singleElement().satisfies(span -> {
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(span.getAttributes().get(AttributeKey.stringKey("simplejavamail.submission.status"))).isEqualTo("accepted");
                assertThat(span.getAttributes().get(AttributeKey.stringKey("simplejavamail.send.failure.phase"))).isEqualTo("cleanup");
            });
            assertBalanced(telemetry, 1, 1);
            assertPrivate(telemetry);
        }
    }

    /** Jakarta Mail instantiates this fixture through its ordinary provider boundary, without a real network connection. */
    public static final class CloseFailingTransport extends Transport {
        public CloseFailingTransport(final Session session, final URLName name) {
            super(session, name);
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            return true;
        }

        @Override
        public void sendMessage(final Message message, final Address[] addresses) {
            ((AtomicInteger) session.getProperties().get(AtomicInteger.class.getName())).incrementAndGet();
        }

        @Override
        public void close() throws MessagingException {
            setConnected(false);
            throw (MessagingException) session.getProperties().get(MessagingException.class.getName());
        }
    }

    @Test
    void partialAcceptanceRetainsTheExactReceiptAndRemainsAFailedSend() throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(ignored -> "250 queued", true); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).withMailSendResultObserver(outcomes::add).buildMailer()) {
            final Email mixed = mail.emailBuilder().copying(email())
                    .withRecipients(mail.recipientBuilder()
                            .withAddress("rejected@example.test")
                            .withType(Message.RecipientType.TO)
                            .build())
                    .withSendingToAcceptedRecipients(true)
                    .buildEmail();
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(mixed));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.PARTIALLY_ACCEPTED);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            assertThat(telemetry.submissions().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertBalanced(telemetry, 1, 1);
        }
    }

    @Test
    void preparationAndSchedulingFailuresDoNotManufactureSubmissionChildren() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk(); Mailer mailer = builder(0, telemetry).withExecutorService(executor).buildMailer()) {
            final Email incomplete = mail.emailBuilder().startingBlank().buildEmail();
            assertThat(catchThrowable(() -> mailer.sync().sendMail(incomplete))).isNotNull();
            executor.shutdown();
            assertThatThrownBy(() -> mailer.async().sendMail(email()).getCompletion().get(5, SECONDS)).hasCauseInstanceOf(RuntimeException.class);
            assertThat(telemetry.sends()).hasSize(2).allSatisfy(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
            assertBalanced(telemetry, 2, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationOfASuspendedRetryClosesTheParentWithoutAnotherInvocation() throws Exception {
        final CountDownLatch waiting = new CountDownLatch(1);
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(ignored -> "451 retry later", false); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).withMailSendRetryPolicy(MailSendRetryPolicy.builder()
                     .withMaximumAttempts(3)
                     .withRetryDelay(Duration.ofMinutes(1))
                     .build())
                     .withMailSendProgressObserver(progress -> {
                         if (progress.getStage() == MailSendProgressStage.WAITING_TO_RETRY) {
                             waiting.countDown();
                         }
                     }).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
            assertThat(waiting.await(5, SECONDS)).isTrue();
            send.requestCancellation();
            assertThatThrownBy(() -> send.getCompletion().get(5, SECONDS)).hasCauseInstanceOf(MailSendCancelledException.class);
            assertThat(server.submissions).hasValue(1);
            assertThat(telemetry.sends().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.send.outcome"))).isEqualTo("cancelled");
            assertBalanced(telemetry, 1, 1);
        }
    }

    @Test
    void queuedCancellationBalancesAnUnstartedObservationAndLeavesNoContextAttached() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk(); Mailer mailer = builder(0, telemetry).withThreadPoolSize(1)
                .withAsyncQueueCapacity(1).withCustomMailer(callback((email, message) -> {
                    entered.countDown();
                    try {
                        assertThat(release.await(5, SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                })).buildMailer()) {
            final MailSend<MailSubmissionReceipt> running = mailer.async().sendMail(email());
            try {
                assertThat(entered.await(5, SECONDS)).isTrue();
                final MailSend<MailSubmissionReceipt> queued = mailer.async().sendMail(email());
                queued.requestCancellation();
                assertThatThrownBy(() -> queued.getCompletion().get(5, SECONDS))
                        .hasCauseInstanceOf(MailSendCancelledException.class);
            } finally {
                release.countDown();
            }
            running.getCompletion().get(5, SECONDS);
            assertBalanced(telemetry, 2, 1);
            assertThat(telemetry.sends()).anySatisfy(span -> assertThat(span.getAttributes()
                    .get(AttributeKey.stringKey("simplejavamail.send.outcome"))).isEqualTo("cancelled"));
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void deadlineDuringFinalReplyEndsTheInvocationAndLogicalSpanOnce() throws Exception {
        final CountDownLatch waitingForReply = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(ignored -> {
            waitingForReply.countDown();
            try {
                release.await(5, SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "250 queued";
        }, false); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).withMailSendTimeout(Duration.ofSeconds(2)).buildMailer()) {
            server.expectClientAbort();
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email());
            try {
                assertThat(waitingForReply.await(5, SECONDS)).isTrue();
                assertThatThrownBy(() -> send.getCompletion().get(5, SECONDS))
                        .hasCauseInstanceOf(MailSendTimeoutException.class);
                assertBalanced(telemetry, 1, 1);
                assertThat(telemetry.sends().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.send.outcome"))).isEqualTo("timed_out");
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentCustomCallbacksHaveIsolatedContextAndDoNotExposeSmtpDetails() throws Exception {
        final AtomicReference<String> firstParent = new AtomicReference<>();
        final AtomicReference<String> secondParent = new AtomicReference<>();
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk(); Mailer mailer = builder(0, telemetry)
                .withCustomMailer(callback((email, message) -> {
                    final String current = Span.current().getSpanContext().getSpanId();
                    (email.getSubject().equals("one") ? firstParent : secondParent).set(current);
                })).buildMailer()) {
            final Span first = telemetry.sdk.getTracer("application").spanBuilder("first request").startSpan();
            final Span second = telemetry.sdk.getTracer("application").spanBuilder("second request").startSpan();
            final MailSend<MailSubmissionReceipt> one;
            final MailSend<MailSubmissionReceipt> two;
            try (Scope ignored = first.makeCurrent()) {
                one = mailer.async().sendMail(mail.emailBuilder().copying(email()).withSubject("one").buildEmail());
            }
            try (Scope ignored = second.makeCurrent()) {
                two = mailer.async().sendMail(mail.emailBuilder().copying(email()).withSubject("two").buildEmail());
            }
            one.getCompletion().get(5, SECONDS);
            two.getCompletion().get(5, SECONDS);
            first.end();
            second.end();
            assertThat(firstParent.get()).isNotEqualTo(secondParent.get());
            assertThat(telemetry.submissions()).hasSize(2).allSatisfy(span -> {
                assertThat(span.getName()).isEqualTo("mail.custom_submit");
                assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
                assertThat(span.getAttributes().get(AttributeKey.stringKey("server.address"))).isNull();
            });
            assertThat(telemetry.sends()).extracting(SpanData::getParentSpanId)
                    .containsExactlyInAnyOrder(first.getSpanContext().getSpanId(), second.getSpanContext().getSpanId());
            assertBalanced(telemetry, 2, 2);
        }
    }

    @Test
    void batchEmailsAreSiblingsAndUntouchedEmailsRemainUnobserved() throws Exception {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(0, telemetry).withCustomMailer(callback((email, message) -> { })).buildMailer()) {
            final Span request = telemetry.sdk.getTracer("application").spanBuilder("batch request").startSpan();
            try (Scope ignored = request.makeCurrent()) {
                mailer.async().sendMailsInSimpleBatch(Arrays.asList(email(), email())).getCompletion().get(5, SECONDS);
            }
            request.end();
            assertThat(telemetry.sends()).hasSize(2).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(request.getSpanContext().getSpanId()));
            assertBalanced(telemetry, 2, 2);
            assertThat(telemetry.metric("simplejavamail.mail.send.phase.duration").getHistogramData().getPoints()).noneSatisfy(point ->
                    assertThat(point.getAttributes().get(AttributeKey.stringKey("simplejavamail.send.phase"))).isEqualTo("scheduling"));
        }
        final AtomicInteger calls = new AtomicInteger();
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk(); Mailer mailer = builder(0, telemetry).withCustomMailer(callback((email, message) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private error text");
        })).buildMailer()) {
            assertThat(catchThrowable(() -> mailer.sync().sendMailsInSimpleBatch(Arrays.asList(email(), email())))).isNotNull();
            assertThat(calls).hasValue(1);
            assertBalanced(telemetry, 1, 1);
            assertPrivate(telemetry);
        }
    }

    @Test
    void samplingDoesNotDisableMetricsAndLoggingHasNoFictionalProviderChild() throws Exception {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk(Sampler.alwaysOff());
             Mailer mailer = builder(0, telemetry).withTransportModeLoggingOnly(true).buildMailer()) {
            mailer.sync().sendMail(email());
            assertThat(telemetry.spans.getFinishedSpanItems()).isEmpty();
            assertBalanced(telemetry, 1, 0);
            assertThat(telemetry.metric("simplejavamail.mail.send.completed").getLongSumData().getPoints()).singleElement()
                    .satisfies(point -> assertThat(point.getAttributes().get(AttributeKey.stringKey("simplejavamail.send.outcome"))).isEqualTo("logged_only"));
        }
    }

    @Test
    void rejectedResultDispatchCannotLoseTelemetryOrOwnSdkShutdown() throws Exception {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            try (Mailer mailer = builder(0, telemetry).withCustomMailer(callback((email, message) -> { }))
                    .withMailSendResultObserver(outcome -> {
                        throw new AssertionError("must not run");
                    }, task -> {
                        throw new RejectedExecutionException("private executor detail");
                    }).buildMailer()) {
                mailer.sync().sendMail(email());
            }
            final Span stillUsable = telemetry.sdk.getTracer("application").spanBuilder("after Mailer.close").startSpan();
            stillUsable.end();
            assertThat(telemetry.namedSpans("after Mailer.close")).hasSize(1);
            assertBalanced(telemetry, 1, 1);
        }
    }

    @Test
    void pinnedOpenConnectionEmailsKeepTheirOwnSpansWithoutMeasuringSharedSetup() throws Exception {
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer mailer = builder(server.port(), telemetry).buildMailer()) {
            final Span parent = telemetry.sdk.getTracer("application").spanBuilder("open scope").startSpan();
            try (Scope ignored = parent.makeCurrent()) {
                mailer.withOpenConnection(sender -> {
                    sender.sendMail(email());
                    sender.sendMail(email());
                    assertThat(Span.current()).isSameAs(parent);
                });
            } finally {
                parent.end();
            }
            assertThat(telemetry.sends()).hasSize(2).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId()));
            assertThat(server.connections).hasValue(1);
            assertBalanced(telemetry, 2, 2);
            assertThat(telemetry.metric("simplejavamail.mail.send.phase.duration").getHistogramData().getPoints()).noneSatisfy(point ->
                    assertThat(point.getAttributes().get(AttributeKey.stringKey("simplejavamail.send.phase"))).isEqualTo("connection_acquisition"));
        }
    }

    @Test
    void telemetryAddsNoAttachmentReadsOrSmtpConnections() throws Exception {
        final AtomicInteger unobservedReads = new AtomicInteger();
        final AtomicInteger observedReads = new AtomicInteger();
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer unobserved = builder(server.port(), telemetry).clearMailSendTelemetry().buildMailer();
             Mailer observed = builder(server.port(), telemetry).buildMailer()) {
            unobserved.sync().sendMail(attachmentEmail(unobservedReads));
            observed.sync().sendMail(attachmentEmail(observedReads));
            assertThat(observedReads.get()).isPositive().isEqualTo(unobservedReads.get());
            assertThat(server.connections).hasValue(2);
            assertThat(server.submissions).hasValue(2);
            assertThat(server.commandsPerConnection).hasSize(2);
            assertThat(server.commandsPerConnection.get(1)).containsExactlyElementsOf(server.commandsPerConnection.get(0));
            assertBalanced(telemetry, 1, 1);
        }
    }

    @Test
    void telemetryLeavesAuthoritativeExactContentAndCommandsUnchanged() throws Exception {
        final String exactContent = "From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Message-ID: <exact-telemetry@example.test>\r\nX-Spacing:   keep this\r\n"
                + "DKIM-Signature: v=1; a=rsa-sha256; d=example.test; b=opaque\r\n\r\nPreserved content.\r\n";
        final Email exact = mail.emailBuilder().startingFromExactEml(exactContent.getBytes(US_ASCII))
                .withEnvelopeRecipients("receiver@example.test").buildEmail();
        try (TelemetrySmtpServer server = new TelemetrySmtpServer(); TelemetryTestSdk telemetry = new TelemetryTestSdk();
             Mailer unobserved = builder(server.port(), telemetry).clearMailSendTelemetry().buildMailer();
             Mailer observed = builder(server.port(), telemetry).buildMailer()) {
            unobserved.sync().sendMail(exact);
            observed.sync().sendMail(exact);
            // The opaque signature is not being verified here: the contract is that none of its authoritative bytes are rewritten.
            assertThat(server.messages).containsExactly(exactContent, exactContent);
            assertThat(server.commandsPerConnection).hasSize(2);
            assertThat(server.commandsPerConnection.get(1)).containsExactlyElementsOf(server.commandsPerConnection.get(0));
            assertBalanced(telemetry, 1, 1);
            assertPrivate(telemetry);
        }
    }

    @Test
    void failingSdkInitializationOrSpanCreationNeverChangesTheSendResult() throws Exception {
        try (TelemetryTestSdk telemetry = new TelemetryTestSdk()) {
            final OpenTelemetry broken = mock(OpenTelemetry.class);
            when(broken.getTracer("org.simplejavamail.mail")).thenThrow(new IllegalStateException("private exporter details"));
            try (Mailer mailer = builder(0, telemetry).withCustomMailer(callback((email, message) -> { }))
                    .withMailSendTelemetry(OtelAdapter.wrap(broken)).buildMailer()) {
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
            }
            final Tracer tracer = mock(Tracer.class);
            doReturn(tracer).when(broken).getTracer("org.simplejavamail.mail");
            when(broken.getMeter("org.simplejavamail.mail")).thenReturn(telemetry.sdk.getMeter("org.simplejavamail.mail"));
            when(tracer.spanBuilder("mail.send")).thenThrow(new IllegalStateException("private exporter details"));
            final RuntimeException actualFailure = new IllegalStateException("application callback failed");
            try (Mailer mailer = builder(0, telemetry).withCustomMailer(callback((email, message) -> {
                throw actualFailure;
            }))
                    .withMailSendTelemetry(OtelAdapter.wrap(broken)).buildMailer()) {
                // CustomMailer failures already cross SJM's MailerException boundary; SDK failure must not replace that original cause.
                assertThat(catchThrowable(() -> mailer.sync().sendMail(email())))
                        .isInstanceOf(MailException.class).hasCause(actualFailure);
            }
        }
    }

    private Email attachmentEmail(final AtomicInteger reads) {
        final DataSource content = new DataSource() {
            @Override
            public InputStream getInputStream() {
                reads.incrementAndGet();
                return new ByteArrayInputStream(new byte[1024]);
            }
            @Override
            public OutputStream getOutputStream() {
                throw new UnsupportedOperationException();
            }
            @Override
            public String getContentType() {
                return "application/octet-stream";
            }
            @Override
            public String getName() {
                return "attachment.bin";
            }
        };
        return mail.emailBuilder().copying(email()).withAttachment("attachment.bin", content).buildEmail();
    }

    private static void assertBalanced(final TelemetryTestSdk telemetry, final int sends, final int submissions) {
        assertThat(telemetry.sum("simplejavamail.mail.send.completed")).isEqualTo(sends);
        assertThat(telemetry.sum("simplejavamail.mail.submission.completed")).isEqualTo(submissions);
        assertThat(telemetry.sum("simplejavamail.mail.send.active")).isZero();
    }

    private static void assertPrivate(final TelemetryTestSdk telemetry) {
        assertThat(telemetry.spans.getFinishedSpanItems()).allSatisfy(span -> {
            assertThat(span.getAttributes().toString()).doesNotContain("private", "@", "Subject", "password");
            assertThat(span.getEvents()).isEmpty();
            assertThat(span.getStatus().getDescription()).isEmpty();
        });
        assertThat(telemetry.metricAttributes()).allSatisfy(attributes -> assertThat(attributes.asMap().keySet())
                .allSatisfy(key -> assertThat(key.getKey())
                        .isIn("simplejavamail.send.outcome", "simplejavamail.submission.status", "simplejavamail.send.phase", "error.type")));
    }

    private interface SendCallback {
        void send(Email email, MimeMessage message);
    }

    private static CustomMailer callback(final SendCallback callback) {
        return new CustomMailer() {
            @Override
            public void testConnection(final OperationalConfig config, final Session session) { }
            @Override
            public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
                callback.send(email, message);
            }
        };
    }
}
