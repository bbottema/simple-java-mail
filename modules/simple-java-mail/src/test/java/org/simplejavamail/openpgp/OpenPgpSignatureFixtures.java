package org.simplejavamail.openpgp;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.key.generation.KeySpecBuilder;
import org.pgpainless.key.generation.type.rsa.RSA;
import org.pgpainless.key.generation.type.rsa.RsaLength;
import org.pgpainless.key.parsing.KeyRingReader;
import org.pgpainless.util.Passphrase;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Builds real multi-signature input packets independently of SJM's outbound MIME pipeline. */
final class OpenPgpSignatureFixtures {
    static final byte[] CONTENT = ("Content-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n\r\nComplete signature facts\r\n").getBytes(StandardCharsets.US_ASCII);
    private static final BouncyCastleProvider BC = new BouncyCastleProvider();
    static Key first;
    static Key second;

    static void initialize() throws Exception {
        if (first != null) return;
        first = new Key(resource("private-key.asc"), resource("public-key.asc"), "openpgpjs-fixture-passphrase".toCharArray());
        final Passphrase passphrase = new Passphrase("second-test-key".toCharArray());
        try {
            final OpenPGPKey key = new PGPainless().buildKey()
                    .setPrimaryKey(new KeySpecBuilder(RSA.withLength(RsaLength._2048), KeyFlag.CERTIFY_OTHER, KeyFlag.SIGN_DATA).build())
                    .addUserId("Second Test Signer <second@example.test>").setPassphrase(passphrase).build();
            second = new Key(key.getEncoded(), key.toCertificate().getEncoded(), "second-test-key".toCharArray());
        } finally {
            passphrase.clear();
        }
    }

    static OpenPgpReceiveConfig receiving(final Key... verificationKeys) {
        final var builder = OpenPgpReceiveConfig.builder().addDecryptionKeyRing(first.secret, first.passphrase);
        for (Key key : verificationKeys) builder.addVerificationKeyRing(key.publicRing);
        return builder.build();
    }

    static byte[] detached(final Key... signers) throws Exception {
        return detached(CONTENT, -1, signers);
    }

    static byte[] detachedWithInvalid(final int invalid, final Key... signers) throws Exception {
        return detached(CONTENT, invalid, signers);
    }

    static byte[] detached(final byte[] content, final int invalid, final Key... signers) throws Exception {
        return signedMime(content, detachedPayload(content, invalid, signers));
    }

