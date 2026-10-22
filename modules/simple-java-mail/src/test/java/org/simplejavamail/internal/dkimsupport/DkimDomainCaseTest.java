package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.Header;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.utils.mail.dkim.DomainKey;
import org.simplejavamail.utils.mail.dkim.DomainKeyUtil;
import testutil.ConfigLoaderTestHelper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

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

        try (MockedStatic<DomainKeyUtil> dns = mockStatic(DomainKeyUtil.class)) {
            dns.when(() -> DomainKeyUtil.getDomainKey(domain, "mail")).thenReturn(domainKey);

            final MimeMessage signed = EmailConverter.emailToMimeMessage(email);

            dns.verify(() -> DomainKeyUtil.getDomainKey(domain, "mail"));
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

        try (MockedStatic<DomainKeyUtil> dns = mockStatic(DomainKeyUtil.class)) {
            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("isn't example.com or a subdomain thereof");
            dns.verifyNoInteractions();
        }
    }

    @Test
    void retainsCaseSensitiveDnsLocalPartRestrictions() throws Exception {
        final KeyPair keyPair = rsaKey();
        final DomainKey domainKey = domainKey(keyPair, "Sender");
        final Email email = email("sender@EXAMPLE.COM", config(keyPair, "example.com"));

        try (MockedStatic<DomainKeyUtil> dns = mockStatic(DomainKeyUtil.class)) {
            dns.when(() -> DomainKeyUtil.getDomainKey("example.com", "mail")).thenReturn(domainKey);

            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("Incompatible identity for granularity Sender");
            dns.verify(() -> DomainKeyUtil.getDomainKey("example.com", "mail"));
        }
    }

    @Test
    void retainsDnsKeyCompatibilityChecks() throws Exception {
        final DomainKey domainKey = domainKey(rsaKey(), "Sender");
        final Email email = email("Sender@EXAMPLE.COM", config(rsaKey(), "example.com"));

        try (MockedStatic<DomainKeyUtil> dns = mockStatic(DomainKeyUtil.class)) {
            dns.when(() -> DomainKeyUtil.getDomainKey("example.com", "mail")).thenReturn(domainKey);

            assertThatThrownBy(() -> EmailConverter.emailToMimeMessage(email))
                    .hasStackTraceContaining("Incompatible private and public key");
            dns.verify(() -> DomainKeyUtil.getDomainKey("example.com", "mail"));
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

        try (MockedStatic<DomainKeyUtil> dns = mockStatic(DomainKeyUtil.class)) {
            final MimeMessage signed = EmailConverter.emailToMimeMessage(email);

            dns.verifyNoInteractions();
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
        final List<Header> headers = new ArrayList<>();
        final Enumeration<Header> allHeaders = signed.getAllHeaders();
        while (allHeaders.hasMoreElements()) {
            headers.add(allHeaders.nextElement());
        }
        Collections.reverse(headers);
        final StringBuilder canonical = new StringBuilder();
        for (String name : dkimTag(signed, "h").split(":")) {
            final Header selected = headers.stream().filter(header -> name.equalsIgnoreCase(header.getName()))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing signed header " + name));
            headers.remove(selected);
            canonical.append(relaxedHeader(selected.getName(), selected.getValue())).append("\r\n");
        }
        canonical.append(relaxedHeader("DKIM-Signature", signed.getHeader("DKIM-Signature", null)
                .replaceFirst("b=[^;]*$", "b=")));

        final Signature verifier = ed25519
                ? Signature.getInstance("Ed25519", new BouncyCastleProvider()) : Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getMimeDecoder().decode(dkimTag(signed, "b")))).isTrue();
    }

    private static String relaxedHeader(final String name, final String value) {
        return name.toLowerCase(Locale.ROOT) + ":" + value.replaceAll("[\\r\\n \\t]+", " ").trim();
    }
}
