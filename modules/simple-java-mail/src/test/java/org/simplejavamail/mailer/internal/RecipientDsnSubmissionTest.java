package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import org.simplejavamail.mailer.internal.SmtpEnvelopeIdTest.PeerServer;
import org.simplejavamail.recipient.RecipientBuilder;
import testutil.ConfigLoaderTestHelper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.*;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.*;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.*;

/** Real pooled SMTP submissions: parameters, bytes, response ownership and lease reuse are asserted at the wire. */
@Timeout(45)
class RecipientDsnSubmissionTest {

    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String DSN = "250-localhost\r\n250 DSN";
    private static final String ADDRESS = "same@example.test";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedTypesDuplicateAddressesAndMailerOverridesKeepTheirCorrectPreference(final boolean async) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            final String content = acceptRecipients(peer, command(ADDRESS, "NEVER"), command(ADDRESS, "SUCCESS"), command(ADDRESS, "DELAY"));
            assertThat(content).doesNotContain("NOTIFY=", "ORCPT=", "ENVID=");
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, command(ADDRESS, "DELAY")); // Same lease; no previous explicit NEVER/SUCCESS.
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add)
                .withEmailOverrides(mail.emailBuilder().startingBlank().withDeliveryStatusNotificationNotifyOptions(DELAY).buildEmail()).buildMailer()) {
            final Email email = email(recipient(RecipientType.BCC), recipient(RecipientType.CC, SUCCESS), recipient(RecipientType.TO, NEVER));
            final MailSubmissionReceipt first = send(mailer, email, async);
            final MailSubmissionReceipt second = send(mailer, email(recipient(RecipientType.TO)), async);
            assertThat(first.getStatus()).isEqualTo(ACCEPTED);
            assertThat(first.getAcceptedRecipients()).containsExactly(ADDRESS, ADDRESS, ADDRESS);
            assertThat(first.getRecipientResults()).allSatisfy(result -> assertThat(result.getRcptResponse()).isPresent());
            assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(first);
            assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(second);
            assertThat(email.getDeliveryStatusNotification()).isNull();
            assertThat(email.getRecipients().get(0).getDeliveryStatusNotificationNotifyOptions()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void missingOrMalformedDsnRejectsBeforeMailAndReturnsAHealthyLease(final boolean malformed, final boolean async) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(malformed ? "250-localhost\r\n250 DSN invalid" : "250 localhost");
            expectCommand(peer, MAIL_FROM);
            acceptRecipients(peer, "RCPT TO:<" + ADDRESS + ">"); // No second connection; no unsupported DSN parameters.
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            final Throwable thrown = catchThrowable(() -> send(mailer, email(recipient(RecipientType.TO, NEVER)), async));
            final Throwable failure = async ? ((ExecutionException) thrown).getCause() : thrown;
            assertThat(failure).isInstanceOf(MailSubmissionException.class).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            final MailSubmissionReceipt receipt = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(receipt.getStatus()).isEqualTo(REJECTED);
            assertThat(receipt.getValidUnsentRecipients()).containsExactly(ADDRESS);
            assertThat(receipt.getSmtpResponse()).isEmpty();
            assertThat(receipt.getRecipientResults()).allSatisfy(result -> assertThat(result.getRcptResponse()).isEmpty());
            assertThat(send(mailer, email(recipient(RecipientType.TO)), async).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unmanagedTransportRejectsRecipientPreferencesButRetainsSharedDsn(final boolean customSocketFactory) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, "RCPT TO:<" + ADDRESS + "> NOTIFY=FAILURE"); // Caller transport: no managed ORCPT hook.
            finishConnection(peer);
        })) {
            final Properties properties = SmtpCapabilityProbeCharacterizationTest.session(false).getProperties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
            properties.setProperty("mail.smtp.dsn.notify", "FAILURE");
            final MailerGenericBuilder<?> mailerBuilder = customSocketFactory
                    ? builder(server).withProperty("mail.smtp.socketFactory", SocketFactory.getDefault()).withProperty("mail.smtp.dsn.notify", "FAILURE")
                    : mail.mailerBuilder(Session.getInstance(properties)).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1);
            try (Mailer mailer = mailerBuilder.buildMailer()) {
                final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email(recipient(RecipientType.TO, NEVER))));
                assertThat(failure).hasCauseInstanceOf(MailTransportCompatibilityException.class);
                mailer.sync().sendMail(email(recipient(RecipientType.TO)));
                assertThat(properties.getProperty("mail.smtp.dsn.notify")).isEqualTo("FAILURE");
            }
        }
    }

    @Test
    void exactEnvelopeRecipientsHavePoliciesWithoutRewritingHeadersOrBytes() throws Exception {
        final byte[] eml = ("From: sender@example.test\r\nTo: visible@example.test\r\nMessage-ID: <exact@example.test>\r\n"
                + "X-Spacing:   keep\r\n\r\nExact payload.\r\n").getBytes(US_ASCII);
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM + " RET=HDRS");
            assertThat(acceptRecipients(peer, command(ADDRESS, "NEVER"), command(ADDRESS, "SUCCESS")).getBytes(US_ASCII)).containsExactly(eml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withEmailOverrides(mail.emailBuilder().startingBlank()
                .withDeliveryStatusNotificationNotifyOptions(DELAY).buildEmail()).buildMailer()) {
            final Email exact = mail.emailBuilder().startingFromExactEml(eml)
                    .withEnvelopeRecipients(recipient(RecipientType.TO, NEVER), recipient(RecipientType.TO, SUCCESS))
                    .withDeliveryStatusNotificationReturnOption(ReturnOption.HEADERS_ONLY).buildEmail();
            assertThat(mailer.sync().sendMail(exact).getAcceptedRecipients()).containsExactly(ADDRESS, ADDRESS);
        }
    }

    @Test
    void automaticOrcptUsesTheOverriddenEnvelopeAndDoesNotEnableNotify() throws Exception {
        final String address = "test+tag=42@example.test";
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            final String content = acceptRecipients(peer, "RCPT TO:<" + address + "> ORCPT=rfc822;test+2Btag+3D42@example.test");
            assertThat(content).contains("To: " + ADDRESS).doesNotContain("ORCPT=", "NOTIFY=");
            finishConnection(peer);
        }); Mailer mailer = builder(server).buildMailer()) {
            mailer.sync().sendMail(mail.emailBuilder().copying(email(recipient(RecipientType.TO, NEVER)))
                    .withOverrideReceivers(new Recipient("Actual recipient", address, null, null)).buildEmail());
        }
    }

    @Test
    void simultaneousWorkersAndRepeatedAttemptsDoNotSharePoliciesOrResponses() throws Exception {
        final CountDownLatch connected = new CountDownLatch(2);
        final CyclicBarrier submissions = new CyclicBarrier(2);
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (PeerServer server = new PeerServer(2, peer -> {
            peer.greet(DSN);
            connected.countDown();
            assertThat(connected.await(10, SECONDS)).isTrue();
            for (int index = 0; index < 3; index++) {
                final String command = nextCommand(peer);
                submissions.await(10, SECONDS);
                assertThat(command).startsWith(MAIL_FROM + " ENVID=");
                final String preference = command.substring(command.lastIndexOf('=') + 1);
                assertThat(preference).isIn("NEVER", "SUCCESS");
                acceptRecipients(peer, command(ADDRESS, preference));
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withConnectionPoolMaxSize(2).withThreadPoolSize(2).withMailSendObserver(outcomes::add).buildMailer()) {
            for (int round = 0; round < 3; round++) {
                final List<MailSend<MailSubmissionReceipt>> sends = new ArrayList<>();
                for (final NotifyOption option : List.of(NEVER, SUCCESS)) {
                    sends.add(mailer.async().sendMail(mail.emailBuilder().copying(email(recipient(RecipientType.TO, option)))
                            .fixingEnvelopeId(option.name()).buildEmail()));
                }
                for (final MailSend<MailSubmissionReceipt> send : sends) {
                    final MailSubmissionReceipt receipt = send.getCompletion().get(15, SECONDS);
                    assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
                    assertThat(receipt.getRecipientResults()).singleElement().satisfies(result -> assertThat(result.getRcptResponse()).isPresent());
                }
            }
            assertThat(outcomes).hasSize(6);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simpleBatchesStopBeforeUntouchedRecipientsAfterPreflightFailure(final boolean async) throws Exception {
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final AtomicInteger visited = new AtomicInteger();
        final List<Email> emails = List.of(email(recipient(RecipientType.TO)), email(recipient(RecipientType.TO, NEVER)), email(recipient(RecipientType.TO)));
        final Iterable<Email> emailsVisitedOnIteration = () -> new Iterator<Email>() {
            private final Iterator<Email> delegate = emails.iterator();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Email next() {
                visited.incrementAndGet();
                return delegate.next();
            }
        };
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet("250 localhost");
            expectCommand(peer, MAIL_FROM);
            acceptRecipients(peer, "RCPT TO:<" + ADDRESS + ">");
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcomes::add).buildMailer()) {
            final Throwable failure = catchThrowable(() -> {
                if (async) {
                    mailer.async().sendMailsInSimpleBatch(emailsVisitedOnIteration).getCompletion().get(10, SECONDS);
                } else {
                    mailer.sync().sendMailsInSimpleBatch(emailsVisitedOnIteration);
                }
            });
            assertThat(failure).isNotNull();
            assertThat(visited).hasValue(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).isSuccessful()).isTrue();
            assertThat(outcomes.get(1).isSuccessful()).isFalse();
        }
    }

    @Test
    void openConnectionAndSessionFallbackDoNotRetainPreviousRecipientOverrides() throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, command(ADDRESS, "NEVER"));
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, command(ADDRESS, "FAILURE"));
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.smtp.dsn.notify", "FAILURE").buildMailer()) {
            mailer.withOpenConnection(sender -> {
                sender.sendMail(email(recipient(RecipientType.TO, NEVER)));
                sender.sendMail(email(recipient(RecipientType.TO)));
            });
            assertThat(mailer.getSession().getProperty("mail.smtp.dsn.notify")).isEqualTo("FAILURE");
        }
    }

    @Test
    void customMailerReceivesRecipientPreferencesButNoDsnHeadersAreSynthesized() throws Exception {
        final AtomicInteger called = new AtomicInteger();
        final CustomMailer customMailer = mock(CustomMailer.class);
        doAnswer(invocation -> {
            final Email email = invocation.getArgument(2);
            final MimeMessage message = invocation.getArgument(3);
            assertThat(email.getRecipients().get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
            assertThat(message.getHeader("ORCPT")).isNull();
            called.incrementAndGet();
            return null;
        }).when(customMailer).sendMessage(any(), any(), any(), any());
        try (Mailer mailer = mail.mailerBuilder().withCustomMailer(customMailer).buildMailer()) {
            assertThat(mailer.sync().sendMail(email(recipient(RecipientType.TO, NEVER))).getStatus()).isEqualTo(UNKNOWN);
            assertThat(called).hasValue(1);
        }
    }

    @Test
    void partialFailureInvalidatesItsLeaseWithoutLeakingPoliciesOrRecipientFactsToTheReplacement() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        try (PeerServer server = new PeerServer(2, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            if (connections.getAndIncrement() == 0) {
                peer.reply("250 sender accepted");
                peer.expect(command(ADDRESS, "SUCCESS"));
                peer.reply("250 2.1.5 first occurrence accepted");
                peer.expect(command(ADDRESS, "NEVER"));
                peer.reply("550 5.1.1 second occurrence rejected");
                peer.expect("DATA");
                peer.reply("354 send accepted recipient's message");
                readMessage(peer);
                peer.reply("250 2.0.0 accepted first occurrence");
                peer.expectClosed();
            } else {
                acceptRecipients(peer, "RCPT TO:<" + ADDRESS + "> ORCPT=rfc822;" + ADDRESS);
                finishConnection(peer);
            }
        }); Mailer mailer = builder(server).withProperty("mail.smtp.sendpartial", "true").buildMailer()) {
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email(recipient(RecipientType.TO, SUCCESS), recipient(RecipientType.TO, NEVER))));
            assertThat(failure).isInstanceOf(MailSubmissionException.class);
            final MailSubmissionReceipt rejected = ((MailSubmissionException) failure).getSubmissionReceipt();
            assertThat(rejected.getStatus()).isEqualTo(PARTIALLY_ACCEPTED);
            assertThat(rejected.getAcceptedRecipients()).containsExactly(ADDRESS);
            assertThat(rejected.getInvalidRecipients()).containsExactly(ADDRESS);
            assertThat(rejected.getRecipientResults()).hasSize(2);
            assertThat(rejected.getRecipientResults().get(0).getRcptResponse().orElseThrow().getReturnCode()).isEqualTo(250);
            assertThat(rejected.getRecipientResults().get(1).getRcptResponse().orElseThrow().getReturnCode()).isEqualTo(550);
            final MailSubmissionReceipt next = mailer.async().sendMail(email(recipient(RecipientType.TO))).getCompletion().get(10, SECONDS);
            assertThat(next.getStatus()).isEqualTo(ACCEPTED);
            assertThat(next.getInvalidRecipients()).isEmpty();
            assertThat(next.getRecipientResults()).singleElement().satisfies(result ->
                    assertThat(result.getRcptResponse().orElseThrow().getReturnCode()).isEqualTo(250));
        }
    }

    @Test
    void poolSizeOneObserverCanReenterWithADifferentRecipientPolicy() throws Exception {
        final AtomicInteger completed = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Mailer[] holder = new Mailer[1];
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(DSN);
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, command(ADDRESS, "NEVER"));
            readGeneratedEnvelopeId(peer, MAIL_FROM);
            acceptRecipients(peer, command(ADDRESS, "SUCCESS"));
            finishConnection(peer);
        }); Mailer mailer = builder(server).withMailSendObserver(outcome -> {
            if (completed.incrementAndGet() == 1) {
                final Throwable failure = catchThrowable(() -> holder[0].sync().sendMail(email(recipient(RecipientType.TO, SUCCESS))));
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }).buildMailer()) {
            holder[0] = mailer;
            mailer.async().sendMail(email(recipient(RecipientType.TO, NEVER))).getCompletion().get(10, SECONDS);
            assertThat(completed).hasValue(2);
            assertThat(failures).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recipientPreferencesUsePostTlsCapabilitiesNotTheEarlierGreeting(final boolean supportedAfterTls) throws Exception {
        try (PeerServer server = new PeerServer(1, peer -> {
            peer.greet(supportedAfterTls ? "250-localhost\r\n250 STARTTLS" : "250-localhost\r\n250-DSN\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 start TLS");
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply(supportedAfterTls ? "250-localhost\r\n250-AUTH LOGIN\r\n250 DSN" : "250-localhost\r\n250 AUTH LOGIN");
            peer.authenticate();
            if (supportedAfterTls) {
                readGeneratedEnvelopeId(peer, MAIL_FROM);
                acceptRecipients(peer, command(ADDRESS, "NEVER"));
            }
            finishConnection(peer);
        }); Mailer mailer = builder(server).withSMTPServer("localhost", server.port(), "test-user", "test-password")
                .withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withProperties(SmtpCapabilityProbeCharacterizationTest.session(false).getProperties())
                .trustingSSLHosts("localhost").buildMailer()) { // Trust only this loopback fixture's certificate; keep hostname verification enabled.
            final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email(recipient(RecipientType.TO, NEVER))));
            if (supportedAfterTls) {
                assertThat(failure).isNull();
            } else {
                assertThat(failure).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            }
        }
    }

    @Test
    void negotiatedInternationalizedRecipientUsesUtf8OrcptOnTheWire() throws Exception {
        final String address = "müller+tag@example.test";
        final byte[] eml = "From: sender@example.test\r\nTo: visible@example.test\r\n\r\nExact content.\r\n".getBytes(US_ASCII);
        try (PeerServer server = new PeerServer(1, UTF_8, peer -> {
            peer.greet("250-localhost\r\n250-DSN\r\n250 SMTPUTF8");
            readGeneratedEnvelopeId(peer, MAIL_FROM + " SMTPUTF8");
            assertThat(acceptRecipients(peer, "RCPT TO:<" + address + "> NOTIFY=FAILURE ORCPT=utf-8;müller\\x{2B}tag@example.test")
                    .getBytes(US_ASCII)).containsExactly(eml);
            finishConnection(peer);
        }); Mailer mailer = builder(server).withProperty("mail.mime.allowutf8", "true").buildMailer()) {
            final Recipient recipient = new RecipientBuilder().withAddress(address).withDeliveryStatusNotificationNotifyOptions(FAILURE).build();
            mailer.sync().sendMail(mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients(recipient).buildEmail());
        }
    }

    @Test
    void recipientPolicyUsesTheMailboxSpellingAfterMimeHeaderParsing() throws Exception {
        final Email email = email(new Recipient(null, "  receiver@example.test  ", RecipientType.TO, null, List.of(NEVER)));
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).disablingAllClientValidation(true).buildMailer()) {
            final Email effectiveEmail = mailer.rehearse(email).getEffectiveEmail();
            final PreparedMail prepared = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(mailer.getSession(), effectiveEmail);
            final String address = ((InternetAddress) prepared.getRecipients()[0]).getAddress();
            assertThat(address).isEqualTo("receiver@example.test");
            assertThat(prepared.getDeliveryEnvelope().getRecipientOptions()).singleElement().satisfies(recipient -> {
                assertThat(recipient.getAddress()).isEqualTo(address);
                assertThat(recipient.getNotifyOptions()).containsExactly(NEVER);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rehearsalAndValidationRejectTheSameEnvelopePolicyMismatchAsSendPreparation(final boolean processSecurity) throws Exception {
        final Email email = mail.emailBuilder().copying(email(recipient(RecipientType.TO, NEVER)))
                .withHeader("To", "additional@example.test").buildEmailCompletedWithDefaultsAndOverrides();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 1).withConnectionPoolCoreSize(0).buildMailer()) {
            final Throwable preparationFailure = catchThrowable(() -> SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(mailer.getSession(), email));
            assertThat(preparationFailure).isInstanceOf(MailTransportCompatibilityException.class)
                    .hasMessageContaining("final SMTP recipient list has 2 entries")
                    .hasMessageContaining("Email recipient list has 1")
                    .hasMessageContaining("withRecipients(...)")
                    .hasMessageContaining("withHeader(...)")
                    .hasMessageContaining("withOverrideReceivers(...)");
            assertThat(preparationFailure.getMessage()).doesNotContain(ADDRESS, "additional@example.test", "one mailbox per Recipient");
            assertThatThrownBy(() -> mailer.rehearse(email, processSecurity))
                    .hasCauseInstanceOf(MailTransportCompatibilityException.class).hasRootCauseMessage(preparationFailure.getMessage());
            assertThatThrownBy(() -> mailer.validate(email, processSecurity))
                    .hasCauseInstanceOf(MailTransportCompatibilityException.class).hasRootCauseMessage(preparationFailure.getMessage());
            assertThatThrownBy(() -> mailer.rehearse(email)).hasCauseInstanceOf(MailTransportCompatibilityException.class);
            assertThatThrownBy(() -> mailer.validate(email)).hasCauseInstanceOf(MailTransportCompatibilityException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void rehearsalAndValidationLeaveServerCapabilitiesToTheActualSend(final boolean processSecurity, final boolean exact) throws Exception {
        final byte[] eml = "From: sender@example.test\r\nTo: visible@example.test\r\n\r\nExact content.\r\n".getBytes(US_ASCII);
        final Email email = exact ? mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients(recipient(RecipientType.TO, NEVER))
                .fixingEnvelopeId("requires-dsn-on-send").buildEmail()
                : mail.emailBuilder().copying(email(recipient(RecipientType.TO, NEVER))).fixingEnvelopeId("requires-dsn-on-send").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 1).withConnectionPoolCoreSize(0).buildMailer()) {
            final MailRehearsal rehearsal = mailer.rehearse(email, processSecurity);
            assertThat(rehearsal.getEnvelopeRecipients()).containsExactly(ADDRESS);
            if (exact) {
                assertThat(rehearsal.getEmlBytes()).containsExactly(eml);
            }
            assertThat(mailer.validate(email, processSecurity)).isTrue();
        }
    }

    private MailerRegularBuilder<?> builder(final PeerServer server) {
        return mail.mailerBuilder().withSMTPServer("localhost", server.port()).withSmtpClientHostname("probe.example.test")
                .withSessionTimeout(5000).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(10000);
    }

    private Email email(final Recipient... recipients) {
        return mail.emailBuilder().startingBlank().from("sender@example.test").withRecipients(recipients)
                .withSubject("Recipient DSN test").withPlainText("Synthetic local test.").buildEmail();
    }

    private static Recipient recipient(final RecipientType type, final NotifyOption... options) {
        return new RecipientBuilder().withAddress(ADDRESS).withType(type).withDeliveryStatusNotificationNotifyOptions(options).build();
    }

    private static String command(final String address, final String notify) {
        return "RCPT TO:<" + address + "> NOTIFY=" + notify + " ORCPT=rfc822;" + address;
    }

    private static String acceptRecipients(final Conversation peer, final String... recipients) throws Exception {
        peer.reply("250 sender accepted");
        for (final String recipient : recipients) {
            peer.expect(recipient);
            peer.reply("250 2.1.5 recipient accepted");
        }
        peer.expect("DATA");
        peer.reply("354 send message");
        final String content = readMessage(peer);
        peer.reply("250 2.0.0 message accepted");
        return content;
    }

    private static String nextCommand(final Conversation peer) throws Exception {
        String command = peer.readLine();
        while ("NOOP".equals(command)) {
            peer.reply("250 alive");
            command = peer.readLine();
        }
        return command;
    }

    private static MailSubmissionReceipt send(final Mailer mailer, final Email email, final boolean async) throws Exception {
        return async ? mailer.async().sendMail(email).getCompletion().get(15, SECONDS) : mailer.sync().sendMail(email);
    }
}
