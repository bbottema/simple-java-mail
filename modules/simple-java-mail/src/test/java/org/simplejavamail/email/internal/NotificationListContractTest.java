package org.simplejavamail.email.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.MailInvalidAddressException;
import testutil.smtp.RecordingSmtpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationListContractTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void destinationsAreIndependentImmutableSnapshotsAndSurviveCopySerializationAndMimeImport() throws Exception {
        final List<Recipient> supplied = new ArrayList<>(Arrays.asList(
                mail.recipientBuilder().to("Alice", "alice@example.org"), mail.recipientBuilder().to("Bob", "bob@example.org")));
        final EmailPopulatingBuilder builder = base().withDispositionNotificationTo(supplied).withReturnReceiptTo(supplied);
        final Email original = builder.buildEmail();
        supplied.clear();
        builder.withDispositionNotificationTo("replacement@example.org").clearReturnReceiptTo();
        assertAddresses(original, "alice@example.org", "bob@example.org");
        assertThatThrownBy(() -> original.getDispositionNotificationToRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> original.getReturnReceiptToRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertAddresses(mail.emailBuilder().copying(original).buildEmail(), "alice@example.org", "bob@example.org");
        assertAddresses(roundTrip(original), "alice@example.org", "bob@example.org");
        final Email imported = mail.converter().fromEml(mail.converter().fromEmail(original).toEmlBytes()).toEmail();
        assertAddresses(imported, "alice@example.org", "bob@example.org");
    }

    @Test
    void governanceSelectsOneWholeListAndSuppressionDoesNotRemoveSubmittedDestinations() throws Exception {
        final Email defaults = mail.emailBuilder().startingBlank().withDispositionNotificationTo("default@example.org")
                .withReturnReceiptTo("default@example.org").buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withDispositionNotificationTo("override1@example.org, override2@example.org")
                .withReturnReceiptTo("override1@example.org, override2@example.org").buildEmail();
        final Email submitted = base().withDispositionNotificationTo("local1@example.org, local2@example.org")
                .withReturnReceiptTo("local1@example.org, local2@example.org").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer()) {
            assertAddresses(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(submitted),
                    "override1@example.org", "override2@example.org");
            final Email suppressed = mail.emailBuilder().copying(submitted).withTemplateOptions(EmailTemplateOptions.builder()
                    .dontApplyOverrideValueFor(EmailProperty.DISPOSITION_NOTIFICATION_TO, EmailProperty.RETURN_RECEIPT_TO).build()).buildEmail();
            assertAddresses(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(suppressed),
                    "local1@example.org", "local2@example.org");
        }
    }

    @Test
    void everyDestinationIsValidatedAndClearingStillAllowsDeferredFallback() throws Exception {
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").buildMailer()) {
            assertThatThrownBy(() -> mailer.validate(base().withDispositionNotificationTo("valid@example.org, invalid").buildEmail()))
                    .isInstanceOf(MailInvalidAddressException.class);
            final Email deferred = base().withReplyTo("reply1@example.org, reply2@example.org")
                    .withDispositionNotificationTo("discard@example.org").withReturnReceiptTo("discard@example.org")
                    .withDispositionNotificationTo().withReturnReceiptTo().buildEmail();
            assertThat(deferred.getDispositionNotificationToRecipients()).isEmpty();
            assertAddresses(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(deferred), "reply1@example.org", "reply2@example.org");
        }
    }

    @Test
    void releasedSingletonSnapshotUpgradesBothDestinationsWithoutChangingItsRequestFlags() throws Exception {
        final byte[] fixture;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/serialization/email-9.3.6-notifications.base64"))) {
            fixture = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(fixture))) {
            restored = (Email) input.readObject();
        }
        assertThat(restored.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress).containsExactly("read@example.org");
        assertThat(restored.getReturnReceiptToRecipients()).extracting(Recipient::getAddress).containsExactly("return@example.org");
        assertThat(restored.getUseDispositionNotificationTo()).isTrue();
        assertThat(restored.getUseReturnReceiptTo()).isTrue();
        assertThat(mail.emailBuilder().copying(restored).buildEmail()).isEqualTo(restored);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smtpSubmitsAllDeliveryMailboxesButNeverNotificationDestinations(final boolean asynchronous) throws Exception {
        try (RecordingSmtpServer server = new RecordingSmtpServer();
             Mailer mailer = mail.mailerBuilder().withSMTPServer("127.0.0.1", server.port())
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test").build()).buildMailer()) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.org")
                    .to("alice@example.org, bob@example.org").cc("carol@example.org; dan@example.org").bcc("eve@example.org")
                    .withReplyTo("reply1@example.org, reply2@example.org")
                    .withDispositionNotificationTo().withReturnReceiptTo().withPlainText("body").buildEmail();
            if (asynchronous) {
                mailer.async().sendMail(email).getCompletion().get(20, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                mailer.sync().sendMail(email);
            }
            assertThat(server.commands()).filteredOn(command -> command.startsWith("RCPT TO:"))
                    .containsExactly("RCPT TO:<alice@example.org>", "RCPT TO:<bob@example.org>", "RCPT TO:<carol@example.org>",
                            "RCPT TO:<dan@example.org>", "RCPT TO:<eve@example.org>");
            assertThat(server.messages()).hasSize(1);
            final MimeMessage received = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(server.messages().get(0)));
            assertThat(received.getHeader("Disposition-Notification-To", null)).contains("reply1@example.org", "reply2@example.org");
            assertThat(received.getHeader("Return-Receipt-To", null)).contains("reply1@example.org", "reply2@example.org");
            assertThat(received.getRecipients(jakarta.mail.Message.RecipientType.BCC)).isNull();
        }
    }

    private EmailPopulatingBuilder base() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("delivery@example.org").withPlainText("body");
    }

    private static void assertAddresses(final Email email, final String... addresses) {
        assertThat(email.getDispositionNotificationToRecipients()).extracting(Recipient::getAddress).containsExactly(addresses);
        assertThat(email.getReturnReceiptToRecipients()).extracting(Recipient::getAddress).containsExactly(addresses);
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }
}
