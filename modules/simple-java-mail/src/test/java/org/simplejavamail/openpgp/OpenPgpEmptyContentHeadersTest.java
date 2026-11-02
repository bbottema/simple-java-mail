package org.simplejavamail.openpgp;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgpainless.key.parsing.KeyRingReader;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpEmptyContentHeadersTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final BouncyCastleProvider BC = new BouncyCastleProvider();
    private static final char[] PASSPHRASE = "openpgpjs-fixture-passphrase".toCharArray();
    private static PGPSecretKey signingKey;
    private static PGPPublicKey encryptionKey;
    private static OpenPgpReceiveConfig receiving;

    @BeforeAll
    static void readKeys() throws Exception {
        final byte[] privateKey = resource("private-key.asc");
        final byte[] publicKey = resource("public-key.asc");
        signingKey = new KeyRingReader().secretKeyRing(privateKey).getSecretKey();
        final Iterator<PGPPublicKey> keys = new KeyRingReader().publicKeyRing(publicKey).getPublicKeys();
        while (keys.hasNext()) {
            final PGPPublicKey candidate = keys.next();
            if (candidate.isEncryptionKey()) encryptionKey = candidate;
        }
        assertThat(encryptionKey).isNotNull();
        receiving = OpenPgpReceiveConfig.builder().addVerificationKeyRing(publicKey)
                .addDecryptionKeyRing(privateKey, PASSPHRASE).build();
    }

    @ParameterizedTest(name = "{0}, {1}, body={2}")
    @MethodSource("emptyHeaderEntities")
    void protectedContentCanHaveAnEmptyHeaderArea(final Protection protection, final String firstLineEnding,
            final String body) throws Exception {
        final byte[] entity = (firstLineEnding + body).getBytes(US_ASCII);
        final byte[] incoming = protection == Protection.SIGNED ? signed(entity) : encrypted(entity);
        for (boolean stream : new boolean[] {false, true}) {
            final TrackingInputStream caller = new TrackingInputStream(incoming);
            final Email parsed = stream ? EmailConverter.emlToEmailWithOpenPgp(caller, receiving)
                    : MAIL.converter().fromEml(incoming).withOpenPgpReceiveConfig(receiving).toEmail();

            assertThat(parsed.getPlainText()).isEqualTo(body);
            assertThat(parsed.getHeaders()).doesNotContainKey("X-Visible");
            assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus())
                    .isEqualTo(protection == Protection.SIGNED ? SignatureStatus.VALID : SignatureStatus.NOT_PRESENT);
            assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus())
                    .isEqualTo(protection == Protection.SIGNED ? DecryptionStatus.NOT_ENCRYPTED : DecryptionStatus.DECRYPTED);
            assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(incoming);
            assertThat(caller.closed).isFalse();
        }
    }

    private static Stream<Arguments> emptyHeaderEntities() {
        return Arrays.stream(Protection.values()).flatMap(protection -> Stream.of("\r\n", "\n")
                .flatMap(ending -> Stream.of("Hello", "X-Visible: this is body\r\n\r\nSecond paragraph", "\r\nHello", "")
                        .map(body -> Arguments.of(protection, ending, body))));
    }

    private static byte[] signed(final byte[] content) throws Exception {
        final byte[] canonical = new String(content, US_ASCII).replace("\r\n", "\n").replace("\n", "\r\n").getBytes(US_ASCII);
        final PGPSignatureGenerator signature = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(
                signingKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC));
        signature.init(PGPSignature.BINARY_DOCUMENT, signingKey.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().setProvider(BC).build(PASSPHRASE)));
        signature.update(canonical);
        final ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
            signature.generate().encode(output);
        }
        return (outer("multipart/signed; protocol=\"application/pgp-signature\"; micalg=pgp-sha256; boundary=emptyheaders")
                + "--emptyheaders\r\n" + new String(content, US_ASCII)
                + "\r\n--emptyheaders\r\nContent-Type: application/pgp-signature\r\n\r\n"
                + crlf(armored) + "\r\n--emptyheaders--\r\n").getBytes(US_ASCII);
    }

    private static byte[] encrypted(final byte[] content) throws Exception {
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        final PGPEncryptedDataGenerator encryption = new PGPEncryptedDataGenerator(new JcePGPDataEncryptorBuilder(
                SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setProvider(BC));
        encryption.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey).setProvider(BC));
        try (OutputStream encrypted = encryption.open(packets, new byte[8192]);
             OutputStream literal = new PGPLiteralDataGenerator().open(encrypted, PGPLiteralData.BINARY,
                     PGPLiteralData.CONSOLE, content.length, new Date(0))) {
            literal.write(content);
        }
        final ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
            output.write(packets.toByteArray());
        }
        return (outer("multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=emptyheaders")
                + "--emptyheaders\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n"
                + "--emptyheaders\r\nContent-Type: application/octet-stream\r\n\r\n"
                + crlf(armored) + "\r\n--emptyheaders--\r\n").getBytes(US_ASCII);
    }

    private static String outer(final String type) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Empty content headers\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: " + type + "\r\n\r\n";
    }

    private static String crlf(final ByteArrayOutputStream output) {
        return output.toString(US_ASCII).replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = OpenPgpEmptyContentHeadersTest.class.getResourceAsStream("/openpgpjs/" + name)) {
            if (input == null) throw new AssertionError(name);
            return input.readAllBytes();
        }
    }

    private enum Protection { SIGNED, ENCRYPTED }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream(final byte[] input) { super(input); }
        @Override public void close() { closed = true; }
    }
}
