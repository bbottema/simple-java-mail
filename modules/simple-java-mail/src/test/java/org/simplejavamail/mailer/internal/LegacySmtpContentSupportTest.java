package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import testutil.smtp.ScriptedSmtpServer;
import testutil.ConfigLoaderTestHelper;

import javax.net.SocketFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.assertCompatibilityFailure;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.builder;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.composedEmail;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.exactEmail;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.expectCommand;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

@Timeout(30)
class LegacySmtpContentSupportTest {

    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String ASCII_RECIPIENT = "receiver@example.test";
    private static final String INTERNATIONAL_RECIPIENT = "müller@example.test";
    private static final String PROPERTY = "simplejavamail.smtp.legacycontentsupport";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customSocketFactoryRetainsUtf8EncodingAndPerMessageNegotiation(final boolean advertised) throws Exception {
        final SocketFactory socketFactory = SocketFactory.getDefault();
        final Properties properties = new Properties();
        properties.put("mail.smtp.socketFactory", socketFactory);
        properties.setProperty("mail.smtp.socketFactory.fallback", "true");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet(advertised ? "250-localhost\r\n250 SMTPUTF8" : "250 localhost");
            acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT);
            acceptMessage(peer, MAIL_FROM + (advertised ? " SMTPUTF8" : ""), "", INTERNATIONAL_RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperties(properties).withLegacySmtpContentSupport(true).buildMailer()) {
            mailer.sync().sendMail(message(ASCII_RECIPIENT));
            mailer.sync().sendMail(message(INTERNATIONAL_RECIPIENT));
            assertThat(mailer.getSession().getProperties().get("mail.smtp.socketFactory")).isSameAs(socketFactory);
            assertThat(mailer.getSession().getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("true");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void internationalizedProviderSubmitterKeepsItsDeclarationAndCannotBeSilentlyDropped(final boolean advertised) throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n" + (advertised ? "250-SMTPUTF8\r\n" : "") + "250 AUTH PLAIN");
            if (advertised) {
                acceptMessage(peer, MAIL_FROM + " SMTPUTF8 AUTH=caf+C3+A9", "", ASCII_RECIPIENT);
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.submitter", "café").withLegacySmtpContentSupport(true).buildMailer()) {
            if (advertised) {
                mailer.sync().sendMail(message(ASCII_RECIPIENT));
            } else {
                assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(message(ASCII_RECIPIENT))), "submitter identity");
            }
        }
    }

    @Test
    void cancellationDuringInternationalSubmissionDoesNotLeakIntoTheNextAttempt() throws Exception {
        final CountDownLatch mailCommandReceived = new CountDownLatch(1);
        final AtomicInteger connections = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            if (connections.incrementAndGet() == 1) {
                expectCommand(peer, MAIL_FROM + " SMTPUTF8");
                mailCommandReceived.countDown();
                peer.expectClosed();
            } else {
                acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT);
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(message(INTERNATIONAL_RECIPIENT));
            assertThat(mailCommandReceived.await(10, SECONDS)).isTrue();
            send.requestCancellation();
            assertThat(catchThrowable(() -> send.getCompletion().get(10, SECONDS))).hasCauseInstanceOf(MailSendCancelledException.class);
            assertThat(outcomes).hasSize(1);
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(message(ASCII_RECIPIENT));
            assertThat(receipt.getAcceptedRecipients()).containsExactly(ASCII_RECIPIENT);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(receipt);
        }
    }

    @ParameterizedTest(name = "{0}: SMTPUTF8={1}, 8BITMIME={2}, legacy={3}")
    @MethodSource("contentCapabilityCombinations")
    void negotiatesEachAdvertisedCapabilityAndRequiresPermissionForMissingOnes(final String contentKind, final boolean smtpUtf8,
            final boolean eightBitMime, final boolean legacy) throws Exception {
        final boolean internationalAddress = contentKind.equals("address") || contentKind.equals("combined");
        final boolean rawHeaders = contentKind.equals("headers");
        final boolean rawBody = contentKind.equals("body") || contentKind.equals("combined");
        final boolean requiresUtf8 = internationalAddress || rawHeaders;
        final boolean permitted = legacy || (!requiresUtf8 || smtpUtf8) && (!rawBody || eightBitMime);
        final String recipient = internationalAddress ? INTERNATIONAL_RECIPIENT : ASCII_RECIPIENT;
        final Email email = contentForCombination(contentKind, recipient);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n" + (smtpUtf8 ? "250-SMTPUTF8\r\n" : "") + (eightBitMime ? "250-8BITMIME\r\n" : "") + "250 SIZE 100000");
            if (permitted) {
                final String mailCommand = peer.readLine();
                final String expectedPrefix = MAIL_FROM + (requiresUtf8 && smtpUtf8 ? " SMTPUTF8" : "")
                        + (rawBody && eightBitMime ? " BODY=8BITMIME" : "");
                assertThat(mailCommand).matches(java.util.regex.Pattern.quote(expectedPrefix) + " SIZE=[0-9]+");
                final String content = acceptMessageAfterMailFrom(peer, "", recipient);
                assertThat(mailCommand).endsWith(" SIZE=" + content.getBytes(UTF_8).length);
                assertThat(content).contains("To: " + recipient);
                if (rawHeaders) {
                    assertThat(content.getBytes(UTF_8)).containsExactly(rawHeaderBytes());
                } else if (rawBody) {
                    assertThat(content).contains("Café", "Content-Transfer-Encoding: 8bit");
                } else if (!internationalAddress) {
                    assertThat(US_ASCII.newEncoder().canEncode(content)).isTrue();
                }
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(legacy).withMailSendObserver(outcomes::add).buildMailer()) {
            if (permitted) {
                final MailSubmissionReceipt receipt = mailer.async().sendMail(email).getCompletion().get(10, SECONDS);
                assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(receipt);
            } else {
                assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(email)), "does not advertise");
                assertThat(outcomes).hasSize(1);
                assertThat(outcomes.get(0).isSuccessful()).isFalse();
            }
        }
    }

    private static Stream<Arguments> contentCapabilityCombinations() {
        final List<Arguments> combinations = new ArrayList<>();
        for (final String contentKind : List.of("ascii", "display text", "address", "headers", "body", "combined")) {
            for (final boolean smtpUtf8 : List.of(false, true)) {
                for (final boolean eightBitMime : List.of(false, true)) {
                    for (final boolean legacy : List.of(false, true)) {
                        combinations.add(Arguments.of(contentKind, smtpUtf8, eightBitMime, legacy));
                    }
                }
            }
        }
        return combinations.stream();
    }

    private static Email contentForCombination(final String contentKind, final String recipient) {
        if (contentKind.equals("headers")) {
            return exactEmail(rawHeaderBytes());
        }
        if (contentKind.equals("body") || contentKind.equals("combined")) {
            return factory().emailBuilder().copying(message(recipient))
                    .withPlainText("Café").withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).buildEmail();
        }
        return contentKind.equals("display text")
                ? composedEmail("sender@example.test", recipient, "Café", "Café") : message(recipient);
    }

    private static byte[] rawHeaderBytes() {
        return ("From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Café\r\n\r\nASCII body\r\n").getBytes(UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pooled", "batch", "scope"})
    void alternatingAsciiAndInternationalMailDoesNotReconnectOrLeakDeclarations(final String mode) throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        final List<Email> emails = List.of(message(ASCII_RECIPIENT), message(INTERNATIONAL_RECIPIENT), message(ASCII_RECIPIENT));
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            connections.incrementAndGet();
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT);
            acceptMessage(peer, MAIL_FROM + " SMTPUTF8", "", INTERNATIONAL_RECIPIENT);
            acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            if (mode.equals("batch")) {
                mailer.async().sendMailsInSimpleBatch(emails).getCompletion().get(10, SECONDS);
            } else if (mode.equals("scope")) {
                mailer.withOpenConnection(sender -> emails.forEach(sender::sendMail));
            } else {
                mailer.sync().sendMail(emails.get(0));
                mailer.async().sendMail(emails.get(1)).getCompletion().get(10, SECONDS);
                mailer.sync().sendMail(emails.get(2));
            }
            assertThat(mailer.getSession().getProperty("mail.mime.allowutf8")).isEqualTo("true");
        }
        assertThat(connections).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "body", "both"})
    void legacyExactContentIsSubmittedWithoutReencodingOrInventedExtensions(final String contentKind) throws Exception {
        final byte[] original = ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Subject: " + (contentKind.equals("body") ? "ASCII" : "Café") + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"
                + (contentKind.equals("headers") ? "ASCII" : "Café") + "\r\n").getBytes(UTF_8);
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            assertThat(acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT).getBytes(UTF_8)).containsExactly(original);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(true).buildMailer()) {
            assertThat(mailer.sync().sendMail(exactEmail(original)).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @Test
    void asciiBodyLabelledEightBitNeedsNoAdvertisedCapability() throws Exception {
        final Email email = factory().emailBuilder().copying(message(ASCII_RECIPIENT))
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).buildEmail();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            assertThat(acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT)).contains("Content-Transfer-Encoding: 8bit");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(email);
        }
    }

    @Test
    void rejectedLegacyAttemptIsNotRetriedAndCannotContaminateTheReplacement() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, UTF_8, peer -> {
            final boolean first = connections.incrementAndGet() == 1;
            peer.greet("250 localhost");
            if (first) {
                expectCommand(peer, "MAIL FROM:<séndér@example.test>");
                peer.reply("550 5.6.7 internationalized mail rejected");
                peer.expect("RSET");
                peer.reply("250 reset");
                peer.expectClosed();
            } else {
                acceptMessage(peer, MAIL_FROM, "", ASCII_RECIPIENT);
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(true).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(
                    composedEmail("séndér@example.test", INTERNATIONAL_RECIPIENT, "Test", "Body")));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt rejected = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(rejected.getStatus()).isEqualTo(REJECTED);
            assertThat(rejected.getSmtpResponse().orElseThrow().getReturnCode()).isEqualTo(550);
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(rejected);
            final MailSubmissionReceipt accepted = mailer.async().sendMail(message(ASCII_RECIPIENT)).getCompletion().get(10, SECONDS);
            assertThat(accepted.getStatus()).isEqualTo(ACCEPTED);
            assertThat(accepted.getAcceptedRecipients()).containsExactly(ASCII_RECIPIENT);
            assertThat(accepted.getSmtpResponse().orElseThrow().getReturnCode()).isEqualTo(250);
            assertThat(outcomes).hasSize(2);
        }
        assertThat(connections).hasValue(2);
    }

    @Test
    void legacyPermissionBelongsToTheSelectedServerNotTheEntryMailer() throws Exception {
        final UUID cluster = UUID.randomUUID();
        try (ScriptedSmtpServer strictServer = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer); // Local compatibility rejection must not issue MAIL FROM or break the lease.
        }); ScriptedSmtpServer legacyServer = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            acceptMessage(peer, MAIL_FROM, "", INTERNATIONAL_RECIPIENT);
            finishConnection(peer);
        }); Mailer strict = builder(strictServer).withClusterKey(cluster).withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN)
                .buildMailer(); Mailer legacy = builder(legacyServer).withClusterKey(cluster).withLegacySmtpContentSupport(true).buildMailer()) {
            int accepted = 0;
            int rejected = 0;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    legacy.sync().sendMail(message(INTERNATIONAL_RECIPIENT));
                    accepted++;
                } catch (MailSubmissionException failure) {
                    assertCompatibilityFailure(failure, "does not advertise SMTPUTF8");
                    rejected++;
                }
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(strict.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isFalse();
        }
    }

    @Test
    void propertiesBuilderReplacementAndSnapshotsRemainIndependent() throws Exception {
        final SimpleJavaMailConfig configured = ConfigLoader.builder().withMap("legacy relay", Map.of(PROPERTY, "true")).load();
        final SimpleJavaMail configuredFactory = SimpleJavaMail.withConfig(configured);
        try (Mailer enabled = configuredFactory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
             Mailer disabled = configuredFactory.mailerBuilder().withLegacySmtpContentSupport(true).withLegacySmtpContentSupport(false)
                     .withTransportModeLoggingOnly(true).buildMailer();
             Mailer separate = factory().mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(enabled.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isTrue();
            assertThat(disabled.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isFalse();
            assertThat(separate.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isFalse();
            assertThat(enabled.getSession().getProperty("mail.mime.allowutf8")).isEqualTo("true");
            assertThat(separate.getSession().getProperty("mail.mime.allowutf8")).isEqualTo("true");
            final ConfigPropertyDiagnostic diagnostic = configured.getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION).get(0);
            assertThat(diagnostic.getPropertyName()).isEqualTo(PROPERTY);
            assertThat(diagnostic.getDisplayValue()).isEqualTo("true");
            assertThat(diagnostic.getSourceName()).isEqualTo("legacy relay");
            assertThat(diagnostic.isRedacted()).isFalse();
            final Email completeEmail = factory().emailBuilder().copying(message(ASCII_RECIPIENT)).buildEmailCompletedWithDefaultsAndOverrides();
            assertThat(SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(enabled.getSession(), completeEmail)
                    .isLegacySmtpContentSupportEnabled()).isTrue();
            assertThat(SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(disabled.getSession(), completeEmail)
                    .isLegacySmtpContentSupportEnabled()).isFalse();
        }
    }

    @Test
    void legacyPermissionDoesNotOverrideExplicitlyDisabledUtf8OrSecurityRequirements() throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(true).withProperty("mail.mime.allowutf8", false).buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(message(INTERNATIONAL_RECIPIENT))), "UTF-8 output");
            final Email secured = factory().emailBuilder().copying(message(ASCII_RECIPIENT)).withTlsRequiredForOnwardDelivery().buildEmail();
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(secured)), "connection is not using TLS");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"binary", "nul", "long", "invalid-header", "body-conflict"})
    void legacyPermissionDoesNotBypassContentSafetyChecks(final String kind) throws Exception {
        final String headers = "From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: "
                + (kind.equals("invalid-header") ? "Café" : "ASCII") + "\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: " + (kind.equals("binary") ? "binary" : "8bit") + "\r\n\r\n";
        final String body = kind.equals("nul") ? "zero\0byte" : kind.equals("long") ? "x".repeat(999) : "Café";
        final byte[] original = (headers + body + "\r\n").getBytes(kind.equals("invalid-header") ? ISO_8859_1 : UTF_8);
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250 localhost");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(true)
                .withProperty("mail.smtp.mailextension", kind.equals("body-conflict") ? "BODY=7BIT" : "").buildMailer()) {
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(exactEmail(original))), "No message was submitted");
        }
    }

    @Test
    void legacyAddressesDoNotImplyInternationalizedOrcptSupport() throws Exception {
        final Email email = factory().emailBuilder().copying(message(INTERNATIONAL_RECIPIENT))
                .withDeliveryStatusNotificationNotifyOptions(NotifyOption.FAILURE).buildEmail();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 DSN");
            SmtpDsnCharacterizationTest.readGeneratedEnvelopeId(peer, MAIL_FROM);
            SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom(peer, " NOTIFY=FAILURE", INTERNATIONAL_RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withLegacySmtpContentSupport(true).buildMailer()) {
            assertThat(mailer.sync().sendMail(email).getEnvelopeId()).isNotNull();
        }
    }

    @Test
    void callerOwnedAngusRetainsSessionWideDeclarationsEvenForAsciiMail() throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SMTPUTF8");
            acceptMessage(peer, MAIL_FROM + " SMTPUTF8", "", ASCII_RECIPIENT);
            finishConnection(peer);
        })) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", String.valueOf(server.port()));
            properties.setProperty("mail.smtp.localhost", "probe.example.test");
            properties.setProperty("mail.mime.allowutf8", "true");
            final Session session = Session.getInstance(properties);
            final String provider = session.getProvider("smtp").getClassName();
            try (Mailer mailer = factory().mailerBuilder(session).withSmtpClientHostname("probe.example.test")
                    .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withSessionTimeout(5000).buildMailer()) {
                mailer.sync().sendMail(message(ASCII_RECIPIENT));
                assertThat(session.getProvider("smtp").getClassName()).isEqualTo(provider);
                assertThat(session.getProperty("mail.mime.allowutf8")).isEqualTo("true");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitMailExtensionRemainsDeliberateAndDoesNotGetDuplicated(final boolean advertised) throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet(advertised ? "250-localhost\r\n250 SMTPUTF8" : "250 localhost");
            acceptMessage(peer, MAIL_FROM + " SMTPUTF8 XTRACE=SMTPUTF8", "", ASCII_RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", "SMTPUTF8 XTRACE=SMTPUTF8").buildMailer()) {
            mailer.sync().sendMail(message(ASCII_RECIPIENT));
        }
    }

    private static Email message(final String recipient) {
        return composedEmail("sender@example.test", recipient, "Synthetic test", "ASCII body");
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }
}
