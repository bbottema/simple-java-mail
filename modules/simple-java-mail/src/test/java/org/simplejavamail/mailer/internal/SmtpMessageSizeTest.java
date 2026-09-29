package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailRetryDisposition;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import testutil.smtp.SmtpConversation;
import testutil.smtp.ScriptedSmtpServer;
import org.simplejavamail.recipient.RecipientBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.builder;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.exactEmail;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

/** Real loopback SMTP conversations; receipt sizes are checked against independently captured, unescaped DATA bytes. */
@Timeout(40)
class SmtpMessageSizeTest {
    private static final String RECIPIENT = "receiver@example.test";
    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactLimitIsAllowedAndObserverReceivesTheSameSizedReceipt(final boolean asynchronous) throws Exception {
        final byte[] content = eml(".first\r\n..second\r\nlast\r\n");
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250 SIZE " + content.length);
            assertThat(acceptSizedMessage(peer, (long) content.length).getBytes(UTF_8)).containsExactly(content);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final MailSubmissionReceipt receipt = asynchronous
                    ? mailer.async().sendMail(exactEmail(content)).getCompletion().get(10, SECONDS) : mailer.sync().sendMail(exactEmail(content));
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getServerMaximumMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
            assertThat(outcomes).singleElement().satisfies(outcome -> assertThat(outcome.getSubmissionReceipt()).containsSame(receipt));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsBeforeMailAndReusesTheSameHealthyLeaseWithoutStaleFacts(final boolean oversizedDiagnostics) throws Exception {
        final byte[] small = eml("small\r\n");
        final byte[] large = eml("too large".repeat(40) + "\r\n");
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n" + (oversizedDiagnostics ? "250-X-LONG " + "x".repeat(70000) + "\r\n" : "")
                    + "250 SIZE " + small.length);
            acceptSizedMessage(peer, (long) small.length);
            // No MAIL, RCPT, DATA or RSET for the oversized attempt; only the following small message arrives.
            acceptSizedMessage(peer, (long) small.length);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            mailer.sync().sendMail(exactEmail(small));
            final Throwable thrown = catchThrowable(() -> mailer.sync().sendMail(exactEmail(large)));
            assertThat(thrown).isInstanceOf(MailSubmissionException.class).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            assertThat(thrown.getCause()).hasMessageContaining("Reduce its content or attachments");
            final MailSubmissionReceipt failure = ((MailSubmissionException) thrown).getSubmissionReceipt();
            assertThat(failure.getMessageSize()).isEqualTo((long) large.length);
            assertThat(failure.getServerMaximumMessageSize()).isEqualTo((long) small.length);
            assertThat(failure.getStatus()).isEqualTo(REJECTED);
            assertThat(failure.getRetryDisposition()).isEqualTo(MailRetryDisposition.CALLER_POLICY_REQUIRED);
            assertThat(failure.getSmtpResponse()).isEmpty();
            assertThat(failure.getEnvelopeId()).isNull();
            assertThat(failure.isRequireTlsUsed()).isFalse();
            assertThat(failure.getValidUnsentRecipients()).containsExactly(RECIPIENT);
            assertThat(failure.getRecipientResults()).allSatisfy(recipient -> {
                assertThat(recipient.getRcptAttempted()).contains(false);
                assertThat(recipient.getRcptResponse()).isEmpty();
            });
            final MailSubmissionReceipt next = mailer.sync().sendMail(exactEmail(small));
            assertThat(next.getMessageSize()).isEqualTo((long) small.length);
            assertThat(next.getStatus()).isEqualTo(ACCEPTED);
            assertThat(outcomes).hasSize(3);
            assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "0", "invalid", "9223372036854775808", "1\r\n250-SIZE 99999"})
    void unknownLimitsDoNotRejectAndMissingSizeDoesNotGetADeclaration(final String advertisedValue) throws Exception {
        final byte[] content = eml("unknown limit\r\n");
        final boolean advertised = !advertisedValue.equals("missing");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n" + (advertised ? "250-SIZE " + advertisedValue + "\r\n" : "") + "250 HELP");
            acceptSizedMessage(peer, advertised ? (long) content.length : null);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(exactEmail(content));
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getServerMaximumMessageSize()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"99999", "invalid", "0", ""})
    void laterSizeDisagreementBeyondTheDiagnosticBudgetDoesNotRejectLegacySubmissions(final String laterValue) throws Exception {
        final byte[] content = eml("The first advertised limit is unusable once the later SIZE value is read.\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250-SIZE 1\r\n250-X-LONG " + "x".repeat(70000) + "\r\n250 SIZE " + laterValue);
            acceptSizedMessage(peer, (long) content.length);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(exactEmail(content));
            assertThat(receipt.getServerMaximumMessageSize()).isNull();
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @Test
    void explicitlyDisabledEhloDoesNotInventSizeSupport() throws Exception {
        final byte[] content = eml("Old-style SMTP.\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.reply("220 old.example SMTP");
            peer.expect("HELO probe.example.test");
            peer.reply("250 hello");
            acceptSizedMessage(peer, null);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.ehlo", false).buildMailer()) {
            assertThat(mailer.sync().sendMail(exactEmail(content)).getServerMaximumMessageSize()).isNull();
        }
    }

    @Test
    void countsAfterEightBitConversionAndOmitsBccAndContentLengthForOrdinaryMessages() throws Exception {
        final CompletableFuture<String> captured = new CompletableFuture<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250-8BITMIME\r\n250 SIZE 99999");
            captured.complete(acceptConvertedEightBitMessage(peer));
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.allow8bitmime", true).buildMailer()) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.test").withRecipients(RecipientBuilder.bcc(null, RECIPIENT))
                    .withSubject("Café").withPlainText("Café\r\n.hello")
                    .withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
                    .withHeader("Content-Length", "123456").buildEmail();
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            final String content = captured.get(10, SECONDS);
            assertThat(content).contains("Content-Transfer-Encoding: 8bit").doesNotContain("Bcc:", "Content-Length:");
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.getBytes(UTF_8).length);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesPostTlsCapabilitiesInsteadOfTheEarlierLimit(final boolean sizeAfterTls) throws Exception {
        final byte[] content = eml("after TLS\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250-SIZE 1\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply("250-localhost\r\n250-X-LONG " + "x".repeat(70000) + "\r\n" + (sizeAfterTls ? "250 SIZE 99999" : "250 HELP"));
            acceptSizedMessage(peer, sizeAfterTls ? (long) content.length : null);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withTransportStrategy(TransportStrategy.SMTP_TLS)
                .trustingSSLHosts("localhost").withProperty("mail.smtp.ssl.protocols", "TLSv1.2").buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(exactEmail(content));
            assertThat(receipt.getServerMaximumMessageSize()).isEqualTo(sizeAfterTls ? 99999L : null);
        }
    }

    @Test
    void preservesAdvancedEstimateAndUnrelatedParametersButReportsMeasuredSize() throws Exception {
        final byte[] content = eml("custom estimate\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 99999");
            assertThat(readCommand(peer)).isEqualTo(MAIL_FROM + " X-TEST=value size=42");
            acceptMessageAfterMailFrom(peer, "", RECIPIENT);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", "X-TEST=value size=42").buildMailer()) {
            assertThat(mailer.sync().sendMail(exactEmail(content)).getMessageSize()).isEqualTo((long) content.length);
        }
    }

    @Test
    void malformedAdvancedDeclarationFailsBeforeSubmissionAndRetainsOnlyTheKnownLimit() throws Exception {
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 99999");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.mailextension", "SIZE=1 size=2").buildMailer()) {
            final Throwable thrown = catchThrowable(() -> mailer.sync().sendMail(exactEmail(eml("small\r\n"))));
            assertThat(thrown).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) thrown).getSubmissionReceipt();
            assertThat(receipt.getServerMaximumMessageSize()).isEqualTo(99999L);
            assertThat(receipt.getMessageSize()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void streamingBatchesStopAtTheOversizedEmailWithoutOpeningLaterElements(final boolean asynchronous) throws Exception {
        final byte[] small = eml("small\r\n");
        final byte[] large = eml("large".repeat(100) + "\r\n");
        final AtomicInteger visited = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final Iterable<Email> emails = () -> new Iterator<Email>() {
            public boolean hasNext() { return visited.get() < 3; }
            public Email next() { return exactEmail(visited.incrementAndGet() == 2 ? large : small); }
        };
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE " + small.length);
            acceptSizedMessage(peer, (long) small.length);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> {
                if (asynchronous) {
                    mailer.async().sendMailsInSimpleBatch(emails).getCompletion().get(10, SECONDS);
                } else {
                    mailer.sync().sendMailsInSimpleBatch(emails);
                }
            });
            assertThat(failure).isNotNull();
            assertThat(visited).hasValue(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(1).getSubmissionReceipt().orElseThrow().getMessageSize()).isEqualTo((long) large.length);
        }
    }

    @Test
    void openConnectionCanContinueAfterSizeRejection() throws Exception {
        final byte[] small = eml("small\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE " + small.length);
            acceptSizedMessage(peer, (long) small.length);
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                assertThat(catchThrowable(() -> sender.sendMail(exactEmail(eml("huge".repeat(100) + "\r\n")))))
                        .hasCauseInstanceOf(MailTransportCompatibilityException.class);
                assertThat(sender.sendMailAndGetReceipt(exactEmail(small)).getMessageSize()).isEqualTo((long) small.length);
            });
        }
    }

    @Test
    void concurrentPooledAttemptsKeepTheirOwnSizesRecipientsAndResponses() throws Exception {
        final CountDownLatch connected = new CountDownLatch(2);
        final AtomicInteger connections = new AtomicInteger();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(2, peer -> {
            peer.greet("250-localhost\r\n" + (connections.getAndIncrement() == 0 ? "250-X-LONG " + "x".repeat(70000) + "\r\n" : "")
                    + "250 SIZE 500");
            connected.countDown();
            assertThat(connected.await(10, SECONDS)).isTrue();
            String command;
            while (!(command = readCommand(peer)).equals("QUIT")) {
                assertThat(command).matches("MAIL FROM:<sender@example.test> SIZE=[0-9]+");
                final String content = acceptMessageAfterMailFrom(peer, "", RECIPIENT);
                assertThat(command).endsWith(" SIZE=" + content.getBytes(UTF_8).length);
            }
            peer.reply("221 bye");
            peer.expectClosed();
        }); Mailer mailer = builder(server).withConnectionPoolMaxSize(2).withMailSendObserver(outcomes::add).buildMailer()) {
            final List<byte[]> content = new ArrayList<>();
            final List<MailSend<MailSubmissionReceipt>> sends = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                final byte[] bytes = new String(eml("x".repeat(index * 40) + "\r\n"), UTF_8)
                        .replace("<size@example.test>", "<size-" + index + "@example.test>").getBytes(UTF_8);
                content.add(bytes);
                sends.add(mailer.async().sendMail(exactEmail(bytes)));
            }
            for (int index = 0; index < sends.size(); index++) {
                MailSubmissionReceipt receipt;
                try {
                    receipt = sends.get(index).getCompletion().get(15, SECONDS);
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(MailSubmissionException.class);
                    receipt = ((MailSubmissionException) failure.getCause()).getSubmissionReceipt();
                }
                assertThat(receipt.getEmailId()).isEqualTo("<size-" + index + "@example.test>");
                assertThat(receipt.getMessageSize()).isEqualTo((long) content.get(index).length);
                assertThat(receipt.getServerMaximumMessageSize()).isEqualTo(500L);
                assertThat(receipt.getRecipientResults()).hasSize(1);
                if (content.get(index).length > 500) {
                    assertThat(receipt.getStatus()).isEqualTo(REJECTED);
                    assertThat(receipt.getValidUnsentRecipients()).containsExactly(RECIPIENT);
                    assertThat(receipt.getSmtpResponse()).isEmpty();
                } else {
                    assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                    assertThat(receipt.getAcceptedRecipients()).containsExactly(RECIPIENT);
                }
            }
            assertThat(outcomes).hasSize(16);
        }
    }

    @Test
    void clusterChecksTheActuallySelectedServerRatherThanTheCallingMailerEndpoint() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final byte[] content = eml("cluster limit\r\n");
        try (ScriptedSmtpServer smallServer = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 1");
            finishConnection(peer);
        }); ScriptedSmtpServer largeServer = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250-X-LONG " + "x".repeat(70000) + "\r\n250 SIZE 99999");
            acceptSizedMessage(peer, (long) content.length);
            finishConnection(peer);
        }); Mailer small = builder(smallServer).withClusterKey(cluster)
                .withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).buildMailer();
             Mailer large = builder(largeServer).withClusterKey(cluster).buildMailer()) {
            final List<MailSubmissionReceipt> receipts = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                try {
                    receipts.add(large.sync().sendMail(exactEmail(content)));
                } catch (MailSubmissionException failure) {
                    receipts.add(failure.getSubmissionReceipt());
                }
            }
            assertThat(receipts).extracting(MailSubmissionReceipt::getServerMaximumMessageSize).containsExactlyInAnyOrder(1L, 99999L);
            assertThat(receipts).extracting(MailSubmissionReceipt::getStatus).containsExactlyInAnyOrder(REJECTED, ACCEPTED);
            assertThat(receipts).allSatisfy(receipt -> assertThat(receipt.getMessageSize()).isEqualTo((long) content.length));
        }
    }

    @Test
    void failedEhloAfterTlsDoesNotReuseThePreTlsSizeLimit() throws Exception {
        final byte[] content = eml("TLS retry\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250-SIZE 1\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply("500 EHLO unavailable");
            acceptSizedMessage(peer, null);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withTransportStrategy(TransportStrategy.SMTP_TLS)
                .trustingSSLHosts("localhost").withProperty("mail.smtp.ssl.protocols", "TLSv1.2").buildMailer()) {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(exactEmail(content));
            assertThat(receipt.getServerMaximumMessageSize()).isNull();
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
        }
    }

    @Test
    void oversizeCheckDoesNotReportSelectedEnvidOrRequireTlsAsUsed() throws Exception {
        final byte[] content = eml("too large\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.startTls("250-localhost\r\n250-SIZE 1\r\n250-DSN\r\n250 REQUIRETLS");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withTransportStrategy(TransportStrategy.SMTP_TLS)
                .trustingSSLHosts("localhost").withProperty("mail.smtp.ssl.protocols", "TLSv1.2").buildMailer()) {
            final Email email = mail.emailBuilder().startingFromExactEml(content).withEnvelopeSender("sender@example.test")
                    .withEnvelopeRecipients(RECIPIENT).fixingEnvelopeId("not-issued").withTlsRequiredForOnwardDelivery().buildEmail();
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email));
            assertThat(failure).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getEnvelopeId()).isNull();
            assertThat(receipt.isRequireTlsUsed()).isFalse();
        }
    }

    @Test
    void realServerRejectionRetainsSizeFactsAndDoesNotRetry() throws Exception {
        final byte[] content = eml("server still decides\r\n");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 SIZE 99999");
            assertThat(readCommand(peer)).isEqualTo(MAIL_FROM + " SIZE=" + content.length);
            peer.reply("552 5.3.4 actual policy limit");
            peer.expect("RSET");
            peer.reply("250 reset");
            peer.expectClosed(); // A genuine SMTP failure invalidates the lease instead of returning it for reuse.
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(exactEmail(content)));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getMessageSize()).isEqualTo((long) content.length);
            assertThat(receipt.getServerMaximumMessageSize()).isEqualTo(99999L);
            assertThat(receipt.getSmtpResponse()).hasValueSatisfying(reply -> assertThat(reply.getReturnCode()).isEqualTo(552));
        }
    }

    private static byte[] eml(final String body) {
        return ("From: sender@example.test\r\nTo: visible@example.test\r\nBcc: retained@example.test\r\n"
                + "Message-ID: <size@example.test>\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body).getBytes(UTF_8);
    }

    private static String acceptSizedMessage(final SmtpConversation peer, final Long declaration) throws IOException {
        final String command = readCommand(peer);
        assertThat(command).isEqualTo(MAIL_FROM + (declaration == null ? "" : " SIZE=" + declaration));
        return acceptMessageAfterMailFrom(peer, "", RECIPIENT);
    }

    private static String acceptConvertedEightBitMessage(final SmtpConversation peer) throws IOException {
        final String command = readCommand(peer);
        assertThat(command).matches("MAIL FROM:<sender@example.test> BODY=8BITMIME SIZE=[0-9]+");
        final String content = acceptMessageAfterMailFrom(peer, "", RECIPIENT);
        assertThat(command).endsWith(" SIZE=" + content.getBytes(UTF_8).length);
        return content;
    }

    private static String readCommand(final SmtpConversation peer) throws IOException {
        String command = peer.readLine();
        while ("NOOP".equals(command)) {
            peer.reply("250 still connected");
            command = peer.readLine();
        }
        return command;
    }
}
