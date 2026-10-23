package org.simplejavamail.internal.dkimsupport;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import testutil.ConfigLoaderTestHelper;
import testutil.DkimSubmissionAssertions;

class DkimTransportTest {
    @ParameterizedTest
    @CsvSource({"SHA256_WITH_RSA,false", "SHA256_WITH_ED25519,false", "SHA256_WITH_RSA,true", "SHA256_WITH_ED25519,true"})
    void preservesSignaturesAndProtectedBytesThroughActualSubmission(final String algorithm, final boolean smime) throws Exception {
        final KeyPairGenerator generator = algorithm.endsWith("ED25519")
                ? KeyPairGenerator.getInstance("Ed25519", new BouncyCastleProvider()) : KeyPairGenerator.getInstance("RSA");
        if (!algorithm.endsWith("ED25519")) { generator.initialize(1024); }
        final KeyPair key = generator.generateKeyPair();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final org.simplejavamail.api.email.EmailPopulatingBuilder builder = factory.emailBuilder().startingBlank()
                .from("sender@supersecret-testing-domain.com").to("receiver@example.net").withSubject("protected wire")
                .withPlainText("body\r\n.line requiring transparency\r\n")
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(key.getPrivate().getEncoded())
                        .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("mail").signingAlgorithm(algorithm).build());
        if (smime) {
            builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(
                    new File(getClass().getResource("/pkcs12/smime_keystore.pkcs12").toURI()),
                    "letmein", "smime_test_user_alias_rsa", "letmein").build());
            builder.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(
                    new File(getClass().getResource("/pkcs12/smime_test_user.pem.standard.crt").toURI())).build());
        }
        final Email email = builder.buildEmailCompletedWithDefaultsAndOverrides();
        try (Mailer mailer = factory.mailerBuilder().withSMTPServer("localhost", 25).buildMailer()) {
            final PreparedMail prepared = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(mailer.getSession(), email);
            DkimSubmissionAssertions.assertPreserved(prepared, mailer.getSession(), key.getPublic());
        }
    }
}
