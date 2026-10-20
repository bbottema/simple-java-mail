package org.simplejavamail.recipient;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.config.ConfigLoader;

import java.security.cert.X509Certificate;

import static jakarta.mail.Message.RecipientType.BCC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class RecipientLenientAddressTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void extractsQuotedDisplayNameAndRetainsRecipientMetadata() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final IRecipientBuilder builder = mail.recipientBuilder().withType(BCC).withSmimeCertificate(certificate)
                .withDeliveryStatusNotificationNotifyOptions(FAILURE)
                .withAddress("\"Doe, Alice\" <alice@example.org>");

        assertThat(builder.getName()).isEqualTo("Doe, Alice");
        assertThat(builder.getAddress()).isEqualTo("alice@example.org");
        final Recipient recipient = builder.build();
        assertThat(recipient.getName()).isEqualTo("Doe, Alice");
        assertThat(recipient.getAddress()).isEqualTo("alice@example.org");
        assertThat(recipient.getType()).isEqualTo(BCC);
        assertThat(recipient.getSmimeCertificate()).isSameAs(certificate);
        assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
    }

    @Test
    void explicitNameWinsInEitherSetterOrderAndNullRestoresEmbeddedName() {
        final IRecipientBuilder nameFirst = mail.recipientBuilder().withName("Chosen")
                .withAddress("Alice <alice@example.org>");
        final IRecipientBuilder addressFirst = mail.recipientBuilder().withAddress("Alice <alice@example.org>")
                .withName("Chosen");

        assertThat(nameFirst.build().getName()).isEqualTo("Chosen");
        assertThat(addressFirst.build().getName()).isEqualTo("Chosen");
        assertThat(nameFirst.withName(null).build().getName()).isEqualTo("Alice");
        assertThat(addressFirst.withName(null).build().getName()).isEqualTo("Alice");
    }

    @Test
    void replacingAddressReplacesItsInferredNameWithoutChangingBuiltRecipients() {
        final IRecipientBuilder builder = mail.recipientBuilder().withAddress("Alice <alice@example.org>");
        final Recipient alice = builder.build();
        assertThat(builder.withAddress("Bob <bob@example.org>").build().getName()).isEqualTo("Bob");
        assertThat(builder.withAddress("plain@example.org").build().getName()).isNull();
        assertThat(alice.getName()).isEqualTo("Alice");
        assertThat(alice.getAddress()).isEqualTo("alice@example.org");
    }

    @Test
    void addressOnlyHelpersRetainThePreviouslyInferredName() throws Exception {
        final IRecipientBuilder builder = mail.recipientBuilder().withAddress("Alice <alice@example.org>");
        assertThat(builder.withAddressOnlyFrom("Bob <bob@example.org>").build().getName()).isEqualTo("Alice");
        assertThat(builder.withAddressOnlyFrom(new InternetAddress("Carol <carol@example.org>")).build().getName()).isEqualTo("Alice");
        assertThat(builder.getAddress()).isEqualTo("carol@example.org");
    }

    @Test
    void namedHelpersReplacePreviousInferredNamesEvenWhenTheNewNameIsAbsent() throws Exception {
        final IRecipientBuilder builder = mail.recipientBuilder().withAddress("Alice <alice@example.org>");
        assertThat(builder.withAddressAndNameOrDefault(new InternetAddress("plain@example.org"), null).build().getName()).isNull();
        builder.withAddress("Bob <bob@example.org>");
        assertThat(builder.withAddressAndFixedNameOrProvided(new InternetAddress("plain@example.org"), null).build().getName()).isNull();
    }

    @Test
    void shortcutsParseEmbeddedNamesAndHonorExplicitNames() {
        assertThat(mail.recipientBuilder().to("Alice <alice@example.org>").getName()).isEqualTo("Alice");
        assertThat(mail.recipientBuilder().cc("Alice <alice@example.org>").getAddress()).isEqualTo("alice@example.org");
        assertThat(mail.recipientBuilder().bcc("Chosen", "Alice <alice@example.org>").getName()).isEqualTo("Chosen");
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken <address", "(comment only)", "   ", "alice@example.org,bob@example.org",
            "alice@example.org;bob@example.org", "Team: alice@example.org, bob@example.org;"})
    void retainsUnparseableOrMultipleMailboxInputForNormalValidation(final String source) {
        final Recipient recipient = mail.recipientBuilder().withName("Chosen").withAddress(source).build();
        assertThat(recipient.getAddress()).isEqualTo(source);
        assertThat(recipient.getName()).isEqualTo("Chosen");
    }
}
