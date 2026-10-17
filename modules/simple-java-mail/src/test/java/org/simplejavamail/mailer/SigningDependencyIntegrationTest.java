package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.EmailBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jakarta.mail.Message.RecipientType.TO;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT8;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SigningDependencyIntegrationTest {

    @BeforeEach
    void clearDefaults() {
        ConfigLoaderTestHelper.clearConfigProperties();
    }

    @Test
    void dkimHashesTheTransmittedUnicodeBodyAndCountsOctets() throws Exception {
        final String text = "Gr\u00fc\u00dfe, \u6771\u4eac and \ud83d\ude80\r\n";
        final KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        final Email email = EmailBuilder.startingBlank()
                    .from("sender@supersecret-testing-domain.com")
                    .withRecipients(new Recipient(null, "recipient@example.org", TO, null))
                    .withSubject("Unicode DKIM body")
                    .withPlainText(text)
                    .withContentTransferEncoding(BIT8)
                    .signWithDomainKey(DkimConfig.builder()
                            .dkimPrivateKeyData(keys.generateKeyPair().getPrivate().getEncoded())
                            .dkimSigningDomain("supersecret-testing-domain.com")
                            .dkimSelector("test")
                            .bodyCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                            .useLengthParam(true)
                            .build())
                    .buildEmail();

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        EmailConverter.emailToMimeMessage(email).writeTo(output);
        final byte[] wire = output.toByteArray();
        final int bodyOffset = new String(wire, ISO_8859_1).indexOf("\r\n\r\n") + 4;
        assertThat(bodyOffset).isGreaterThan(3);
        final byte[] body = Arrays.copyOfRange(wire, bodyOffset, wire.length);
        assertThat(body).isEqualTo(text.getBytes(UTF_8));

        final MimeMessage parsed = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire));
        final String signature = parsed.getHeader("DKIM-Signature", null).replaceAll("\\s+", "");
        assertThat(dkimTag(signature, "bh"))
                .isEqualTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(dkimTag(signature, "l")).isEqualTo(Integer.toString(body.length));
    }

    @Test
    void smimeSigningPreservesRepeatedHeadersAndUnicodeContent() throws Exception {
        final String text = "Gr\u00fc\u00dfe from \u6771\u4eac";
        final Email email = EmailBuilder.startingBlank()
                .from("sender@example.org")
                .withRecipients(new Recipient(null, "recipient@example.org", TO, null))
                .withSubject("S/MIME content preservation")
                .withPlainText(text)
                .withHeader("X-Trace", "first")
                .withHeader("X-Trace", "second")
                .withAttachment("note.txt", text.getBytes(UTF_8), "text/plain")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build())
                .buildEmail();

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        EmailConverter.emailToMimeMessage(email).writeTo(output);
        final MimeMessage parsed = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(output.toByteArray()));
        assertThat(parsed.getHeader("X-Trace")).containsExactly("first", "second");

        final Email restored = EmailConverter.mimeMessageToEmail(parsed);
        assertThat(restored.getOriginalSmimeDetails().getSmimeSignatureValid()).isTrue();
        assertThat(restored.getPlainText()).isEqualTo(text);
        assertThat(restored.getAttachments().stream()
                .filter(attachment -> "note.txt".equals(attachment.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing signed attachment"))
                .readAllData()).isEqualTo(text);
    }

    private static String dkimTag(String signature, String name) {
        final Matcher matcher = Pattern.compile("(?:^|;)" + name + "=([^;]+)").matcher(signature);
        assertThat(matcher.find()).as("DKIM %s tag", name).isTrue();
        return matcher.group(1);
    }
}
