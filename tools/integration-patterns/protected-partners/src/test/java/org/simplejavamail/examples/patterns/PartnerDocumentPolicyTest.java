package org.simplejavamail.examples.patterns;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real offline receive/policy/output checks with generated local identities. No SMTP or external trust service. */
class PartnerDocumentPolicyTest {
    private static PartnerDocumentFixtures fixtures;
    private static PartnerDocumentPolicy documents;
    @TempDir Path storage;

    @BeforeAll
    static void configureSyntheticPartners() throws Exception {
        SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        Pkcs12Config approved = identity("Approved synthetic partner");
        Pkcs12Config unapproved = identity("Unapproved synthetic partner");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(new ByteArrayInputStream(approved.getPkcs12StoreData()), approved.getStorePassword());
        X509Certificate certificate = (X509Certificate) keys.getCertificate(approved.getKeyAlias());
        documents = new PartnerDocumentPolicy(mail, approved, certificate);
        fixtures = new PartnerDocumentFixtures(mail, approved, unapproved);
    }

    @Test
    void approvedFullyCoveredDecryptedDocumentIsProcessedAndExactOriginalRetained() throws Exception {
        byte[] input = fixtures.message("approved");
        PartnerDocumentPolicy.Decision decision = documents.receive(input, storage, "approved", true);

        assertEquals("PROCESSED", decision.action());
        assertEquals("SATISFIED", decision.outcome());
        assertEquals("SATISFIED", decision.baseOutcome());
        assertEquals("VERIFIED", decision.signature());
        assertEquals("DECRYPTED", decision.encryption());
        assertTrue(decision.reasons().isEmpty());
        assertArrayEquals("order=local-demo\namount=42\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(storage.resolve("processed/approved.txt")));
        assertTrue(decision.originalPreserved());
        assertArrayEquals(input, Files.readAllBytes(storage.resolve("archive/approved.eml")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unapproved", "unavailable-verification", "unavailable-decryption", "mixed", "outside-coverage"})
    void unacceptableOrUncertainDocumentIsHeldWithoutChangingOriginal(String scenario) throws Exception {
        byte[] input = fixtures.message(scenario);
        PartnerDocumentPolicy.Decision decision = documents.receive(input, storage, scenario, !"unavailable-decryption".equals(scenario));

        assertEquals("HELD", decision.action());
        assertEquals(0, decision.processedBytes());
        assertFalse(Files.exists(storage.resolve("processed").resolve(scenario + ".txt")));
        assertTrue(decision.originalPreserved());
        assertArrayEquals(input, Files.readAllBytes(storage.resolve("archive").resolve(scenario + ".eml")));
        switch (scenario) {
            case "unapproved" -> {
                assertEquals("VERIFIED", decision.signature());
                assertEquals("NOT_SATISFIED", decision.outcome());
                assertTrue(decision.reasons().contains("SIGNER_NOT_APPROVED"));
            }
            case "unavailable-verification" -> {
                assertEquals("UNVERIFIED", decision.signature());
                assertEquals("INDETERMINATE", decision.outcome());
            }
            case "unavailable-decryption" -> assertTrue(decision.reasons().contains("REQUIRED_DOCUMENT_UNAVAILABLE"));
            case "mixed" -> {
                assertEquals("MIXED", decision.signature());
                assertEquals("SATISFIED", decision.baseOutcome());
                assertEquals("NOT_SATISFIED", decision.outcome());
                assertTrue(decision.reasons().contains("STRICT_SIGNATURE_INVALID"));
            }
            case "outside-coverage" -> {
                assertEquals("UNSIGNED", decision.signature());
                assertEquals("NOT_ENCRYPTED", decision.encryption());
                assertEquals("NOT_SATISFIED", decision.outcome());
            }
            default -> throw new AssertionError(scenario);
        }
    }

    private static Pkcs12Config identity(String name) throws Exception {
        var provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=" + name);
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                new JcaX509v3CertificateBuilder(subject, new BigInteger(128, new SecureRandom()).setBit(127),
                        Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(30, ChronoUnit.DAYS)), subject, pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate())));
        char[] password = "synthetic-test-only".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, password);
        keys.setKeyEntry("partner", pair.getPrivate(), password, new Certificate[] {certificate});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        keys.store(bytes, password);
        return Pkcs12Config.builder().pkcs12Store(bytes.toByteArray()).storePassword(password)
                .keyAlias("partner").keyPassword(password).build();
    }
}
