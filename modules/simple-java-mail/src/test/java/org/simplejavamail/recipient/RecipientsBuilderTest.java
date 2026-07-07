package org.simplejavamail.recipient;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Recipient;

import java.security.cert.X509Certificate;
import java.util.Collection;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

public class RecipientsBuilderTest {

    @Test
    public void buildRecipients_collectsParsedRecipientsWithDefaultAndFixedNames() {
        Collection<Recipient> recipients = new RecipientsBuilder()
                .withRecipientsWithDefaultName("Default", TO, "Alice <alice@example.com>", "bob@example.com")
                .withRecipientsWithFixedName("Fixed", CC, "Carol <carol@example.com>", "dave@example.com")
                .buildRecipients();

        assertThat(recipients).containsExactly(
                new Recipient("Alice", "alice@example.com", TO, null),
                new Recipient("Default", "bob@example.com", TO, null),
                new Recipient("Fixed", "carol@example.com", CC, null),
                new Recipient("Fixed", "dave@example.com", CC, null));
    }

    @Test
    public void buildRecipients_preservesRecipientSmimeCertificates() {
        X509Certificate certificate = mock(X509Certificate.class);

        Collection<Recipient> recipients = new RecipientsBuilder()
                .withRecipients(singletonList(new Recipient("Archive", "archive@example.com", TO, certificate)), BCC)
                .buildRecipients();

        assertThat(recipients).containsExactly(new Recipient("Archive", "archive@example.com", BCC, certificate));
    }

    @Test
    public void buildRecipients_appliesDefaultSmimeCertificateAsGroupDefault() {
        X509Certificate groupCertificate = mock(X509Certificate.class);
        X509Certificate recipientCertificate = mock(X509Certificate.class);

        Collection<Recipient> recipients = new RecipientsBuilder()
                .withDefaultSmimeCertificate(groupCertificate)
                .withRecipient(new Recipient("Alice", "alice@example.com", TO, recipientCertificate))
                .withRecipient("Bob", "bob@example.com", TO)
                .buildRecipients();

        assertThat(recipients).containsExactly(
                new Recipient("Alice", "alice@example.com", TO, recipientCertificate),
                new Recipient("Bob", "bob@example.com", TO, groupCertificate));
    }

    @Test
    public void buildRecipients_appliesFixedSmimeCertificateAsGroupOverride() {
        X509Certificate groupCertificate = mock(X509Certificate.class);
        X509Certificate recipientCertificate = mock(X509Certificate.class);

        Collection<Recipient> recipients = new RecipientsBuilder()
                .withFixedSmimeCertificate(groupCertificate)
                .withRecipient(new Recipient("Alice", "alice@example.com", TO, recipientCertificate))
                .withRecipient("Bob", "bob@example.com", TO)
                .buildRecipients();

        assertThat(recipients).containsExactly(
                new Recipient("Alice", "alice@example.com", TO, groupCertificate),
                new Recipient("Bob", "bob@example.com", TO, groupCertificate));
    }

    @Test
    public void buildRecipients_clearsSmimeCertificatesAsGroupPolicy() {
        X509Certificate groupCertificate = mock(X509Certificate.class);
        X509Certificate recipientCertificate = mock(X509Certificate.class);

        Collection<Recipient> recipients = new RecipientsBuilder()
                .withDefaultSmimeCertificate(groupCertificate)
                .clearingSmimeCertificates()
                .withRecipient(new Recipient("Alice", "alice@example.com", TO, recipientCertificate))
                .withRecipient("Bob", "bob@example.com", TO)
                .buildRecipients();

        assertThat(recipients).containsExactly(
                new Recipient("Alice", "alice@example.com", TO, null),
                new Recipient("Bob", "bob@example.com", TO, null));
    }

    @Test
    public void buildRecipients_usesLastGroupSmimeCertificatePolicy() {
        X509Certificate defaultCertificate = mock(X509Certificate.class);
        X509Certificate fixedCertificate = mock(X509Certificate.class);
        X509Certificate recipientCertificate = mock(X509Certificate.class);

        Collection<Recipient> recipients = new RecipientsBuilder()
                .withFixedSmimeCertificate(fixedCertificate)
                .withDefaultSmimeCertificate(defaultCertificate)
                .withRecipient(new Recipient("Alice", "alice@example.com", TO, recipientCertificate))
                .withRecipient("Bob", "bob@example.com", TO)
                .buildRecipients();

        assertThat(recipients).containsExactly(
                new Recipient("Alice", "alice@example.com", TO, recipientCertificate),
                new Recipient("Bob", "bob@example.com", TO, defaultCertificate));
    }

    @Test
    public void buildRecipients_returnsDefensiveUnmodifiableCopy() {
        RecipientsBuilder builder = new RecipientsBuilder();
        Collection<Recipient> firstBuild = builder
                .withRecipient("first@example.com", TO)
                .buildRecipients();

        builder.withRecipient("second@example.com", TO);

        assertThat(firstBuild).containsExactly(new Recipient(null, "first@example.com", TO, null));
        assertThatThrownBy(() -> firstBuild.add(new Recipient(null, "third@example.com", TO, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
