package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EmailAddressListConvenienceTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void listConveniencesKeepEveryMailboxNameOrderAndDuplicate() {
        final Email email = mail.emailBuilder().startingBlank()
                .to("\"Doe, Alice\" <alice@example.org>, Bob <bob@example.org>; alice@example.org")
                .cc("Team: carol@example.org, Dan <dan@example.org>;")
                .bcc("Audit", "Original <archive@example.org>, backup@example.org")
                .withReplyTo("Alice <alice@example.org>, Bob <bob@example.org>")
                .withReplyTo("reply@example.org")
                .buildEmail();

        assertThat(email.getToRecipients()).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "bob@example.org", "alice@example.org");
        assertThat(email.getToRecipients()).extracting(Recipient::getName).containsExactly("Doe, Alice", "Bob", null);
        assertThat(email.getCcRecipients()).extracting(Recipient::getAddress).containsExactly("carol@example.org", "dan@example.org");
        assertThat(email.getBccRecipients()).extracting(Recipient::getName).containsExactly("Audit", "Audit");
        assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "bob@example.org", "reply@example.org");
    }

    @Test
    void explicitReplyNameOverridesEveryParsedName() {
        final Email email = mail.emailBuilder().startingBlank()
                .withReplyTo("Accounts", "Alice <alice@example.org>, bob@example.org").buildEmail();
        assertThat(email.getReplyToRecipients()).extracting(Recipient::getName).containsExactly("Accounts", "Accounts");
    }

    @Test
    void automaticNotificationsUseAllReplyToDestinationsWithoutAddingDeliveryRecipients() throws Exception {
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("delivery@example.org")
                .withReplyTo("alice@example.org, bob@example.org")
                .withDispositionNotificationTo().withReturnReceiptTo()
                .buildEmailCompletedWithDefaultsAndOverrides();
        final MimeMessage mime = mail.converter().fromEmail(email).toMimeMessage();
        assertThat(mime.getHeader("Disposition-Notification-To", null)).contains("alice@example.org", "bob@example.org");
        assertThat(mime.getHeader("Return-Receipt-To", null)).contains("alice@example.org", "bob@example.org");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly("delivery@example.org");
    }

    @Test
    void explicitNotificationListsReplaceTheirPreviousChoice() throws Exception {
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.org")
                .withDispositionNotificationTo("discard@example.org")
                .withDispositionNotificationTo("Receipts", "Alice <alice@example.org>; bob@example.org")
                .withReturnReceiptTo("return@example.org, backup@example.org")
                .buildEmail();
        final MimeMessage mime = mail.converter().fromEmail(email).toMimeMessage();
        assertThat(mime.getHeader("Disposition-Notification-To", null))
                .contains("alice@example.org", "bob@example.org", "Receipts").doesNotContain("discard@example.org");
        assertThat(mime.getHeader("Return-Receipt-To", null)).contains("return@example.org", "backup@example.org");
    }

    @Test
    void senderAndBounceStaySingularAndMalformedNonemptyInputStaysAvailableToValidation() {
        assertThatIllegalArgumentException().isThrownBy(() -> mail.emailBuilder().startingBlank().from("alice@example.org,bob@example.org"));
        assertThatIllegalArgumentException().isThrownBy(() -> mail.emailBuilder().startingBlank().withBounceTo("alice@example.org,bob@example.org"));
        final String malformed = "unclosed <address";
        assertThat(mail.emailBuilder().startingBlank().to(malformed).buildEmail().getToRecipients())
                .extracting(Recipient::getAddress).containsExactly(malformed);
    }
}