    private static byte[] detachedPayload(final byte[] content, final int invalid, final Key... signers) throws Exception {
        final List<PGPSignatureGenerator> generators = generators(signers);
        final ByteArrayOutputStream signatures = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(signatures)) {
            for (int i = 0; i < generators.size(); i++) {
                final PGPSignatureGenerator generator = generators.get(i);
                generator.update(i == invalid ? different(content) : content);
                final PGPSignature signature = generator.generate();
                assertSignature(signature, signers[i], content, i != invalid);
                signature.encode(armor);
            }
        }
        return signatures.toByteArray();
    }

    static byte[] combined(final Key... signers) throws Exception {
        return combined(CONTENT, -1, signers);
    }

    static byte[] combinedWithInvalid(final int invalid, final Key... signers) throws Exception {
        return combined(CONTENT, invalid, signers);
    }

    static byte[] combined(final byte[] content, final int invalid, final Key... signers) throws Exception {
        return combined(content, invalid, -1, signers);
    }

    static byte[] combinedWithMalformedSignature() throws Exception {
        return combined(CONTENT, -1, 0, first);
    }

    private static byte[] combined(final byte[] content, final int invalid, final int malformed, final Key... signers) throws Exception {
        final List<PGPSignatureGenerator> generators = generators(signers, malformed);
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        for (int i = 0; i < generators.size(); i++) {
            generators.get(i).generateOnePassVersion(i + 1 < generators.size()).encode(packets);
        }
        try (OutputStream literal = new PGPLiteralDataGenerator().open(packets, PGPLiteralData.BINARY,
                PGPLiteralData.CONSOLE, content.length, new Date())) {
            literal.write(content);
        }
        for (int i = generators.size() - 1; i >= 0; i--) {
            final PGPSignatureGenerator generator = generators.get(i);
            generator.update(i == invalid ? different(content) : content);
            final PGPSignature signature = generator.generate();
            assertSignature(signature, signers[i], content, i != invalid);
            signature.encode(packets);
        }
        return encryptedMime(encrypt(packets.toByteArray()));
    }

    static byte[] nested(final boolean packetOutside, final Key... signers) throws Exception {
        final byte[] inner = packetOutside ? detached(signers) : combined(signers);
        final byte[] entity = mimeEntity(inner);
        return packetOutside ? combined(entity, -1, first) : detached(entity, -1, first);
    }

    static byte[] corruptIntegrity(final byte[] source) throws Exception {
        final String message = new String(source, StandardCharsets.US_ASCII);
        final int begin = message.indexOf("-----BEGIN PGP MESSAGE-----");
        final int end = message.indexOf("-----END PGP MESSAGE-----", begin) + "-----END PGP MESSAGE-----".length();
        final byte[] payload;
        try (InputStream decoded = PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(
                message.substring(begin, end).getBytes(StandardCharsets.US_ASCII)))) { payload = decoded.readAllBytes(); }
        payload[payload.length - 1] ^= 1;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) { armor.write(payload); }
        return (message.substring(0, begin) + crlf(output.toByteArray()) + message.substring(end)).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] compressedDetached(final boolean visible) throws Exception {
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        if (visible) packets.write(binarySignature(first));
        final PGPCompressedDataGenerator compression = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
        try (OutputStream compressed = compression.open(packets)) { compressed.write(binarySignature(second)); }
        if (visible) packets.write(binarySignature(first));
        return signedMime(CONTENT, armor(packets.toByteArray()));
    }

    static byte[] beyondPacketInspectionLimit() throws Exception {
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        packets.write(binarySignature(first));
        for (int i = 0; i < 10_001; i++) packets.write(new byte[] {(byte) 0xca, 3, 'P', 'G', 'P'});
        packets.write(binarySignature(second));
        return signedMime(CONTENT, armor(packets.toByteArray()));
    }

    private static byte[] binarySignature(final Key key) throws Exception {
        try (InputStream decoded = PGPUtil.getDecoderStream(new java.io.ByteArrayInputStream(detachedPayload(CONTENT, -1, key)))) {
            return decoded.readAllBytes();
        }
    }

    private static byte[] armor(final byte[] payload) throws Exception {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ArmoredOutputStream output = new ArmoredOutputStream(result)) { output.write(payload); }
        return result.toByteArray();
    }

    private static byte[] mimeEntity(final byte[] source) {
        final String message = new String(source, StandardCharsets.US_ASCII);
        return message.substring(message.indexOf("Content-Type:")).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] different(final byte[] content) {
        final byte[] other = Arrays.copyOf(content, content.length);
        other[0] ^= 1;
        return other;
    }

    private static List<PGPSignatureGenerator> generators(final Key[] signers) throws Exception {
        return generators(signers, -1);
    }

    private static List<PGPSignatureGenerator> generators(final Key[] signers, final int malformed) throws Exception {
        final List<PGPSignatureGenerator> result = new ArrayList<>();
        for (Key signer : signers) {
            final PGPSecretKey key = signingKey(signer);
            final PGPSignatureGenerator generator = new PGPSignatureGenerator(
                    new JcaPGPContentSignerBuilder(key.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC));
            generator.init(PGPSignature.BINARY_DOCUMENT, signer.privateSigningKey);
            final PGPSignatureSubpacketGenerator hashed = new PGPSignatureSubpacketGenerator();
            hashed.setIssuerFingerprint(false, key.getPublicKey());
            if (result.size() == malformed) hashed.addNotationData(true, false, "unsupported@example.test", "opaque");
            generator.setHashedSubpackets(hashed.generate());
            final PGPSignatureSubpacketGenerator unhashed = new PGPSignatureSubpacketGenerator();
            unhashed.setIssuerKeyID(false, key.getKeyID());
            generator.setUnhashedSubpackets(unhashed.generate());
            result.add(generator);
        }
        return result;
    }

    private static void assertSignature(final PGPSignature signature, final Key signer, final byte[] content,
            final boolean valid) throws Exception {
        signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BC), signingKey(signer).getPublicKey());
        signature.update(content);
        assertThat(signature.verify()).isEqualTo(valid);
    }

    private static byte[] encrypt(final byte[] packets) throws Exception {
        final PGPPublicKeyRing publicRing = new KeyRingReader().publicKeyRing(first.publicRing);
        PGPPublicKey recipient = null;
        for (Iterator<PGPPublicKey> keys = publicRing.getPublicKeys(); keys.hasNext();) {
            final PGPPublicKey key = keys.next();
            if (!key.isMasterKey() && key.isEncryptionKey()) { recipient = key; break; }
        }
        if (recipient == null) throw new AssertionError("Fixture needs an encryption subkey");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(bytes)) {
            final PGPEncryptedDataGenerator encryption = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true)
                            .setSecureRandom(new SecureRandom()).setProvider(BC));
            encryption.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider(BC));
            try (OutputStream encrypted = encryption.open(armor, new byte[8192])) { encrypted.write(packets); }
        }
        return bytes.toByteArray();
    }

    private static byte[] signedMime(final byte[] content, final byte[] signature) {
        return (headers("multipart/signed; protocol=\"application/pgp-signature\"; micalg=pgp-sha256; boundary=signed-results")
                + "--signed-results\r\n" + new String(content, StandardCharsets.US_ASCII)
                + "\r\n--signed-results\r\nContent-Type: application/pgp-signature\r\n\r\n"
                + crlf(signature) + "\r\n--signed-results--\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] encryptedMime(final byte[] ciphertext) {
        return (headers("multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=encrypted-results")
                + "--encrypted-results\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n"
                + "--encrypted-results\r\nContent-Type: application/octet-stream\r\n\r\n"
                + crlf(ciphertext) + "\r\n--encrypted-results--\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static String headers(final String contentType) {
        return "From: sender@example.test\r\nTo: receiver@example.test\r\nMessage-ID: <all-signatures@example.test>\r\n"
                + "Subject: Complete signature facts\r\nMIME-Version: 1.0\r\nContent-Type: " + contentType + "\r\n\r\n";
    }

    private static String crlf(final byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    private static PGPSecretKey signingKey(final Key key) throws Exception {
        final PGPSecretKeyRing ring = new KeyRingReader().secretKeyRing(key.secret);
        for (Iterator<PGPSecretKey> keys = ring.getSecretKeys(); keys.hasNext();) {
            final PGPSecretKey candidate = keys.next();
            if (candidate.isSigningKey()) return candidate;
        }
        throw new AssertionError("No fixture signing key");
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = OpenPgpSignatureFixtures.class.getResourceAsStream("/openpgpjs/" + name)) {
            if (input == null) throw new AssertionError(name);
            return input.readAllBytes();
        }
    }

    static final class Key {
        final byte[] secret;
        final byte[] publicRing;
        final char[] passphrase;
        private final PGPPrivateKey privateSigningKey;
        private Key(final byte[] secret, final byte[] publicRing, final char[] passphrase) throws Exception {
            this.secret = secret;
            this.publicRing = publicRing;
            this.passphrase = passphrase;
            privateSigningKey = signingKey(this).extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider(BC).build(passphrase));
        }
    }
}
