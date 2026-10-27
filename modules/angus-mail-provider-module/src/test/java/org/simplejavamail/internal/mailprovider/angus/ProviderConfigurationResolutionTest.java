package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Provider precedence is modeled independently of the shared resolver and its declaration representation. */
class ProviderConfigurationResolutionTest {
    @Test
    void retryCaptureRetainsExplicitMailExtensionsIncludingEmptyText() throws Exception {
        final String[] choices = {null, "", "RET=FULL", "ENVID=caller-owned"};
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String propertyChoice : choices) {
                for (String messageChoice : choices) {
                    final Properties properties = new Properties();
                    if (propertyChoice != null) { properties.setProperty("mail." + protocol + ".mailextension", propertyChoice); }
                    final Session session = Session.getInstance(properties);
                    final SMTPMessage original = new SMTPMessage(session);
                    original.setFrom(new InternetAddress("sender@example.test"));
                    original.setMailExtension(messageChoice);
                    original.setText("Body");
                    original.saveChanges();
                    final SMTPTransport transport = new SMTPTransport(session, new URLName(protocol, null, -1, null, null, null));
                    final PreparedMail replay = new AngusMailTransportAdapter().prepareForRepeatedSubmission(transport,
                            prepared(original, null), new MimeMessage(original));
                    assertThat(((SMTPMessage) replay.getMimeMessage()).getMailExtension())
                            .as("%s message=%s property=%s", protocol, messageChoice, propertyChoice)
                            .isEqualTo(messageChoice == null ? propertyChoice : messageChoice);
                    assertThat(original.getMailExtension()).isEqualTo(messageChoice);
                }
            }
        }
        record("field", "provider.mailExtension");
        record("rule", "provider.message-session-options");
        record("adapter", "provider.mail-extension");
        record("case", "provider.mail-extension");
    }

    @Test
    void envelopeSenderKeepsEverySourceAndEmptyValueBoundary() throws Exception {
        final String[] emailChoices = {null, "", "email@example.test"};
        final String[] legacyChoices = {null, "", "legacy@example.test"};
        final String[] sessionChoices = {null, "", "session@example.test"};
        final String[] headerChoices = {null, "header@example.test"};
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String email : emailChoices) {
                for (String legacy : legacyChoices) {
                    for (String configured : sessionChoices) {
                        for (String header : headerChoices) {
                            final Properties properties = new Properties();
                            properties.setProperty("mail.from", "local@example.test");
                            if (configured != null) { properties.setProperty("mail." + protocol + ".from", configured); }
                            final SMTPMessage message = new SMTPMessage(Session.getInstance(properties));
                            message.setEnvelopeFrom(legacy);
                            if (header != null) { message.setFrom(new InternetAddress(header)); }
                            final String expected = email != null && !email.isEmpty() ? email
                                    : email == null && legacy != null && !legacy.isEmpty() ? legacy
                                    : configured != null && !configured.isEmpty() ? configured
                                    : header != null ? header : "local@example.test";
                            assertThat(AngusContentNegotiation.resolveEnvelopeSender(prepared(message, email), protocol))
                                    .as("%s email=%s legacy=%s session=%s header=%s", protocol, email, legacy, configured, header)
                                    .isEqualTo(expected);
                        }
                    }
                }
            }
        }
        record("field", "provider.envelopeFrom");
        record("rule", "provider.envelope-precedence");
        record("rule", "provider.explicit-empty-envelope-suppresses-legacy");
        record("adapter", "provider.envelope-sender");
        record("case", "provider.envelope-sender");
    }

    @Test
    void explicitEnvelopeDoesNotReadFallbackHeadersOrResolveALocalAddress() throws Exception {
        final SMTPMessage message = new SMTPMessage(Session.getInstance(new Properties())) {
            @Override public Address[] getFrom() throws MessagingException {
                throw new MessagingException("Unused From fallback must remain unread");
            }
        };
        assertThat(AngusContentNegotiation.resolveEnvelopeSender(prepared(message, "chosen@example.test"), "smtp"))
                .isEqualTo("chosen@example.test");
        message.setEnvelopeFrom("legacy@example.test");
        assertThat(AngusContentNegotiation.resolveEnvelopeSender(prepared(message, null), "smtp"))
                .isEqualTo("legacy@example.test");
        message.getSession().getProperties().setProperty("mail.smtp.from", "session@example.test");
        assertThat(AngusContentNegotiation.resolveEnvelopeSender(prepared(message, ""), "smtp"))
                .isEqualTo("session@example.test");
        record("rule", "provider.lazy-fallbacks");
        record("case", "provider.lazy-envelope-fallbacks");
    }

    private static PreparedMail prepared(final MimeMessage message, final String sender) {
        return PreparedMail.builder().withMimeMessage(message).withRecipients(new Address[0])
                .withDeliveryEnvelope(DeliveryEnvelope.builder().withEnvelopeFrom(sender).build())
                .withContentRequirement(ContentRequirement.NORMAL).build();
    }
}
