package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.NewsAddress;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSSLTransport;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import javax.net.SocketFactory;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;

class AngusMailTransportAdapterTest {

    @ParameterizedTest
    @CsvSource({"plain-id, plain-id", "order +42=, order+20+2B42+3D", "id RET=FULL, id+20RET+3DFULL", "+20, +2B20"})
    void envelopeIdentifiersUseAngusXtextWithoutChangingTheReportedValue(final String identifier, final String encodedIdentifier) throws Exception {
        final AngusMailTransportAdapter.AngusSmtpMessage facade = new AngusMailTransportAdapter.AngusSmtpMessage(
                preparedMail(), "XTEST=keep", identifier, null, false);

        assertThat(facade.getMailExtension()).isEqualTo("XTEST=keep ENVID=" + encodedIdentifier);
        assertThat(facade.getEnvelopeIdUsed()).isEqualTo(identifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps", "message"})
    void conflictingIdentifiersExplainTheConfigurationFixBeforeSending(final String configurationSource) throws Exception {
        final SMTPMessage original = new SMTPMessage(message("body"));
        original.getSession().getProperties().setProperty("mail.smtp.mailextension", "XTEST=keep ENVID=smtp-id");
        original.getSession().getProperties().setProperty("mail.smtps.mailextension", "XTEST=keep ENVID=smtps-id");
        if ("message".equals(configurationSource)) {
            original.setMailExtension("XTEST=keep ENVID=message-id");
        }
        final String protocol = "smtps".equals(configurationSource) ? "smtps" : "smtp";
        final SMTPTransport transport = transportRejectingSubmission(original.getSession(), protocol);
        final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().envelopeId("fixed-selection").build();

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                new PreparedMail(original, recipients(), new DeliveryEnvelope(null, notification), ContentRequirement.NORMAL));

        assertThat(result.getFailure()).hasValueSatisfying(failure -> {
            assertThat(failure)
                    .hasMessageContaining("An envelope identifier is configured twice")
                    .hasMessageContaining("Remove the ENVID=... entry from that setting and keep fixingEnvelopeId(...)")
                    .hasMessageContaining("Leave any other entries unchanged")
                    .hasMessageContaining("No message was submitted")
                    .hasMessageNotContaining("fixed-selection")
                    .hasMessageNotContaining("ENVID=smtp-id")
                    .hasMessageNotContaining("ENVID=smtps-id")
                    .hasMessageNotContaining("ENVID=message-id");
            assertSelectedConfigurationSource(failure, configurationSource, protocol);
        });
        assertThat(result.getEnvelopeId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "XTEST=message"})
    void explicitMessageExtensionStillOverridesConflictingSessionProperties(final String messageExtension) throws Exception {
        final SMTPMessage original = new SMTPMessage(message("body"));
        original.getSession().getProperties().setProperty("mail.smtp.mailextension", "ENVID=ignored-session-id");
        original.setMailExtension(messageExtension);
        final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().envelopeId("fixed-selection").build();

        try (CommandRecordingTransport transport = new CommandRecordingTransport(original.getSession())) {
            transport.connect("localhost", 25, null, null);
            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(original, recipients(), new DeliveryEnvelope(null, notification), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).containsSame(transport.writeFailure);
            assertThat(result.getEnvelopeId()).isEqualTo("fixed-selection");
            assertThat(transport.commands).containsExactly("MAIL FROM:<sender@example.com>"
                    + (messageExtension.isEmpty() ? "" : " " + messageExtension) + " ENVID=fixed-selection");
            assertThat(original.getMailExtension()).isEqualTo(messageExtension);
            assertThat(original.getSession().getProperty("mail.smtp.mailextension")).isEqualTo("ENVID=ignored-session-id");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noRecipientsDoesNotReportASelectedEnvelopeId(final boolean fixedIdentifier) throws Exception {
        final MimeMessage message = message("body");
        final DeliveryStatusNotification dsn = fixedIdentifier ? DeliveryStatusNotification.builder().envelopeId("not-submitted").build() : null;
        try (CommandRecordingTransport transport = new CommandRecordingTransport(message.getSession())) {
            transport.connect("localhost", 25, null, null);
            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, new Address[0], new DeliveryEnvelope(null, dsn), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure).hasMessage("No recipient addresses"));
            assertThat(result.getEnvelopeId()).isNull();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void requireTlsIsReportedOnlyAfterAngusBuildsTheMailFromCommand() throws Exception {
        final MimeMessage message = tlsMessage("body");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult attempted = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(attempted.getFailure()).containsSame(transport.writeFailure);
            assertThat(attempted.isRequireTlsUsed()).isTrue();
            assertThat(transport.commands).singleElement().asString().contains(" REQUIRETLS");

            final MailTransportResult notAttempted = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, new Address[0], requireTlsEnvelope(), ContentRequirement.NORMAL));
            assertThat(notAttempted.getFailure()).hasValueSatisfying(failure -> assertThat(failure).hasMessage("No recipient addresses"));
            assertThat(notAttempted.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).hasSize(1);
        }
    }

