package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.MailTransportResult;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Exercises real provider selection and preparation, stopping before transport I/O. Expected choices are independent constants. */
class ProviderLayeredOptionsContractTest {
    @Test
    void repeatedSubmissionCapturesLegacyAndSessionOptionsWithoutRewritingContent() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (int scenario = 0; scenario < 3; scenario++) {
                final Properties properties = new Properties();
                properties.setProperty("mail." + protocol + ".dsn.notify", "DELAY");
                properties.setProperty("mail." + protocol + ".dsn.ret", "FULL");
                properties.setProperty("mail." + protocol + ".submitter", "session-identity");
                properties.setProperty("mail." + protocol + ".sendpartial", "true");
                final Session session = Session.getInstance(properties);
                final SMTPMessage original = message(session);
                original.setAllow8bitMIME(true);
                if (scenario == 0) {
                    original.setNotifyOptions(SMTPMessage.NOTIFY_FAILURE);
                    original.setReturnOption(SMTPMessage.RETURN_HDRS);
                    original.setSubmitter("legacy-identity");
                    original.setSendPartial(true);
                } else if (scenario == 2) {
                    original.setSubmitter("");
                }
                final PreparedMail replay = new AngusMailTransportAdapter().prepareForRepeatedSubmission(
                        new SMTPTransport(session, new URLName(protocol, null, -1, null, null, null)),
                        prepared(original, DeliveryEnvelope.builder().build(), ContentRequirement.NORMAL, false), new MimeMessage(original));
                final SMTPMessage actual = (SMTPMessage) replay.getMimeMessage();
                assertThat(actual.getNotifyOptions()).as("%s scenario %s", protocol, scenario)
                        .isEqualTo(scenario == 0 ? SMTPMessage.NOTIFY_FAILURE : SMTPMessage.NOTIFY_DELAY);
                assertThat(actual.getReturnOption()).isEqualTo(scenario == 0 ? SMTPMessage.RETURN_HDRS : SMTPMessage.RETURN_FULL);
                assertThat(actual.getSubmitter()).isEqualTo(scenario == 0 ? "legacy-identity" : scenario == 2 ? "" : "session-identity");
                assertThat(actual.getSendPartial()).isTrue();
                assertThat(actual.getAllow8bitMIME()).isFalse();
                assertThat(original.getAllow8bitMIME()).isTrue();
                assertThat(replay.getContentRequirement()).isEqualTo(ContentRequirement.PRESERVE_PROTECTED_CONTENT);
            }
        }
        record("field", "provider.dsnReturn");
        record("field", "provider.dsnNotify");
        record("field", "provider.sendPartial");
        record("field", "provider.submitter");
        record("case", "provider.retry-session-options");
    }

    @Test
    void emailEnvelopeChoicesOverrideLegacyValuesAtTheActualSubmissionAdapter() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final SMTPMessage original = message(session);
        original.setEnvelopeFrom("legacy@example.test");
        original.setSendPartial(false);
        original.setNotifyOptions(SMTPMessage.NOTIFY_SUCCESS);
        original.setReturnOption(SMTPMessage.RETURN_FULL);
        final DeliveryEnvelope envelope = DeliveryEnvelope.builder().withEnvelopeFrom("email@example.test")
                .withSendingToAcceptedRecipients(true).withDeliveryStatusNotification(DeliveryStatusNotification.builder()
                        .returnOption(HEADERS_ONLY).notifyOptions(FAILURE).build()).build();
        final CapturingTransport transport = new CapturingTransport(session, "smtp", Set.of("DSN"));
        final MailTransportResult result = new AngusMailTransportAdapter().sendMessage(transport,
                prepared(original, envelope, ContentRequirement.NORMAL, false));

        assertThat(result.getStatus()).isEqualTo(MailSubmissionStatus.ACCEPTED);
        assertThat(transport.submitted).isInstanceOf(SMTPMessage.class);
        final SMTPMessage actual = (SMTPMessage) transport.submitted;
        assertThat(actual.getEnvelopeFrom()).isEqualTo("email@example.test");
        assertThat(actual.getSendPartial()).isTrue();
        assertThat(actual.getNotifyOptions()).isEqualTo(SMTPMessage.NOTIFY_FAILURE);
        assertThat(actual.getReturnOption()).isEqualTo(SMTPMessage.RETURN_HDRS);
        assertThat(original.getEnvelopeFrom()).isEqualTo("legacy@example.test");
        assertThat(original.getNotifyOptions()).isEqualTo(SMTPMessage.NOTIFY_SUCCESS);

        session.getProperties().setProperty("mail.smtp.sendpartial", "true");
        final CapturingTransport conflicting = new CapturingTransport(session, "smtp", Set.of("DSN"));
        final MailTransportResult rejected = new AngusMailTransportAdapter().sendMessage(conflicting,
                prepared(original, DeliveryEnvelope.builder().withSendingToAcceptedRecipients(false).build(), ContentRequirement.NORMAL, false));
        assertThat(rejected.getFailure()).hasValueSatisfying(failure -> assertThat(failure).isInstanceOf(MailTransportCompatibilityException.class));
        assertThat(conflicting.submitted).isNull();
        record("rule", "provider.message-session-options");
        record("case", "provider.email-envelope-over-legacy");
    }

    @Test
    void recipientPreferenceReplacesSharedNotifyWhileAnOmissionKeepsIt() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final String mailbox = "same@example.test";
        final Address[] recipients = {new InternetAddress(mailbox), new InternetAddress(mailbox)};
        final DeliveryEnvelope envelope = DeliveryEnvelope.builder().withRecipientOptions(List.of(
                new DeliveryRecipient(mailbox, List.of(NEVER)), new DeliveryRecipient(mailbox, List.of()))).build();
        final PreparedMail mail = PreparedMail.builder().withMimeMessage(message(session)).withRecipients(recipients)
                .withDeliveryEnvelope(envelope).withContentRequirement(ContentRequirement.NORMAL).build();
        final AngusRecipientCommands commands = AngusRecipientCommands.prepare(new ManagedAngusTransport(session, null), mail, true);
        assertThat(commands.applyToRecipientCommand("RCPT TO:<same@example.test> NOTIFY=SUCCESS", 0))
                .contains(" NOTIFY=NEVER ").doesNotContain("NOTIFY=SUCCESS");
        assertThat(commands.applyToRecipientCommand("RCPT TO:<same@example.test> NOTIFY=SUCCESS", 1))
                .contains(" NOTIFY=SUCCESS ").doesNotContain("NOTIFY=NEVER");
        assertThat(mail.getDeliveryEnvelope().getRecipientOptions()).hasSize(2);
        record("rule", "provider.recipient-notify-precedence");
        record("adapter", "provider.recipient-dsn");
        record("case", "provider.recipient-notify");
    }

    @Test
    void contentSelectionCombinesMessageSessionAndPreservationRules() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (boolean messageChoice : new boolean[] {false, true}) {
                for (boolean sessionChoice : new boolean[] {false, true}) {
                    for (ContentRequirement requirement : new ContentRequirement[] {ContentRequirement.NORMAL, ContentRequirement.PRESERVE_PROTECTED_CONTENT}) {
                        final Properties properties = new Properties();
                        properties.setProperty("mail." + protocol + ".allow8bitmime", Boolean.toString(sessionChoice));
                        final Session session = Session.getInstance(properties);
                        final SMTPMessage message = message(session);
                        message.setAllow8bitMIME(messageChoice);
                        final AngusMailFromParameters actual = AngusContentNegotiation.resolveMailParameters(
                                new CapturingTransport(session, protocol, Set.of("8BITMIME")),
                                prepared(message, DeliveryEnvelope.builder().build(), requirement, false), protocol, null, "test", recipients());
                        assertThat(actual.getMailExtension()).as("%s message=%s session=%s requirement=%s", protocol, messageChoice, sessionChoice, requirement)
                                .isEqualTo(requirement == ContentRequirement.NORMAL && (messageChoice || sessionChoice) ? "BODY=8BITMIME" : null);
                    }
                }
            }
        }
        final Properties properties = new Properties();
        properties.setProperty("mail.mime.allowutf8", "true");
        final Session session = Session.getInstance(properties);
        final SMTPMessage message = message(session);
        final DeliveryEnvelope envelope = DeliveryEnvelope.builder().withEnvelopeFrom("müller@example.test").build();
        final CapturingTransport legacy = new CapturingTransport(session, "smtp", Set.of());
        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(legacy,
                prepared(message, envelope, ContentRequirement.NORMAL, false), "smtp", null, "test", recipients()))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("SMTPUTF8");
        assertThat(AngusContentNegotiation.resolveMailParameters(legacy,
                prepared(message, envelope, ContentRequirement.NORMAL, true), "smtp", null, "test", recipients()).isSmtpUtf8()).isFalse();
        final CapturingTransport utf8 = new CapturingTransport(session, "smtp", Set.of("SMTPUTF8"));
        assertThat(AngusContentNegotiation.resolveMailParameters(utf8,
                prepared(message, envelope, ContentRequirement.NORMAL, false), "smtp", null, "test", recipients()).isSmtpUtf8()).isTrue();
        properties.setProperty("mail.mime.allowutf8", "false");
        assertThatThrownBy(() -> AngusContentNegotiation.resolveMailParameters(utf8,
                prepared(message, envelope, ContentRequirement.NORMAL, true), "smtp", null, "test", recipients()))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("disabled");
        record("field", "provider.allow8bitMime");
        record("field", "provider.smtpUtf8");
        record("rule", "provider.content-compatibility");
        record("adapter", "provider.content-options");
        record("case", "provider.content-legacy-session-choices");
    }

    private static SMTPMessage message(final Session session) throws Exception {
        final SMTPMessage message = new SMTPMessage(session);
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setText("ASCII body");
        message.saveChanges();
        return message;
    }

    private static Address[] recipients() throws Exception {
        return new Address[] {new InternetAddress("recipient@example.test")};
    }

    private static PreparedMail prepared(final MimeMessage message, final DeliveryEnvelope envelope,
            final ContentRequirement requirement, final boolean legacySupport) throws Exception {
        return PreparedMail.builder().withMimeMessage(message).withRecipients(recipients()).withDeliveryEnvelope(envelope)
                .withContentRequirement(requirement).withLegacySmtpContentSupportEnabled(legacySupport).build();
    }

    private static final class CapturingTransport extends SMTPTransport {
        private final Set<String> extensions;
        private Message submitted;

        private CapturingTransport(final Session session, final String protocol, final Set<String> extensions) {
            super(session, new URLName(protocol, null, -1, null, null, null));
            this.extensions = extensions;
        }

        @Override public boolean supportsExtension(final String extension) { return extensions.contains(extension); }
        @Override public void sendMessage(final Message message, final Address[] addresses) { submitted = message; }
    }
}
