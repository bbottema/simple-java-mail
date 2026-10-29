package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryStatusEnvelopeIdTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(strings = {"order-123 ", "order-123  ", "Order+123=45 ", "order-123+20", "order-123=20"})
    void preservesTheUnencodedIdentifierAcrossMimeAndEmlImport(final String envelopeId) throws Exception {
        final String expected = DeliveryStatusNotification.builder().envelopeId(envelopeId).build().getEnvelopeId();
        final String eml = report("Original-Envelope-Id: " + envelopeId + "\r\n");
        assertReport(EmailConverter.emlToEmail(eml), expected);
        for (boolean eager : new boolean[] {false, true}) {
            assertReport(mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail(), expected);
            final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(eml.getBytes(UTF_8)));
            assertReport(EmailConverter.mimeMessageToEmail(mime, null, eager), expected);
            assertReport(mail.converter().fromMimeMessage(mime).withFetchAttachmentData(eager).toEmail(), expected);
        }
    }

    @Test
    void retainsTheIdentifierThroughSnapshotsAndExactInspection() throws Exception {
        final String envelopeId = "order-123 ";
        final byte[] source = report("Original-Envelope-Id: " + envelopeId + "\r\n").getBytes(UTF_8);
        final Email imported = mail.converter().fromEml(source).toEmail();
        assertReport(mail.emailBuilder().copying(imported).buildEmail(), envelopeId);

        final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
            output.writeObject(imported.getDeliveryStatusReport());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
            assertThat(((DeliveryStatusReport) input.readObject()).getEnvelopeId()).isEqualTo(envelopeId);
        }

        final Email exact = mail.emailBuilder().startingFromExactEml(source)
                .withEnvelopeRecipients("archive@example.org").buildEmail();
        assertReport(exact, envelopeId);
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(source);
    }

    @ParameterizedTest
    @MethodSource("repeatedIdentifiers")
    void comparesRepeatedIdentifiersWithoutChangingTheirSpelling(final String first, final String second,
            final String expected, final String warning) {
        final DeliveryStatusReport parsed = mail.converter().fromEml(report(
                "Original-Envelope-Id: " + first + "\r\nOriginal-Envelope-Id: " + second + "\r\n"))
                .toEmail().getDeliveryStatusReport();
        assertThat(parsed.getEnvelopeId()).isEqualTo(expected);
        assertThat(parsed.getWarnings()).singleElement().asString().contains(warning);
        assertThat(parsed.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
    }

    private static Stream<Arguments> repeatedIdentifiers() {
        return Stream.of(
                Arguments.of("order-123 ", "order-123 ", "order-123 ", "repeats Original-Envelope-Id"),
                Arguments.of("order-123", "order-123 ", null, "conflicting Original-Envelope-Id"),
                Arguments.of("order-123 ", "order-123  ", null, "conflicting Original-Envelope-Id"),
                Arguments.of("Order-123", "order-123", null, "conflicting Original-Envelope-Id"));
    }

    @Test
    void keepsExistingLeadingFieldWhitespaceAndUnfoldingRules() {
        assertReport(mail.converter().fromEml(report("Original-Envelope-Id:\t  order-123 \r\n")).toEmail(), "order-123 ");
        assertReport(mail.converter().fromEml(report("Original-Envelope-Id: order-\r\n 123 \r\n")).toEmail(), "order- 123 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t"})
    void keepsEmptyIdentifiersUnavailable(final String supplied) {
        final DeliveryStatusReport parsed = mail.converter().fromEml(report("Original-Envelope-Id: " + supplied + "\r\n"))
                .toEmail().getDeliveryStatusReport();
        assertThat(parsed.getEnvelopeId()).isNull();
        assertThat(parsed.getWarnings()).singleElement().asString().contains("empty or conflicting Original-Envelope-Id");
        assertThat(parsed.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
    }

    @Test
    void keepsMissingIdentifiersAbsentWithoutChangingOtherFieldInterpretation() {
        assertReport(mail.converter().fromEml(report("")).toEmail(), null);
    }

    private static void assertReport(final Email email, final String expected) {
        final DeliveryStatusReport parsed = email.getDeliveryStatusReport();
        assertThat(parsed).isNotNull();
        assertThat(parsed.getEnvelopeId()).isEqualTo(expected);
        assertThat(parsed.getWarnings()).isEmpty();
        assertThat(parsed.getReportingServer().getName()).isEqualTo("relay.example.test");
        assertThat(parsed.getRecipients()).hasSize(1);
        assertThat(parsed.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
        assertThat(parsed.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
    }

    private static String report(final String envelopeHeaders) {
        return "From: mailer-daemon@example.test\r\nTo: sender@example.test\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/report; report-type=delivery-status; boundary=report\r\n\r\n"
                + "--report\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nDelivery feedback\r\n"
                + "--report\r\nContent-Type: message/delivery-status\r\n\r\n"
                + "Reporting-MTA:  dns; relay.example.test \t\r\n" + envelopeHeaders + "\r\n"
                + "Final-Recipient: rfc822; reader@example.test\r\nAction: \tfailed \t\r\nStatus:  5.1.1 \t\r\n"
                + "\r\n--report--\r\n";
    }
}
