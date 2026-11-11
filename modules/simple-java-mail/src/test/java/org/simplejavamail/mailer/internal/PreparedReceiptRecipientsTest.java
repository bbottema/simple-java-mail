package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailRecipientDisposition;
import org.simplejavamail.api.mailer.MailRecipientResult;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.internal.util.FinalizedMimeMessage.ProtectionState.FINAL_WIRE_SIGNED;

@Timeout(20)
public class PreparedReceiptRecipientsTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String ALICE = "alice@example.org";
    private static final String BOB = "bob@example.org";
    private static final String GROUP = "Team: one@example.org, two@example.org;";
    private static final DkimConfig SIGNING_FIXTURE = DkimConfig.builder().dkimPrivateKeyData("unused-test-key")
            .dkimSigningDomain("example.org").dkimSelector("test").build();
    private static final AtomicInteger TRANSPORTS = new AtomicInteger();
    private static final AtomicInteger FAILED_CONNECTIONS = new AtomicInteger();

    @BeforeEach
    void resetTransportWitness() {
        TRANSPORTS.set(0);
        FAILED_CONNECTIONS.set(0);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("recipientProjections")
    void receiptUsesPreparedRecipientOccurrences(final Mode mode, final String scenario,
            final Email email, final List<String> recipients) throws Exception {
        final RecordingCustomMailer custom = new RecordingCustomMailer();
        final MailSubmissionReceipt receipt;
        try (Mailer mailer = builder(mode, custom).buildMailer()) {
            receipt = mailer.sync().sendMail(email);
        }
        assertRecipients(receipt, recipients);
        assertUnknownFacts(receipt);
        assertThat(custom.calls.get()).isEqualTo(mode == Mode.CUSTOM ? 1 : 0);
        assertThat(TRANSPORTS.get()).isZero();
    }

    private static Stream<Arguments> recipientProjections() {
        final List<Projection> projections = List.of(
                new Projection("generic To", message().withHeader("To", BOB).buildEmail(), List.of(ALICE, BOB)),
                new Projection("generic Cc", message().withHeader("Cc", BOB).buildEmail(), List.of(ALICE, BOB)),
                new Projection("generic Bcc", message().withHeader("Bcc", BOB).buildEmail(), List.of(ALICE, BOB)),
                new Projection("all generic categories", message().withHeader("Bcc", "hidden@example.org")
                        .withHeader("To", BOB).withHeader("Cc", "copy@example.org").buildEmail(),
                        List.of(ALICE, BOB, "copy@example.org", "hidden@example.org")),
                new Projection("interleaved typed categories", message().bcc("hidden@example.org")
                        .cc("copy@example.org").to("last@example.org").buildEmail(),
                        List.of(ALICE, "last@example.org", "copy@example.org", "hidden@example.org")),
                new Projection("duplicate occurrences", message().bcc(ALICE).cc(ALICE).to(ALICE)
                        .withHeader("Cc", ALICE).buildEmail(), List.of(ALICE, ALICE, ALICE, ALICE, ALICE)),
                new Projection("generic group expands", message().withHeader("Cc", GROUP).buildEmail(),
                        List.of(ALICE, "one@example.org", "two@example.org")),
                new Projection("convenience group uses its normal expansion", message().cc(GROUP).buildEmail(),
                        List.of(ALICE, "one@example.org", "two@example.org")),
                new Projection("receipt inspection does not bind provider DSN preferences", message()
                        .withRecipients(new Recipient(null, "notify@example.org", TO, null, Set.of(NotifyOption.FAILURE)))
                        .withHeader("Newsgroups", "example.discussion").buildEmail(), Arrays.asList(ALICE, "notify@example.org", null)),
                new Projection("explicit delivery override", message().withHeader("Cc", BOB)
                        .withOverrideReceivers(override("second@example.org"), override("first@example.org"), override("second@example.org"))
                        .buildEmail(), List.of("second@example.org", "first@example.org", "second@example.org")));
        return Stream.of(Mode.values()).flatMap(mode -> projections.stream()
                .map(projection -> Arguments.of(mode, projection.name, projection.email, projection.recipients)));
    }

    @Test
    void asynchronousLoggingOnlyObserverSeesTheSamePreparedReceipt() throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        final MailSubmissionReceipt receipt;
        try (Mailer mailer = builder(Mode.LOGGING_ONLY, new RecordingCustomMailer())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(observed::add).build()).buildMailer()) {
            receipt = mailer.async().sendMail(message().withHeader("Bcc", BOB).buildEmail())
                    .getCompletion().get(5, TimeUnit.SECONDS);
        }
        assertRecipients(receipt, List.of(ALICE, BOB));
        assertUnknownFacts(receipt);
        assertThat(observed).singleElement().satisfies(outcome -> {
            assertThat(outcome.isSuccessful()).isTrue();
            assertThat(outcome.isLoggingOnly()).isTrue();
            assertThat(outcome.getSubmissionReceipt()).containsSame(receipt);
        });
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void loggingOnlyStillSuppressesAConfiguredCustomMailer() throws Exception {
        final RecordingCustomMailer custom = new RecordingCustomMailer();
        try (Mailer mailer = builder(Mode.CUSTOM, custom).withTransportModeLoggingOnly(true).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(message().withHeader("Cc", BOB).buildEmail());
            assertRecipients(receipt, List.of(ALICE, BOB));
            assertUnknownFacts(receipt);
        }
        assertThat(custom.calls.get()).isZero();
        assertThat(TRANSPORTS.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void simpleBatchObserversReceiveEachPreparedRecipientList(final Mode mode) throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        final RecordingCustomMailer custom = new RecordingCustomMailer();
        try (Mailer mailer = builder(mode, custom)
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(observed::add).build()).buildMailer()) {
            mailer.sync().sendMailsInSimpleBatch(List.of(
                    message().withHeader("Cc", BOB).buildEmail(),
                    message().withHeader("Bcc", "hidden@example.org").buildEmail()));
        }
        assertThat(observed).hasSize(2);
        assertRecipients(observed.get(0).getSubmissionReceipt().orElseThrow(), List.of(ALICE, BOB));
        assertRecipients(observed.get(1).getSubmissionReceipt().orElseThrow(), List.of(ALICE, "hidden@example.org"));
        observed.forEach(outcome -> assertUnknownFacts(outcome.getSubmissionReceipt().orElseThrow()));
        assertThat(custom.calls.get()).isEqualTo(mode == Mode.CUSTOM ? 2 : 0);
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void scopedLoggingOnlySendingUsesPreparedRecipients() throws Exception {
        final AtomicReference<MailSubmissionReceipt> receipt = new AtomicReference<>();
        try (Mailer mailer = builder(Mode.LOGGING_ONLY, new RecordingCustomMailer()).buildMailer()) {
            mailer.withOpenConnection(sender -> receipt.set(sender.sendMail(message().withHeader("Cc", BOB).buildEmail())));
        }
        assertRecipients(receipt.get(), List.of(ALICE, BOB));
        assertUnknownFacts(receipt.get());
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void routedLoggingOnlyScopesUseTheirSelectedPreparedRecipients() throws Exception {
        final AtomicReference<MailSubmissionReceipt> scoped = new AtomicReference<>();
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer fallback = builder(Mode.LOGGING_ONLY, new RecordingCustomMailer()).buildMailer();
                 Mailer routed = smtpInitiator().withFailoverMailer(fallback,
                         FailoverPolicy.builder().withPrimaryConnectionAttempts(1).build())
                         .withSendHandlers(MailSendHandlers.builder().withResultObserver(observed::add).build()).buildMailer()) {
                routed.withOpenConnection(sender -> scoped.set(sender.sendMail(message().withHeader("Bcc", BOB).buildEmail())));
                routed.sync().sendMailsInSimpleBatch(List.of(message().withHeader("Cc", "copy@example.org").buildEmail()));
            }
        }
        assertThat(FAILED_CONNECTIONS.get()).isPositive();
        assertThat(observed).hasSize(2).allSatisfy(outcome -> assertThat(outcome.isLoggingOnly()).isTrue());
        assertRecipients(scoped.get(), List.of(ALICE, BOB));
        assertRecipients(observed.get(1).getSubmissionReceipt().orElseThrow(), List.of(ALICE, "copy@example.org"));
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void routedSimpleBatchCapturesIntentBeforeTheSelectedCustomMailerMutatesIt() throws Exception {
        final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
        final RecordingCustomMailer custom = new RecordingCustomMailer() {
            @Override protected void receive(final MimeMessage message) throws MessagingException {
                message.setRecipients(TO, "callback-owned@example.org");
                message.removeHeader("Cc");
            }
        };
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer fallback = builder(Mode.CUSTOM, custom).buildMailer();
                 Mailer routed = smtpInitiator().withFailoverMailer(fallback,
                         FailoverPolicy.builder().withPrimaryConnectionAttempts(1).build())
                         .withSendHandlers(MailSendHandlers.builder().withResultObserver(observed::add).build()).buildMailer()) {
                routed.sync().sendMailsInSimpleBatch(List.of(message().withHeader("Cc", BOB).buildEmail()));
            }
        }
        assertThat(FAILED_CONNECTIONS.get()).isEqualTo(1);
        assertThat(custom.calls.get()).isEqualTo(1);
        assertThat(observed).singleElement().satisfies(outcome -> {
            assertThat(outcome.isLoggingOnly()).isFalse();
            final MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElseThrow();
            assertThat(receipt.getSubmittedAt()).isAfterOrEqualTo(custom.returnedAt.get());
            assertRecipients(receipt, List.of(ALICE, BOB));
            assertUnknownFacts(receipt);
        });
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void customMailerCannotRewriteTheAlreadyCapturedPreparedIntent() throws Exception {
        final RecordingCustomMailer custom = new RecordingCustomMailer() {
            @Override protected void receive(final MimeMessage message) throws MessagingException {
                message.setRecipients(TO, "callback-owned@example.org");
                message.removeHeader("Cc");
                message.setHeader("Message-ID", "<callback-owned@example.org>");
            }
        };
        final MailSubmissionReceipt receipt;
        try (Mailer mailer = builder(Mode.CUSTOM, custom).buildMailer()) {
            receipt = mailer.sync().sendMail(message().withHeader("Cc", BOB).fixingMessageId("<prepared@example.org>").buildEmail());
        }
        assertThat(receipt.getSubmittedAt()).isAfterOrEqualTo(custom.returnedAt.get());
        assertThat(receipt.getEmailId()).isEqualTo("<prepared@example.org>");
        assertRecipients(receipt, List.of(ALICE, BOB));
        assertUnknownFacts(receipt);
        assertThat(custom.calls.get()).isEqualTo(1);
    }

    @Test
    void customMailerKeepsItsExceptionAndDoesNotAcquireATransport() throws Exception {
        final IllegalStateException applicationProblem = new IllegalStateException("application delivery failed");
        final RecordingCustomMailer custom = new RecordingCustomMailer() {
            @Override protected void receive(final MimeMessage message) {
                throw applicationProblem;
            }
        };
        final Throwable failure;
        try (Mailer mailer = builder(Mode.CUSTOM, custom).buildMailer()) {
            failure = catchThrowable(() -> mailer.sync().sendMail(message().withHeader("Cc", BOB).buildEmail()));
        }
        assertThat(failure).isSameAs(applicationProblem);
        assertThat(custom.calls.get()).isEqualTo(1);
        assertThat(TRANSPORTS.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"LOGGING_ONLY, false", "LOGGING_ONLY, true", "CUSTOM, false", "CUSTOM, true"})
    void unreadablePreparedRecipientHeadersPreserveFallbackAndExplicitOverrides(final Mode mode, final boolean deliveryOverride) throws Exception {
        final String malformedCc = "broken <";
        final AtomicInteger securityPasses = new AtomicInteger();
        final EmailPopulatingBuilder draft = message().signWithDomainKey(SIGNING_FIXTURE);
        if (deliveryOverride) {
            draft.withOverrideReceivers(override("delivery@example.org"), override("delivery@example.org"));
        }
        final RecordingCustomMailer custom = new RecordingCustomMailer() {
            @Override protected void receive(final MimeMessage message) throws MessagingException {
                assertThat(message.getHeader("Cc", null)).isEqualTo(malformedCc);
                assertThatThrownBy(message::getAllRecipients).isInstanceOf(AddressException.class);
            }
        };
        final DKIMModule dkim = signingModuleWithCc(malformedCc, securityPasses);
        try (Mailer mailer = builder(mode, custom).buildMailer();
             MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(draft.buildEmail());
            assertRecipients(receipt, deliveryOverride ? List.of("delivery@example.org", "delivery@example.org") : List.of(ALICE));
            assertUnknownFacts(receipt);
        }
        assertThat(securityPasses.get()).isEqualTo(1);
        assertThat(custom.calls.get()).isEqualTo(mode == Mode.CUSTOM ? 1 : 0);
        assertThat(TRANSPORTS.get()).isZero();
    }

    @Test
    void receiptDoesNotReadAnAttachmentAfterTheCustomMailerReturns() throws Exception {
        final CountingDataSource payload = new CountingDataSource();
        final AtomicInteger preparedReads = new AtomicInteger();
        final RecordingCustomMailer custom = new RecordingCustomMailer() {
            @Override protected void receive(final MimeMessage message) {
                preparedReads.set(payload.opens.get());
            }
        };
        try (Mailer mailer = builder(Mode.CUSTOM, custom).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(message().withHeader("Cc", BOB)
                    .withAttachment("payload.bin", payload).buildEmail());
            assertThat(payload.opens.get()).isEqualTo(preparedReads.get());
            assertRecipients(receipt, List.of(ALICE, BOB));
        }
        assertThat(payload.opens.get()).isEqualTo(preparedReads.get());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void receiptReusesRecipientsFromTheOneCompletedSecurityPass(final Mode mode) throws Exception {
        final Email email = message().withHeader("Cc", BOB).signWithDomainKey(SIGNING_FIXTURE).buildEmail();
        final AtomicInteger securityPasses = new AtomicInteger();
        final DKIMModule dkim = signingModuleWithCc("security-adjusted@example.org", securityPasses);
        try (Mailer mailer = builder(mode, new RecordingCustomMailer()).buildMailer();
             MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            assertRecipients(receipt, List.of(ALICE, "security-adjusted@example.org"));
            assertUnknownFacts(receipt);
        }
        assertThat(securityPasses.get()).isEqualTo(1);
        assertThat(TRANSPORTS.get()).isZero();
    }

    private static DKIMModule signingModuleWithCc(final String cc, final AtomicInteger securityPasses) {
        final DKIMModule dkim = mock(DKIMModule.class);
        when(dkim.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
                .thenAnswer(invocation -> {
                    assertThat(securityPasses.incrementAndGet()).isEqualTo(1);
                    final MimeMessage message = invocation.getArgument(1);
                    message.setHeader("Cc", cc);
                    return FinalizedMimeMessage.finalizeMessage(message, FINAL_WIRE_SIGNED);
                });
        return dkim;
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void exactMessagesKeepTheirExplicitEnvelopeAndIdentifier(final Mode mode) throws Exception {
        final byte[] source = ("From: sender@example.org\r\nTo: visible@example.org\r\n"
                + "Bcc: hidden@example.org\r\nSubject: Exact\r\n\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
        final Email exact = MAIL.emailBuilder().startingFromExactEml(source)
                .withEnvelopeRecipients("delivery@example.org", "delivery@example.org").buildEmail();
        final RecordingCustomMailer custom = new RecordingCustomMailer();
        final MailSubmissionReceipt receipt;
        try (Mailer mailer = builder(mode, custom).buildMailer()) {
            receipt = mailer.sync().sendMail(exact);
        }
        assertRecipients(receipt, List.of("delivery@example.org", "delivery@example.org"));
        assertThat(receipt.getEmailId()).isNull();
        assertUnknownFacts(receipt);
        assertThat(TRANSPORTS.get()).isZero();
    }

    private static MailerGenericBuilder<?> builder(final Mode mode, final RecordingCustomMailer custom) throws Exception {
        final MailerGenericBuilder<?> builder = MAIL.mailerBuilder(sessionFor(ForbiddenTransport.class))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
        return mode == Mode.LOGGING_ONLY ? builder.withTransportModeLoggingOnly(true) : builder.withCustomMailer(custom);
    }

    private static MailerGenericBuilder<?> smtpInitiator() throws Exception {
        return MAIL.mailerBuilder(sessionFor(RefusingTransport.class));
    }

    private static Session sessionFor(final Class<? extends Transport> transportType) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "127.0.0.1");
        final Session session = Session.getInstance(properties);
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", transportType.getName(), "test", "1"));
        return session;
    }

    private static EmailPopulatingBuilder message() {
        return MAIL.emailBuilder().startingBlank().from("sender@example.org").to(ALICE)
                .withSubject("Prepared receipt").withPlainText("body");
    }

    private static Recipient override(final String address) {
        return new Recipient(null, address, null, null);
    }

    private static void assertRecipients(final MailSubmissionReceipt receipt, final List<String> recipients) {
        assertThat(receipt.getRecipientResults()).extracting(MailRecipientResult::getEnvelopeAddress)
                .containsExactlyElementsOf(recipients);
        assertThatThrownBy(() -> receipt.getRecipientResults().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertUnknownFacts(final MailSubmissionReceipt receipt) {
        assertThat(receipt.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(receipt.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
        assertThat(receipt.getSmtpResponse()).isEmpty();
        assertThat(receipt.getAcceptedRecipients()).isEmpty();
        assertThat(receipt.getValidUnsentRecipients()).isEmpty();
        assertThat(receipt.getInvalidRecipients()).isEmpty();
        assertThat(receipt.getRetryableRecipients()).isEmpty();
        assertThat(receipt.getEnvelopeId()).isNull();
        assertThat(receipt.getMessageSize()).isNull();
        assertThat(receipt.getServerMaximumMessageSize()).isNull();
        assertThat(receipt.isRequireTlsUsed()).isFalse();
        assertThat(receipt.hasServerAcceptanceInformation()).isFalse();
        receipt.getRecipientResults().forEach(recipient -> {
            assertThat(recipient.getDisposition()).isEqualTo(MailRecipientDisposition.UNKNOWN);
            assertThat(recipient.getRcptAttempted()).isNull();
            assertThat(recipient.getRcptResponse()).isEmpty();
        });
    }

    private enum Mode { LOGGING_ONLY, CUSTOM }

    private static final class Projection {
        private final String name;
        private final Email email;
        private final List<String> recipients;

        private Projection(final String name, final Email email, final List<String> recipients) {
            this.name = name;
            this.email = email;
            this.recipients = recipients;
        }
    }

    private static class RecordingCustomMailer implements CustomMailer {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Instant> returnedAt = new AtomicReference<>();

        @Override public void testConnection(final OperationalConfig config, final Session session) {
            throw new AssertionError("A simulated/custom send must not test a connection");
        }

        @Override public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
            calls.incrementAndGet();
            try {
                receive(message);
            } catch (MessagingException failure) {
                throw new IllegalStateException(failure);
            }
            returnedAt.set(Instant.now());
        }

        protected void receive(final MimeMessage message) throws MessagingException { }
    }

    public static final class ForbiddenTransport extends Transport {
        public ForbiddenTransport(final Session session, final URLName url) {
            super(session, url);
            TRANSPORTS.incrementAndGet();
            throw new AssertionError("Recipient inspection must not acquire a Transport");
        }

        @Override public void sendMessage(final Message message, final Address[] recipients) {
            throw new AssertionError("Recipient inspection must not submit a message");
        }
    }

    public static final class RefusingTransport extends Transport {
        public RefusingTransport(final Session session, final URLName url) {
            super(session, url);
        }

        @Override protected boolean protocolConnect(final String host, final int port, final String user, final String password)
                throws MessagingException {
            FAILED_CONNECTIONS.incrementAndGet();
            throw new MessagingException("Deterministic setup refusal", new ConnectException("No socket was opened"));
        }

        @Override public void sendMessage(final Message message, final Address[] recipients) {
            throw new AssertionError("The refusing primary cannot submit a message");
        }
    }

    private static final class CountingDataSource implements DataSource {
        private final AtomicInteger opens = new AtomicInteger();

        @Override public InputStream getInputStream() {
            opens.incrementAndGet();
            return new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
        }

        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read only"); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "payload.bin"; }
    }
}
