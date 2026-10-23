package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.utils.mail.dkim.DomainKey;
import org.simplejavamail.utils.mail.dkim.DomainKeyUtil;
import testutil.ConfigLoaderTestHelper;
import testutil.DkimDnsServer;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DkimDomainCaseTest {

    @ParameterizedTest
    @CsvSource({
            "Sender@example.com, example.com",
            "Sender@EXAMPLE.COM, example.com",
            "Sender@example.com, EXAMPLE.COM",
            "Sender@ExAmPlE.CoM, eXaMpLe.cOm",
            "Sender@sales.Example.Com, example.com",
            "Sender@sales.example.com, EXAMPLE.COM"
    })
    void signsEquivalentDomainsWithoutChangingCallerSpelling(final String sender, final String domain) throws Exception {
        final KeyPair keyPair = rsaKey();
        // Restrict the DNS record to the original local part, so lowercasing it would fail the real DomainKey check.
        final DomainKey domainKey = domainKey(keyPair, "Sender");
        final DkimConfig config = config(keyPair, domain);
        final Email email = email(sender, config);

        try (DnsFixture dns = new DnsFixture(domainKey)) {

            final MimeMessage signed = EmailConverter.emailToMimeMessage(email);

            assertThat(dns.server.received("mail._domainkey." + domain)).isTrue();
            assertThat(email.getFromRecipient().getAddress()).isEqualTo(sender);
            assertThat(email.getDkimConfig().getDkimSigningDomain()).isEqualTo(domain);
            assertThat(signed.getHeader("From", null)).isEqualTo(sender);
            assertThat(dkimTag(signed, "d")).isEqualTo(domain);
            assertThat(dkimTag(signed, "i")).isEqualTo(sender);
            assertValidSignature(signed, keyPair, false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sender@other.com", "Sender@notexample.com", "Sender@example.com.evil", "Sender@example.comevil"})
    void rejectsUnrelatedAndLookalikeDomainsBeforeDns(final String sender) throws Exception {
        final Email email = email(sender, config(rsaKey(), "example.com"));

        try (DnsFixture dns = new DnsFixture(null)) {
            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("isn't example.com or a subdomain thereof");
            assertThat(dns.server.requestCount()).isZero();
        }
    }

    @Test
    void retainsCaseSensitiveDnsLocalPartRestrictions() throws Exception {
        final KeyPair keyPair = rsaKey();
        final DomainKey domainKey = domainKey(keyPair, "Sender");
        final Email email = email("sender@EXAMPLE.COM", config(keyPair, "example.com"));

        try (DnsFixture dns = new DnsFixture(domainKey)) {

            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("Incompatible identity for granularity Sender");
            assertThat(dns.server.received("mail._domainkey.example.com")).isTrue();
        }
    }

    @Test
    void retainsDnsKeyCompatibilityChecks() throws Exception {
        final DomainKey domainKey = domainKey(rsaKey(), "Sender");
        final Email email = email("Sender@EXAMPLE.COM", config(rsaKey(), "example.com"));

        try (DnsFixture dns = new DnsFixture(domainKey)) {

            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("Incompatible private and public key");
            assertThat(dns.server.received("mail._domainkey.example.com")).isTrue();
        }
    }

    @Test
    void signsEd25519WithEquivalentSigningDomainCase() throws Exception {
        final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider()).generateKeyPair();
        // The established lowercase fixture address intentionally uses the existing DNS-free path.
        final String sender = "Sender@supersecret-testing-domain.com";
        final String domain = "SUPERSECRET-TESTING-DOMAIN.COM";
        final DkimConfig config = DkimConfig.builder().dkimPrivateKeyData(keyPair.getPrivate().getEncoded())
                .dkimSigningDomain(domain).dkimSelector("mail").signingAlgorithm("SHA256_WITH_ED25519").build();
        final Email email = email(sender, config);

        try (DnsFixture dns = new DnsFixture(null)) {
            final MimeMessage signed = EmailConverter.emailToMimeMessage(email);

            assertThat(dns.server.requestCount()).isZero();
            assertThat(dkimTag(signed, "d")).isEqualTo(domain);
            assertThat(dkimTag(signed, "i")).isEqualTo(sender);
            assertThat(signed.getHeader("From", null)).isEqualTo(sender);
            assertValidSignature(signed, keyPair, true);
        }
    }

    private static Email email(final String sender, final DkimConfig config) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from(sender).to("receiver@example.org").withSubject("DKIM domain spelling")
                .withPlainText("body").signWithDomainKey(config).buildEmail();
    }

    private static DkimConfig config(final KeyPair keyPair, final String domain) {
        return DkimConfig.builder().dkimPrivateKeyData(keyPair.getPrivate().getEncoded())
                .dkimSigningDomain(domain).dkimSelector("mail").build();
    }

    private static KeyPair rsaKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static DomainKey domainKey(final KeyPair keyPair, final String localPart) throws Exception {
        return new DomainKey(java.util.Map.of(
                'v', "DKIM1", 'k', "rsa", 'g', localPart,
                'p', Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())));
    }

    private static String dkimTag(final MimeMessage signed, final String name) throws Exception {
        final String header = signed.getHeader("DKIM-Signature", null).replaceAll("\\r\\n[ \\t]+", "");
        for (String tag : header.split(";")) {
            final String trimmed = tag.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        throw new AssertionError("Missing DKIM tag " + name);
    }

    private static void assertValidSignature(final MimeMessage signed, final KeyPair keyPair, final boolean ed25519) throws Exception {
        SignatureAssertions.assertValidSignature(((FinalizedMimeMessage) signed).getSerializedBytes(), keyPair.getPublic());
    }

    private static final class DnsFixture implements AutoCloseable {
        private final String previous = DomainKeyUtil.getDnsProviderUrl();
        private final DkimDnsServer server;

        private DnsFixture(final DomainKey key) throws Exception {
            final StringBuilder record = new StringBuilder();
            if (key == null) {
                record.append("v=DKIM1; p=invalid");
            } else {
                key.getTags().forEach((name, value) -> record.append(name).append('=').append(value).append(';'));
            }
            server = new DkimDnsServer(record.toString());
            DomainKeyUtil.setDnsProviderUrl(server.url());
        }

        @Override
        public void close() throws Exception {
            try { DomainKeyUtil.setDnsProviderUrl(previous); }
            finally { server.close(); }
        }
    }
}
