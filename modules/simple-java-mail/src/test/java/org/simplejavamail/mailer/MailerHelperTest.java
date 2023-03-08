package org.simplejavamail.mailer;

import com.sanctionco.jmail.JMail;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import testutil.EmailHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MailerHelperTest {

    @Test
    public void validate() {
        val email = newBuilder().buildEmail();
        assertThat(MailerHelper.validate(email)).isTrue();
        assertThat(MailerHelper.validate(email, JMail.strictValidator())).isTrue();

        val emailInvalidFrom = newBuilder().from("invalid", "invalid").buildEmail();
        assertThat(MailerHelper.validate(emailInvalidFrom)).isTrue();
        assertThatThrownBy(() -> MailerHelper.validate(emailInvalidFrom, JMail.strictValidator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid FROM address: invalid");

        val emailMissingFrom = newBuilder().clearFromRecipient().buildEmail();
        assertThatThrownBy(() -> MailerHelper.validate(emailMissingFrom, JMail.strictValidator()))
                .isInstanceOf(MailCompletenessException.class)
                .hasMessageContaining("Email is not valid: missing sender.");
    }

    @Test
    public void validateLenient() {
        val emailMissingFrom = newBuilder().clearFromRecipient().buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateLenient(emailMissingFrom, JMail.strictValidator()));
        val emailInvalidFrom = newBuilder().from("invalid", "invalid").buildEmail();
        assertThat(MailerHelper.validateLenient(emailInvalidFrom)).isTrue();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateLenient(emailInvalidFrom, JMail.strictValidator()));
    }

    @Test
    public void validateCompleteness() {
        val emailMissingFrom = newBuilder().clearFromRecipient().buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateCompleteness(emailMissingFrom))
                .isInstanceOf(MailCompletenessException.class)
                .hasMessageContaining("Email is not valid: missing sender.");

        val emailMissingRecipients = newBuilder().clearRecipients().buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateCompleteness(emailMissingRecipients))
                .isInstanceOf(MailCompletenessException.class)
                .hasMessageContaining("Email is not valid: missing recipients");
    }

    @Test
    public void validateAddresses() {
        // happy scenarios
        val happyEmail = newBuilder()
                .clearReturnReceiptTo()
                .clearDispositionNotificationTo()
                .buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateAddresses(happyEmail, JMail.validator()));

        val emailEmptyDispositionNotificationTo = newBuilder().withDispositionNotificationTo().buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateAddresses(emailEmptyDispositionNotificationTo, JMail.validator()));

        val emailEmptyReturnReceiptTo = newBuilder().withReturnReceiptTo().buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateAddresses(emailEmptyReturnReceiptTo, JMail.validator()));

        // problem cases
        val emailMissingFrom = newBuilder().clearFromRecipient().buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.validateAddresses(emailMissingFrom, null));

        val emailInvalidFrom = newBuilder().from("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidFrom, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid FROM address: invalid");

        val emailInvalidBcc = newBuilder().bcc("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidBcc, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid BCC address: invalid");

        val emailInvalidCc = newBuilder().cc("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidCc, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid CC address: invalid");

        val emailInvalidTo = newBuilder().to("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidTo, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid TO address: invalid");

        val emailInvalidToByDefault = newBuilder().withRecipients(new Recipient(null, "invalid", null)).buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidToByDefault, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid TO address: invalid");

        val emailInvalidReplyTo = newBuilder().withReplyTo("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidReplyTo, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid REPLY TO address: invalid");

        val emailInvalidBounceTo = newBuilder().withBounceTo("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidBounceTo, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid BOUNCE TO address: invalid");

        val emailInvalidDispositionNotificationTo = newBuilder().withDispositionNotificationTo("invalid", "invalid").buildEmail();
        assertThatThrownBy(() -> MailerHelper.validateAddresses(emailInvalidDispositionNotificationTo, JMail.validator()))
                .isInstanceOf(MailInvalidAddressException.class)
                .hasMessageContaining("Invalid \"Disposition Notification To\" address: invalid");
    }

    @Test
    public void scanForInjectionAttacks() {
    }

    @Test
    public void scanForInjectionAttack() {
        val safeEmail = newBuilder().buildEmail();
        assertThatNoException().isThrownBy(() -> MailerHelper.scanForInjectionAttacks(safeEmail));

        assertThatThrownBy(() -> MailerHelper.scanForInjectionAttacks(newBuilder()
                .withSubject("hmm \n ee")
                .buildEmail()))
                .isInstanceOf(MailSuspiciousCRLFValueException.class)
                .hasMessageContaining("Suspected of injection attack, field: email.subject with suspicious value: hmm \\n ee");

        assertThatThrownBy(() -> MailerHelper.scanForInjectionAttacks(newBuilder()
                .withHeader("m%0Aoo", "headerValue")
                .buildEmail()))
                .isInstanceOf(MailSuspiciousCRLFValueException.class)
                .hasMessageContaining("Suspected of injection attack, field: email.header.headerName with suspicious value: m%0Aoo");

        assertThatThrownBy(() -> MailerHelper.scanForInjectionAttacks(newBuilder()
                .withHeader("headerName", "m\roo")
                .buildEmail()))
                .isInstanceOf(MailSuspiciousCRLFValueException.class)
                .hasMessageContaining("Suspected of injection attack, field: email.header.[headerName] with suspicious value: m\\roo");
    }

    @NotNull
    private static EmailPopulatingBuilder newBuilder() {
        return EmailHelper.createDummyEmailBuilder("id", true, false, true, true, true, false, true);
    }
}