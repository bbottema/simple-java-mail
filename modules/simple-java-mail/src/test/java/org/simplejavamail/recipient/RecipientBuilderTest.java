package org.simplejavamail.recipient;

import jakarta.mail.Message.RecipientType;

import org.simplejavamail.api.SimpleJavaMail;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Recipient;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RecipientBuilderTest {

    @Test
    public void toCreatesNamedToRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().withName("Alice").withAddress("alice@example.com").withType(RecipientType.TO).build())
                .isEqualTo(new Recipient("Alice", "alice@example.com", TO, null));
    }

    @Test
    public void ccCreatesNamedCcRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().withName("Audit").withAddress("audit@example.com").withType(RecipientType.CC).build())
                .isEqualTo(new Recipient("Audit", "audit@example.com", CC, null));
    }

    @Test
    public void bccCreatesNamedBccRecipient() {
        assertThat(SimpleJavaMail.fromDefaults().recipientBuilder().withName("Archive").withAddress("archive@example.com").withType(RecipientType.BCC).build())
                .isEqualTo(new Recipient("Archive", "archive@example.com", BCC, null));
    }

    @Test
    public void convenienceFactoriesUseRecipientBuilderAddressValidation() {
        assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().recipientBuilder().withName("Alice").withAddress("").withType(RecipientType.TO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("address is required");
    }
}
