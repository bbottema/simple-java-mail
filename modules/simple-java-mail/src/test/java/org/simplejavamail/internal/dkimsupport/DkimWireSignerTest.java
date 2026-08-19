/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.dkimsupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.Security;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.internal.util.FinalizedMimeMessage.ProtectionState.NONE;

class DkimWireSignerTest {

    private static final String IDENTITY = "sender@supersecret-testing-domain.com";
    private static final Pattern BODY_HASH = Pattern.compile("(?:^|;)\\s*bh=([^;\\s]+)");

    @Test
    void signingWithRsaAndEd25519DoesNotChangeTheJvmProviderList() throws Exception {
        final Provider[] providersBeforeSigning = Security.getProviders();

        sign(rawMessage("body\r\n"), rsaKey(), "SHA256_WITH_RSA");
        sign(rawMessage("body\r\n"), ed25519Key(), "SHA256_WITH_ED25519");

        assertThat(Security.getProviders()).containsExactly(providersBeforeSigning);
    }

    @Test
    void preservesDkimWireRulesForRepeatedHeadersFoldingAndEmptyBodies() throws Exception {
        final MimeMessage signed = sign(rawMessage(""), rsaKey(), "SHA256_WITH_RSA");
        final String dkimHeader = unfoldedDkimHeader(signed);

        assertThat(dkimHeader)
                .contains("a=rsa-sha256;")
                .contains("h=Content-Transfer-Encoding:Content-Type:MIME-Version:References:References:")
                .contains("bh=47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=;");

        final String wireMessage = new String(((FinalizedMimeMessage) signed).getSerializedBytes(), StandardCharsets.ISO_8859_1);
        final String dkimHeaderBlock = wireMessage.substring(0, wireMessage.indexOf("\r\nFrom:"));
        assertThat(dkimHeaderBlock).contains("\r\n\t");
        assertThat(dkimHeaderBlock.split("\r\n")).allMatch(line -> line.length() <= 78);
    }

    @Test
    void hashesNonAsciiBodiesAfterRelaxedCanonicalization() throws Exception {
        final MimeMessage signed = sign(rawMessage("héllo \t\r\nsecond  line\t\r\n\r\n"), rsaKey(), "SHA256_WITH_RSA");

        assertThat(bodyHash(unfoldedDkimHeader(signed)))
                .isEqualTo("wvAVfnfxoXRVE4yjfVkOzIYPyQ925aDc5t9tYnJySXM=");
    }

    private static MimeMessage sign(final String rawMessage, final KeyPair keyPair, final String algorithm) throws Exception {
        final Session session = Session.getInstance(new Properties());
        final FinalizedMimeMessage message = FinalizedMimeMessage.fromMessageBytes(
                session, rawMessage.getBytes(StandardCharsets.UTF_8), NONE);
        final DkimConfig config = DkimConfig.builder()
                .dkimPrivateKeyData(keyPair.getPrivate().getEncoded())
                .dkimSigningDomain("supersecret-testing-domain.com")
                .dkimSelector("selector")
                .signingAlgorithm(algorithm)
                .build();

        return new DKIMSigner().signMessageWithDKIM(
                mock(Email.class), message, config, new Recipient(null, IDENTITY, null, null));
    }

    private static KeyPair rsaKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private static KeyPair ed25519Key() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider());
        return generator.generateKeyPair();
    }

    private static String unfoldedDkimHeader(final MimeMessage message) throws Exception {
        return message.getHeader("DKIM-Signature", null).replaceAll("\\r\\n[ \\t]+", "");
    }

    private static String bodyHash(final String dkimHeader) {
        final Matcher matcher = BODY_HASH.matcher(dkimHeader);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String rawMessage(final String body) {
        return "From: " + IDENTITY + "\r\n"
                + "To: receiver@example.com\r\n"
                + "Subject: DKIM wire test\r\n"
                + "Date: Thu, 1 Jan 1970 00:00:00 +0000\r\n"
                + "Message-ID: <dkim-wire-test@example.com>\r\n"
                + "References: <first@example.com>\r\n"
                + "References: <second@example.com>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + body;
    }
}