    @Test
    void requireTlsComposesWithDsnMailFromParameters() throws Exception {
        final MimeMessage message = tlsMessage("body");
        final DeliveryStatusNotification dsn = DeliveryStatusNotification.builder()
                .returnOption(HEADERS_ONLY)
                .envelopeId("correlation-id")
                .build();
        final DeliveryEnvelope envelope = new DeliveryEnvelope(null, dsn, List.of(), true);
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), envelope, ContentRequirement.NORMAL));

            assertThat(result.getEnvelopeId()).isEqualTo("correlation-id");
            assertThat(result.isRequireTlsUsed()).isTrue();
            assertThat(transport.commands).singleElement().asString()
                    .contains(" RET=HDRS", " ENVID=correlation-id", " REQUIRETLS");
        }
    }

    @Test
    void requireTlsDoesNotLeakToTheNextMessageOnTheSameTransport() throws Exception {
        final MimeMessage message = tlsMessage("body");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);
            final AngusMailTransportAdapter adapter = new AngusMailTransportAdapter();

            final MailTransportResult required = adapter.sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));
            final MailTransportResult ordinary = adapter.sendMessage(transport,
                    new PreparedMail(message, recipients(), new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));

            assertThat(required.isRequireTlsUsed()).isTrue();
            assertThat(ordinary.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).hasSize(2);
            assertThat(transport.commands.get(0)).contains(" REQUIRETLS");
            assertThat(transport.commands.get(1)).doesNotContain(" REQUIRETLS");
        }
    }

    @Test
    void missingRequireTlsCapabilityFailsBeforeMailFrom() throws Exception {
        final MimeMessage message = tlsMessage("body");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), false)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("does not advertise usable REQUIRETLS")
                    .hasMessageContaining("clear the requirement for this email")
                    .hasMessageContaining("No message was submitted"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void plaintextConnectionFailsBeforeMailFrom() throws Exception {
        final MimeMessage message = message("body");
        message.getSession().getProperties().setProperty("mail.smtp.ssl.checkserveridentity", "true");
        try (CommandRecordingTransport transport = new CommandRecordingTransport(message.getSession())) {
            transport.connect("localhost", 25, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("SMTP connection is not using TLS")
                    .hasMessageContaining("opportunistic SMTP successfully negotiates STARTTLS")
                    .hasMessageContaining("No message was submitted"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void disabledIdentityVerificationFailsBeforeMailFrom() throws Exception {
        final MimeMessage message = tlsMessage("body");
        message.getSession().getProperties().setProperty("mail.smtps.ssl.checkserveridentity", "false");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("server identity verification is disabled")
                    .hasMessageContaining("verifyingServerIdentity(true)"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", " * "})
    void trustAllConfigurationFailsBeforeMailFrom(final String trustedHosts) throws Exception {
        final MimeMessage message = tlsMessage("body");
        message.getSession().getProperties().setProperty("mail.smtps.ssl.trust", trustedHosts);
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("trust every TLS certificate")
                    .hasMessageContaining("Remove trustingAllHosts(true)"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void opaqueTlsSocketFactoryFailsBeforeMailFrom() throws Exception {
        final MimeMessage message = tlsMessage("body");
        message.getSession().getProperties().put("mail.smtps.ssl.socketFactory", SocketFactory.getDefault());
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("custom socket factory owns TLS certificate handling")
                    .hasMessageContaining("clear the email's REQUIRETLS requirement"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void typedRequirementRejectsParameterizedRawRequireTlsBeforeMailFrom() throws Exception {
        final MimeMessage message = tlsMessage("body");
        message.getSession().getProperties().setProperty("mail.smtps.mailextension", "XTRACE=1 REQUIRETLS=invalid");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), requireTlsEnvelope(), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure)
                    .hasMessageContaining("invalid REQUIRETLS=... parameter")
                    .hasMessageContaining("Simple Java Mail will add the valid REQUIRETLS flag")
                    .hasMessageContaining("No message was submitted"));
            assertThat(result.isRequireTlsUsed()).isFalse();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void rawRequireTlsIsPreservedAndReportedWithoutClaimingTypedPolicyValidation() throws Exception {
        final MimeMessage message = tlsMessage("body");
        message.getSession().getProperties().setProperty("mail.smtps.mailextension", "XTRACE=1 REQUIRETLS");
        try (CommandRecordingTlsTransport transport = new CommandRecordingTlsTransport(message.getSession(), true)) {
            transport.connect("localhost", 465, null, null);

            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));

            assertThat(result.isRequireTlsUsed()).isTrue();
            assertThat(transport.commands).singleElement().asString().contains("XTRACE=1 REQUIRETLS");
        }
    }

    @Test
    void unparseableSenderDoesNotReportAnEnvelopeIdBeforeMailFrom() throws Exception {
        final MimeMessage message = message("body");
        message.setHeader("From", "broken <");
        try (CommandRecordingTransport transport = new CommandRecordingTransport(message.getSession())) {
            transport.connect("localhost", 25, null, null);
            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, recipients(), new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure).isInstanceOf(AddressException.class));
            assertThat(result.getEnvelopeId()).isNull();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void unsupportedRecipientTypeDoesNotReportAnEnvelopeIdBeforeMailFrom() throws Exception {
        final MimeMessage message = message("body");
        try (CommandRecordingTransport transport = new CommandRecordingTransport(message.getSession())) {
            transport.connect("localhost", 25, null, null);
            final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                    new PreparedMail(message, new Address[]{new NewsAddress("example.group")}, new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));

            assertThat(result.getFailure()).hasValueSatisfying(failure -> assertThat(failure).hasMessageContaining("is not an InternetAddress"));
            assertThat(result.getEnvelopeId()).isNull();
            assertThat(transport.commands).isEmpty();
        }
    }

    @Test
    void commandWriteFailureReportsItsIdentifierButTheNextLocalFailureDoesNotReuseIt() throws Exception {
        final MimeMessage message = message("body");
        final AngusMailTransportAdapter adapter = new AngusMailTransportAdapter();
        try (CommandRecordingTransport transport = new CommandRecordingTransport(message.getSession())) {
            transport.connect("localhost", 25, null, null);
            final MailTransportResult attempted = adapter.sendMessage(transport,
                    new PreparedMail(message, recipients(), new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));
            assertThat(attempted.getFailure()).containsSame(transport.writeFailure);
            assertThat(attempted.getEnvelopeId()).isNotNull();
            assertThat(transport.commands).containsExactly("MAIL FROM:<sender@example.com> ENVID=" + attempted.getEnvelopeId());

            final MailTransportResult notAttempted = adapter.sendMessage(transport,
                    new PreparedMail(message, new Address[0], new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));
            assertThat(notAttempted.getFailure()).hasValueSatisfying(failure -> assertThat(failure).hasMessage("No recipient addresses"));
            assertThat(notAttempted.getEnvelopeId()).isNull();
            assertThat(transport.commands).hasSize(1);
        }
    }

    @Test
    void automaticFacadeRetainsExistingAngusOptionsAndDoesNotMutateTheMessage() throws Exception {
        final SMTPMessage original = new SMTPMessage(message("body"));
        original.setEnvelopeFrom("original-envelope@example.test");
        original.setNotifyOptions(SMTPMessage.NOTIFY_DELAY);
        original.setReturnOption(SMTPMessage.RETURN_FULL);
        original.setAllow8bitMIME(true);
        original.setSendPartial(true);
        original.setSubmitter("original-submitter");
        original.setMailExtension("XTEST=original");
        final SMTPTransport transport = new SMTPTransport(original.getSession(), null) {
            @Override
            public boolean supportsExtension(final String extension) {
                return "DSN".equals(extension);
            }

            @Override
            public void sendMessage(final Message message, final Address[] addresses) {
                final SMTPMessage facade = (SMTPMessage) message;
                assertThat(facade).isNotSameAs(original);
                assertThat(facade.getEnvelopeFrom()).isEqualTo(original.getEnvelopeFrom());
                assertThat(facade.getNotifyOptions()).isEqualTo(original.getNotifyOptions());
                assertThat(facade.getReturnOption()).isEqualTo(original.getReturnOption());
                assertThat(facade.getAllow8bitMIME()).isTrue();
                assertThat(facade.getSendPartial()).isTrue();
                assertThat(facade.getSubmitter()).isEqualTo(original.getSubmitter());
                assertThat(facade.getMailExtension()).startsWith("XTEST=original ENVID=");
            }
        };
        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                new PreparedMail(original, recipients(), new DeliveryEnvelope(null, null), ContentRequirement.NORMAL));
        assertThat(result.getEnvelopeId()).isNotNull();
        assertThat(original.getMailExtension()).isEqualTo("XTEST=original");
    }

    @Test
    void directlyConstructedTransportWithoutUrlKeepsItsDefaultProtocolForEnvid() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.mailextension", "XTEST=direct");
        final Session session = Session.getInstance(properties);
        final SMTPTransport transport = new SMTPTransport(session, null) {
            @Override
            public boolean supportsExtension(final String extension) {
                return "DSN".equals(extension);
            }

            @Override
            public void sendMessage(final Message message, final Address[] addresses) {
                assertThat(((SMTPMessage) message).getMailExtension()).isEqualTo("XTEST=direct ENVID=direct-id");
            }
        };
        final DeliveryStatusNotification dsn = DeliveryStatusNotification.builder().envelopeId("direct-id").build();
        final PreparedMail preparedMail = new PreparedMail(new MimeMessage(session), recipients(), new DeliveryEnvelope(null, dsn), ContentRequirement.NORMAL);

        assertThat(new AngusMailTransportAdapter().sendMessage(transport, preparedMail).getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
    }

    @Test
    void envidUsesTheSelectedSmtpsExtensionWithoutMutatingEitherProtocolConfiguration() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtps.mailextension", "XTEST=secure");
        properties.setProperty("mail.smtp.mailextension", "ENVID=unused-protocol");
        final MimeMessage message = new MimeMessage(Session.getInstance(properties));
        final DeliveryStatusNotification dsn = DeliveryStatusNotification.builder().envelopeId("smtps-id").build();
        final PreparedMail preparedMail = new PreparedMail(message, recipients(), new DeliveryEnvelope(null, dsn), ContentRequirement.NORMAL);

        final SMTPTransport transport = new SMTPSSLTransport(message.getSession(), new URLName("smtps", null, -1, null, null, null)) {
            @Override
            public boolean supportsExtension(final String extension) {
                return "DSN".equals(extension);
            }

            @Override
            public void sendMessage(final Message sending, final Address[] addresses) {
                assertThat(((SMTPMessage) sending).getMailExtension()).isEqualTo("XTEST=secure ENVID=smtps-id");
            }
        };
        assertThat(new AngusMailTransportAdapter().sendMessage(transport, preparedMail).getEnvelopeId()).isEqualTo("smtps-id");
        assertThat(properties.getProperty("mail.smtps.mailextension")).isEqualTo("XTEST=secure");
        assertThat(properties.getProperty("mail.smtp.mailextension")).isEqualTo("ENVID=unused-protocol");
    }

    @Test
    void facadeMapsEnvelopeAndDsnWithoutChangingWireBytes() throws Exception {
        final MimeMessage message = message("body");
        final byte[] expected = bytes(message);
        final DeliveryStatusNotification dsn = DeliveryStatusNotification.builder().returnOption(HEADERS_ONLY)
                .notifyOptions(FAILURE, DELAY).envelopeId("protected+42").build();
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope("bounce@example.com", dsn, List.of(new DeliveryRecipient(
                        ((InternetAddress) recipients()[0]).getAddress(), List.of(DeliveryStatusNotification.NotifyOption.NEVER)))),
                ContentRequirement.PRESERVE_PROTECTED_CONTENT);
        final AngusRecipientCommands recipientCommands = AngusRecipientCommands.prepare(new ManagedAngusTransport(message.getSession(), null), preparedMail, true);

        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail, null, "protected+42", recipientCommands, false);

        assertThat(facade.getEnvelopeFrom()).isEqualTo("bounce@example.com");
        assertThat(facade.getNotifyOptions()).isEqualTo(SMTPMessage.NOTIFY_FAILURE | SMTPMessage.NOTIFY_DELAY);
        assertThat(facade.getReturnOption()).isEqualTo(SMTPMessage.RETURN_HDRS);
        assertThat(facade.getMailExtension()).isEqualTo("ENVID=protected+2B42");
        assertThat(facade.getRecipientCommands()).isSameAs(recipientCommands);
        assertThat(bytes(facade)).containsExactly(expected);
    }

    @Test
    void stableFacadeSuppressesAngusEightBitTraversalAndSaveChanges() throws Exception {
        final MimeMessage message = message("body");
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_PROTECTED_CONTENT);
        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail, null, null, null, false);
        final byte[] before = bytes(facade);

        facade.saveChanges();

        assertThat(facade.getAllow8bitMIME()).isFalse();
        assertThat(facade.isMimeType("text/*")).isFalse();
        assertThat(facade.isMimeType("multipart/*")).isFalse();
        assertThat(bytes(facade)).containsExactly(before);
    }

    @Test
    void allBytesFacadeRetainsHeadersNormallyIgnoredByAngus() throws Exception {
        final byte[] exactEml = ("From: sender@example.com\r\n"
                + "To: visible@example.com\r\n"
                + "Bcc: hidden@example.com\r\n"
                + "Content-Length: 4\r\n"
                + "\r\nbody\r\n").getBytes(StandardCharsets.US_ASCII);
        final FinalizedMimeMessage message = FinalizedMimeMessage.fromExactMessageBytes(
                Session.getInstance(new Properties()), exactEml);
        final PreparedMail preparedMail = new PreparedMail(message, recipients(),
                new DeliveryEnvelope(null, null), ContentRequirement.PRESERVE_ALL_BYTES);
        final AngusMailTransportAdapter.AngusSmtpMessage facade =
                new AngusMailTransportAdapter.AngusSmtpMessage(preparedMail, null, null, null, false);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        facade.writeTo(output, new String[] {"Bcc", "Content-Length"});

        assertThat(output.toByteArray()).containsExactly(exactEml);
    }

    @Test
    void failedSendDoesNotReuseThePreviousSubmissionResponse() throws Exception {
        final MessagingException failure = new MessagingException("connection dropped after DATA");
        final StaleResponseTransport transport = new StaleResponseTransport(failure);
        final PreparedMail preparedMail = new PreparedMail(message("body"), recipients(),
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail);

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void normalReturnWithoutFreshProviderFactsDoesNotReuseThePreviousResponse() throws Exception {
        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(new StaleResponseTransport(null), preparedMail());
        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getRecipientResults()).allSatisfy(recipient -> assertThat(recipient.getRcptResponse()).isEmpty());
    }

    @Test
    void emptyProviderResponseIsNotReportedAsAnSmtpRejection() throws Exception {
        final MessagingException failure = new MessagingException("Exception reading response");
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, 0, "");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void missingFinalDataReplyClearsAmbiguousRecipientsButRetainsKnownInvalidRecipients() throws Exception {
        final Address ambiguousRecipient = new InternetAddress("ambiguous@example.com");
        final Address invalidRecipient = new InternetAddress("invalid@example.com");
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                ".", -1, "[EOF]", null, null, new Address[]{ambiguousRecipient}, new Address[]{invalidRecipient});
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, -1, "[EOF]");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail(ambiguousRecipient, invalidRecipient));

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.UNKNOWN);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getAcceptedRecipients()).isEmpty();
        assertThat(result.getValidUnsentRecipients()).isEmpty();
        assertThat(result.getInvalidRecipients()).containsExactly(invalidRecipient);
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void explicitFinalDataRejectionRemainsRejected() throws Exception {
        final Address unsentRecipient = new InternetAddress("unsent@example.com");
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                ".", 550, "550 message rejected", null, null, new Address[]{unsentRecipient}, null);
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, 550, "550 message rejected");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail(unsentRecipient));

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
        assertThat(result.getSmtpResponse()).hasValueSatisfying(response -> {
            assertThat(response.getReturnCode()).isEqualTo(550);
            assertThat(response.getResponse()).isEqualTo("550 message rejected");
        });
        assertThat(result.getValidUnsentRecipients()).containsExactly(unsentRecipient);
        assertThat(result.getFailure()).containsSame(failure);
    }

    @Test
    void commandSpecificEofBeforeDataRemainsRejected() throws Exception {
        final Address unsentRecipient = recipients()[0];
        final SMTPSendFailedException failure = new SMTPSendFailedException(
                "MAIL FROM:<sender@example.com>", -1, "[EOF]", null, null, new Address[]{unsentRecipient}, null);
        final ChangedResponseTransport transport = new ChangedResponseTransport(failure, -1, "[EOF]");

        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport, preparedMail());

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.REJECTED);
        assertThat(result.getSmtpResponse()).isEmpty();
        assertThat(result.getValidUnsentRecipients()).containsExactly(unsentRecipient);
    }

    private static MimeMessage message(final String body) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, recipients());
        message.setSubject("subject", StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        message.saveChanges();
        return message;
    }

    private static Address[] recipients() throws Exception {
        return new Address[]{new InternetAddress("receiver@example.com")};
    }

    private static MimeMessage tlsMessage(final String body) throws Exception {
        final MimeMessage message = message(body);
        message.getSession().getProperties().setProperty("mail.smtps.ssl.checkserveridentity", "true");
        return message;
    }

    private static DeliveryEnvelope requireTlsEnvelope() {
        return new DeliveryEnvelope(null, null, List.of(), true);
    }

    private static SMTPTransport transportRejectingSubmission(final Session session, final String protocol) {
        return new SMTPTransport(session, new URLName(protocol, null, -1, null, null, null)) {
            @Override
            public boolean supportsExtension(final String extension) {
                return "DSN".equals(extension);
            }

            @Override
            public void sendMessage(final Message message, final Address[] addresses) {
                throw new AssertionError("Conflicting identifiers must be rejected before submission");
            }
        };
    }

    private static void assertSelectedConfigurationSource(final Throwable failure, final String configurationSource, final String protocol) {
        if ("message".equals(configurationSource)) {
            assertThat(failure).hasMessageContaining("in SMTPMessage.setMailExtension(...)")
                    .hasMessageNotContaining("mail.smtp.mailextension")
                    .hasMessageNotContaining("mail.smtps.mailextension");
            return;
        }
        final String otherProtocol = "smtp".equals(protocol) ? "smtps" : "smtp";
        assertThat(failure).hasMessageContaining("in the Mailer property 'mail." + protocol + ".mailextension'")
                .hasMessageNotContaining("mail." + otherProtocol + ".mailextension")
                .hasMessageNotContaining("SMTPMessage");
    }

    private static PreparedMail preparedMail(final Address... envelope) throws Exception {
        return new PreparedMail(message("body"), envelope.length == 0 ? recipients() : envelope,
                new DeliveryEnvelope(null, null), ContentRequirement.NORMAL);
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    /** Runs Angus's actual preflight and MAIL FROM construction, stopping at its command-write boundary without a socket. */
    private static final class CommandRecordingTransport extends SMTPTransport {
        private final List<String> commands = new ArrayList<>();
        private final MessagingException writeFailure = new MessagingException("Synthetic command-write failure");

        private CommandRecordingTransport(final Session session) {
            super(session, null);
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            return true;
        }

        @Override
        public boolean supportsExtension(final String extension) {
            return "DSN".equals(extension);
        }

        @Override
        protected void sendCommand(final String command) throws MessagingException {
            commands.add(command);
            throw writeFailure;
        }
    }

    /** Runs Angus's implicit-TLS MAIL FROM construction without opening a socket. */
    private static final class CommandRecordingTlsTransport extends SMTPSSLTransport {
        private final List<String> commands = new ArrayList<>();
        private final MessagingException writeFailure = new MessagingException("Synthetic command-write failure");
        private final boolean requireTlsSupported;

        private CommandRecordingTlsTransport(final Session session, final boolean requireTlsSupported) {
            super(session, new URLName("smtps", null, -1, null, null, null));
            this.requireTlsSupported = requireTlsSupported;
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            return true;
        }

        @Override
        public synchronized boolean isSSL() {
            return true;
        }

        @Override
        public boolean supportsExtension(final String extension) {
            return "DSN".equals(extension) || requireTlsSupported && "REQUIRETLS".equals(extension);
        }

        @Override
        protected void sendCommand(final String command) throws MessagingException {
            commands.add(command);
            throw writeFailure;
        }
    }

    private static final class StaleResponseTransport extends SMTPTransport {
        private final MessagingException failure;

        private StaleResponseTransport(final MessagingException failure) {
            super(Session.getInstance(new Properties()), new URLName("smtp", null, -1, null, null, null));
            this.failure = failure;
        }

        @Override
        public synchronized int getLastReturnCode() {
            return 250;
        }

        @Override
        public synchronized String getLastServerResponse() {
            return "250 previous message accepted";
        }

        @Override
        public synchronized void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ChangedResponseTransport extends SMTPTransport {
        private final MessagingException failure;
        private final int failureReturnCode;
        private final String failureResponse;
        private int returnCode = 250;
        private String serverResponse = "250 previous message accepted";

        private ChangedResponseTransport(final MessagingException failure,
                                         final int failureReturnCode,
                                         final String failureResponse) {
            super(Session.getInstance(new Properties()), new URLName("smtp", null, -1, null, null, null));
            this.failure = failure;
            this.failureReturnCode = failureReturnCode;
            this.failureResponse = failureResponse;
        }

        @Override
        public synchronized int getLastReturnCode() {
            return returnCode;
        }

        @Override
        public synchronized String getLastServerResponse() {
            return serverResponse;
        }

        @Override
        public synchronized void sendMessage(final Message message, final Address[] addresses) throws MessagingException {
            returnCode = failureReturnCode;
            serverResponse = failureResponse;
            throw failure;
        }
    }
}
