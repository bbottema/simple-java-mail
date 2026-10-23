package org.simplejavamail.examples.support;

import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Sign, encrypt and inspect the synthetic documents used by these examples.
 * All applications use the generated demonstration identity, never a trusted partner certificate or production key.
 */
public final class ProtectedContent {
    private ProtectedContent() {
    }

    public static SmimeSigningConfig signing() {
        var identity = Pkcs12Config.builder()
                .pkcs12Store(Path.of("/opt/demo/partner.p12"))
                .storePassword("local-demo-only")
                .keyAlias("partner")
                .keyPassword("local-demo-only")
                .build();
        return SmimeSigningConfig.builder()
                .pkcs12Config(identity)
                .build();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static SmimeEncryptionConfig encryption() {
        return SmimeEncryptionConfig.builder().x509Certificate(Path.of("/opt/demo/partner.crt")).build();
    }

    public static Email readProtected(byte[] bytes) {
        return SimpleJavaMail.fromDefaults().converter()
                .fromEml(new java.io.ByteArrayInputStream(bytes))
                .withSmimeDecryption(signing().getPkcs12Config())
                .withSession(jakarta.mail.Session.getInstance(new java.util.Properties()))
                .toEmail();
    }
}
