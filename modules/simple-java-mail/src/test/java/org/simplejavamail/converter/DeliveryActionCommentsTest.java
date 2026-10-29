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
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryActionCommentsTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @MethodSource("commentedActions")
    void readsOneRecognizedActionWithValidExternalComments(final String supplied, final DeliveryAction expected) {
        assertFacts(mail.converter().fromEml(report(supplied)).toEmail(), expected);
    }

    private static Stream<Arguments> commentedActions() {
        return Stream.of(DeliveryAction.DELIVERED, DeliveryAction.DELAYED, DeliveryAction.FAILED,
                        DeliveryAction.RELAYED, DeliveryAction.EXPANDED)
                .flatMap(action -> {
                    final String token = action.name().toLowerCase(Locale.ROOT);
                    return Stream.of(
                            token + " (recipient information)",
                            "(reported) " + token,
                            "(outer (nested)) " + action.name() + " (detail)",
                            token + " (escaped \\) bracket)",
                            token + "\r\n\t(folded comment)",
                            " \t" + token + " \t(comment)\t ",
                            "(first) " + token + " (second) (third)")
                            .map(supplied -> Arguments.of(supplied, action));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "UNKNOWN", "completed", "failed delivered", "failed; ignored",
            "\"failed\"", "failed (unterminated", "failed (nested (unterminated)", "failed (comment))",
            "failed extra (comment)", "(comment only)", "fa(comment)iled", "failed [comment]",
            "failed (dangling \\", "failed (comment) extra"})
    void keepsUnknownOrMalformedActionsUnavailable(final String supplied) {
        final DeliveryStatusReport parsed = mail.converter().fromEml(report(supplied)).toEmail().getDeliveryStatusReport();
        assertThat(parsed.getRecipients().get(0).getAction()).as(supplied).isEqualTo(DeliveryAction.UNKNOWN);
        assertThat(parsed.getWarnings()).as(supplied).isNotEmpty();
        assertThat(parsed.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
    }

    @Test
    void retainsExistingMissingAndDuplicateFieldRules() {
        final String source = report("failed (reported)");
        final DeliveryStatusReport missing = mail.converter().fromEml(source.replace("Action: failed (reported)\r\n", ""))
                .toEmail().getDeliveryStatusReport();
        assertThat(missing.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.UNKNOWN);
        assertThat(missing.getWarnings()).singleElement().asString().contains("missing Action");
        final DeliveryStatusReport identical = mail.converter().fromEml(source.replace("Action: failed (reported)\r\n",
                "Action: failed (reported)\r\nAction: failed (reported)\r\n")).toEmail().getDeliveryStatusReport();
        assertThat(identical.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
        assertThat(identical.getWarnings()).singleElement().asString().contains("repeats Action");
        for (String second : new String[] {"delivered (reported)", "failed (different comment)"}) {
            final DeliveryStatusReport conflicting = mail.converter().fromEml(source.replace("Action: failed (reported)\r\n",
                    "Action: failed (reported)\r\nAction: " + second + "\r\n")).toEmail().getDeliveryStatusReport();
            assertThat(conflicting.getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.UNKNOWN);
            assertThat(conflicting.getWarnings()).singleElement().asString().contains("conflicting Action");
        }
    }

    @Test
    void retainsFactsAcrossConversionSnapshotsAndExactInspection() throws Exception {
        final String source = report("(reported) FaIlEd (recipient removed)");
        final byte[] bytes = source.getBytes(UTF_8);
        assertFacts(EmailConverter.emlToEmail(source), DeliveryAction.FAILED);
        for (boolean eager : new boolean[] {false, true}) {
            final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
            assertFacts(EmailConverter.mimeMessageToEmail(mime, null, eager), DeliveryAction.FAILED);
            assertFacts(mail.converter().fromMimeMessage(mime).withFetchAttachmentData(eager).toEmail(), DeliveryAction.FAILED);
            final Email email = mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();
            assertFacts(email, DeliveryAction.FAILED);
            assertFacts(mail.emailBuilder().copying(email).buildEmail(), DeliveryAction.FAILED);
            final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(serialized)) { output.writeObject(email); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
                assertFacts((Email) input.readObject(), DeliveryAction.FAILED);
            }
        }
        final Email exact = mail.emailBuilder().startingFromExactEml(bytes)
                .withEnvelopeRecipients("archive@example.org").buildEmail();
        assertFacts(exact, DeliveryAction.FAILED);
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(bytes);
    }

    private static void assertFacts(final Email email, final DeliveryAction expected) {
        final DeliveryStatusReport parsed = email.getDeliveryStatusReport();
        assertThat(parsed.getWarnings()).isEmpty();
        assertThat(parsed.getRecipients()).hasSize(1);
        assertThat(parsed.getRecipients().get(0).getAction()).isEqualTo(expected);
        assertThat(parsed.getRecipients().get(0).getStatusCode().toString()).isEqualTo("5.1.1");
        assertThat(parsed.getReportingServer().getName()).isEqualTo("relay.example.org");
    }

    private static String report(final String action) {
        return "From: postmaster@example.org\r\nTo: sender@example.org\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/report; report-type=delivery-status; boundary=report\r\n\r\n"
                + "--report\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nDelivery report.\r\n"
                + "--report\r\nContent-Type: message/delivery-status\r\n\r\n"
                + "Reporting-MTA: dns; relay.example.org\r\nOriginal-Envelope-Id: order-123\r\n\r\n"
                + "Final-Recipient: rfc822; reader@example.org\r\nAction: " + action + "\r\nStatus: 5.1.1\r\n"
                + "\r\n--report--\r\n";
    }
}
