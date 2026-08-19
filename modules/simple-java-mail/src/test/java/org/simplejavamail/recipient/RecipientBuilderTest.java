package org.simplejavamail.recipient;

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
        assertThat(RecipientBuilder.to("Alice", "alice@example.com"))
                .isEqualTo(new Recipient("Alice", "alice@example.com", TO, null));
    }

    @Test
    public void ccCreatesNamedCcRecipient() {
        assertThat(RecipientBuilder.cc("Audit", "audit@example.com"))
                .isEqualTo(new Recipient("Audit", "audit@example.com", CC, null));
    }

    @Test
    public void bccCreatesNamedBccRecipient() {
        assertThat(RecipientBuilder.bcc("Archive", "archive@example.com"))
                .isEqualTo(new Recipient("Archive", "archive@example.com", BCC, null));
    }

    @Test
    public void convenienceFactoriesUseRecipientBuilderAddressValidation() {
        assertThatThrownBy(() -> RecipientBuilder.to("Alice", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("address is required");
    }
}
