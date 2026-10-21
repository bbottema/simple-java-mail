package org.simplejavamail.email.internal;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationCopyContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    enum Choice { UNSET, AUTOMATIC, EXPLICIT, CLEARED, DISABLED }

    @ParameterizedTest
    @EnumSource(Choice.class)
    void copyingPreservesFlagsAndAddressesSeparately(final Choice choice) throws Exception {
        final Email original = email(choice);
        assertChoices(mail.emailBuilder().copying(original).buildEmail(), original);
        assertChoices(mail.emailBuilder().copying(roundTrip(original)).buildEmail(), original);
    }

    @ParameterizedTest
    @EnumSource(Choice.class)
    void copiedChoicesRetainTheirMeaningDuringGovernance(final Choice choice) throws Exception {
        final Email original = email(choice);
        final Email copy = mail.emailBuilder().copying(original).buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(email(Choice.AUTOMATIC)).buildMailer()) {
            final Email governed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(copy);
            assertThat(governed.getUseDispositionNotificationTo()).isEqualTo(choice != Choice.DISABLED);
            assertThat(governed.getUseReturnReceiptTo()).isEqualTo(choice != Choice.DISABLED);
            if (choice == Choice.EXPLICIT || choice == Choice.DISABLED) {
                assertThat(governed.getDispositionNotificationToRecipients()).isEqualTo(original.getDispositionNotificationToRecipients());
                assertThat(governed.getReturnReceiptToRecipients()).isEqualTo(original.getReturnReceiptToRecipients());
            } else {
                assertThat(governed.getDispositionNotificationToRecipients()).containsExactly(original.getFromRecipient());
                assertThat(governed.getReturnReceiptToRecipients()).containsExactly(original.getFromRecipient());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Choice.class)
    void copyingPreservesPropertySpecificSuppression(final Choice choice) throws Exception {
        final Email original = builder(choice)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(EmailProperty.USE_DISPOSITION_NOTIFICATION_TO, EmailProperty.USE_RETURN_RECEIPT_TO).build()).buildEmail();
        final Email copy = mail.emailBuilder().copying(original).buildEmail();
        assertThat(copy.getPropertiesNotToApplyDefaultValueFor()).isEqualTo(original.getPropertiesNotToApplyDefaultValueFor());
        assertChoices(copy, original);
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(email(Choice.AUTOMATIC)).buildMailer()) {
            final Email governed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(copy);
            assertThat(governed.getUseDispositionNotificationTo()).isEqualTo(original.getUseDispositionNotificationTo());
            assertThat(governed.getUseReturnReceiptTo()).isEqualTo(original.getUseReturnReceiptTo());
        }
    }

    private Email email(final Choice choice) {
        return builder(choice).buildEmail();
    }

    private EmailPopulatingBuilder builder(final Choice choice) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "recipient@example.org"));
        switch (choice) {
            case AUTOMATIC:
                builder.withDispositionNotificationTo().withReturnReceiptTo();
                break;
            case EXPLICIT:
                builder.withDispositionNotificationTo("read@example.org").withReturnReceiptTo("received@example.org");
                break;
            case DISABLED:
                // Preserve explicit-false snapshots created by earlier versions, independently of public clear semantics.
                ((InternalEmailPopulatingBuilder) builder).withDispositionNotificationToState(false, null).withReturnReceiptToState(false, null);
                break;
            case CLEARED:
                builder.withDispositionNotificationTo().withReturnReceiptTo().clearDispositionNotificationTo().clearReturnReceiptTo();
                break;
            default:
                break;
        }
        return builder;
    }

    private static void assertChoices(final Email actual, final Email expected) {
        assertThat(actual.getUseDispositionNotificationTo()).isEqualTo(expected.getUseDispositionNotificationTo());
        assertThat(actual.getDispositionNotificationToRecipients()).isEqualTo(expected.getDispositionNotificationToRecipients());
        assertThat(actual.getUseReturnReceiptTo()).isEqualTo(expected.getUseReturnReceiptTo());
        assertThat(actual.getReturnReceiptToRecipients()).isEqualTo(expected.getReturnReceiptToRecipients());
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
