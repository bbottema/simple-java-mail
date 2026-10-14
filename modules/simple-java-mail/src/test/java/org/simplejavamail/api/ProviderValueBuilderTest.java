package org.simplejavamail.api;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderValueBuilderTest {
    @Test
    void preparedContentCopiesRecipientsAndRequiresExplicitContentRules() throws Exception {
        final Address[] recipients = {new InternetAddress("reader@example.org")};
        final PreparedMail.Builder builder = PreparedMail.builder().withMimeMessage(new MimeMessage(Session.getInstance(new Properties())))
                .withRecipients(recipients).withDeliveryEnvelope(DeliveryEnvelope.builder().build());
        assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class).hasMessage("contentRequirement");
        final PreparedMail first = builder.withContentRequirement(ContentRequirement.PRESERVE_ALL_BYTES).build();
        recipients[0] = new InternetAddress("changed@example.org");
        first.getRecipients()[0] = recipients[0];
        assertThat(first.getRecipients()[0].toString()).isEqualTo("reader@example.org");
        assertThat(first.isLegacySmtpContentSupportEnabled()).isFalse();
        assertThat(builder.withLegacySmtpContentSupportEnabled(true).withLegacySmtpContentSupportEnabled(false).build()
                .isLegacySmtpContentSupportEnabled()).isFalse();
    }

    @Test
    void envelopeBuilderCopiesPoliciesAndPreservesAnExplicitFalse() {
        final List<DeliveryRecipient> recipients = new ArrayList<>(List.of(new DeliveryRecipient("reader@example.org", List.of())));
        final DeliveryEnvelope.Builder builder = DeliveryEnvelope.builder().withRecipientOptions(recipients).withSendingToAcceptedRecipients(false);
        final DeliveryEnvelope first = builder.build();
        recipients.clear();
        assertThat(first.getRecipientOptions()).hasSize(1);
        assertThat(first.getSendingToAcceptedRecipients()).isFalse();
        assertThat(first.hasProviderSpecificOptions()).isTrue();
        assertThatThrownBy(() -> first.getRecipientOptions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(builder.withSendingToAcceptedRecipients(null).withRecipientOptions(List.of()).build().hasProviderSpecificOptions()).isFalse();
        assertThatThrownBy(() -> builder.withDeliveryStatusNotificationRequired(true).build()).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.withDeliveryStatusNotificationRequired(false).build().isDeliveryStatusNotificationRequired()).isFalse();
        assertThat(DeliveryEnvelope.builder().build().getRecipientOptions()).isEmpty();
    }
}
