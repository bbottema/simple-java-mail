package org.simplejavamail.openpgp;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBEKeyEncryptionMethodGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgpainless.key.parsing.KeyRingReader;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.OpenPgpMode;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpDecryptionEvidenceTest {

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();
    private static final char[] PASSPHRASE = "openpgpjs-fixture-passphrase".toCharArray();
    private static final byte[] CONTENT = ("Content-Type: text/plain; charset=US-ASCII\r\n\r\n"
            + "This content was never encrypted.\r\n").getBytes(US_ASCII);
    private static OpenPgpReceiveConfig receiving;
    private static PGPSecretKey signingKey;

    @BeforeAll
    static void readFixtureKeys() throws Exception {
        final byte[] secretKey = resource("private-key.asc");
        receiving = OpenPgpReceiveConfig.builder()
                .addDecryptionKeyRing(secretKey, PASSPHRASE)
                .addVerificationKeyRing(resource("public-key.asc"))
                .build();
        signingKey = new KeyRingReader().secretKeyRing(secretKey).getSecretKey();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("unencryptedImports")
    void successfulPacketProcessingDoesNotEstablishDecryption(final PacketShape shape, final ImportRoute route) throws Exception {
        final byte[] input = encryptedMime(unencryptedPackets(shape));

        final Email parsed = route.read(input, receiving);

        final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
        assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
        assertThat(details.getOpenPgpMode()).isEqualTo(OpenPgpMode.ENCRYPTED);
        assertThat(details.getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(details.getSignatures()).isEmpty();
        assertThat(details.getEncryptionAlgorithm()).isNull();
        assertThat(details.getFailureReason()).isNotBlank();
        assertThat(details.getOriginalProtectedMessage()).containsExactly(input);
        assertThat(parsed.getPlainText()).isNull();
        assertThat(parsed.getHTMLText()).isNull();
    }

    private static Stream<Arguments> unencryptedImports() {
        return Arrays.stream(PacketShape.values()).flatMap(shape -> Arrays.stream(ImportRoute.values())
                .map(route -> Arguments.of(shape, route)));
    }

    @ParameterizedTest
    @EnumSource(PacketShape.class)
    void absentReceiveKeysRetainTheExistingUnopenedResult(final PacketShape shape) throws Exception {
        final byte[] input = encryptedMime(unencryptedPackets(shape));

        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(input).toEmail();

        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.KEY_MISSING);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        assertThat(parsed.getPlainText()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void actualEncryptedInputStillEstablishesDecryption(final ImportRoute route) throws Exception {
        final byte[] input = resource("encrypted-mixed.eml");

        final Email parsed = route.read(input, receiving);

        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        assertThat(parsed.getPlainText()).isEqualTo("Héllo from OpenPGP.js");
    }

    @Test
    void passphraseEncryptedPacketsWithoutAMatchingMethodRemainKeyMissing() throws Exception {
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        final PGPEncryptedDataGenerator encryption = new PGPEncryptedDataGenerator(
                new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setProvider(BC));
        encryption.addMethod(new JcePBEKeyEncryptionMethodGenerator("message-passphrase".toCharArray()).setProvider(BC));
        try (OutputStream encrypted = encryption.open(packets, new byte[8192])) {
            encrypted.write(unencryptedPackets(PacketShape.LITERAL));
        }
        final byte[] input = encryptedMime(packets.toByteArray());

        final Email parsed = ImportRoute.FLUENT_EML.read(input, receiving);

        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.KEY_MISSING);
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        assertThat(parsed.getPlainText()).isNull();
    }

    private static byte[] unencryptedPackets(final PacketShape shape) throws Exception {
        final ByteArrayOutputStream packets = new ByteArrayOutputStream();
        final PGPSignatureGenerator signature;
        if (shape == PacketShape.SIGNED_ONLY) {
            signature = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(
                    signingKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(BC));
            signature.init(PGPSignature.BINARY_DOCUMENT, signingKey.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder().setProvider(BC).build(PASSPHRASE)));
            signature.generateOnePassVersion(false).encode(packets);
        } else {
            signature = null;
        }
        try (OutputStream literal = new PGPLiteralDataGenerator().open(packets, PGPLiteralData.BINARY,
                PGPLiteralData.CONSOLE, CONTENT.length, new Date(0))) {
            literal.write(CONTENT);
        }
        if (signature != null) {
            signature.update(CONTENT);
            signature.generate().encode(packets);
        }
        if (shape != PacketShape.COMPRESSED_LITERAL) {
            return packets.toByteArray();
        }
        final ByteArrayOutputStream compressedPackets = new ByteArrayOutputStream();
        try (OutputStream compressed = new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP).open(compressedPackets)) {
            compressed.write(packets.toByteArray());
        }
        return compressedPackets.toByteArray();
    }

    private static byte[] encryptedMime(final byte[] packets) throws Exception {
        final ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
            output.write(packets);
        }
        final String payload = armored.toString(US_ASCII).replace("\r\n", "\n").replace("\n", "\r\n");
        return ("From: sender@example.test\r\nTo: receiver@example.test\r\n"
                + "Subject: Declared encryption\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=evidence\r\n\r\n"
                + "--evidence\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n"
                + "--evidence\r\nContent-Type: application/octet-stream\r\n\r\n"
                + payload + "\r\n--evidence--\r\n").getBytes(US_ASCII);
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = OpenPgpDecryptionEvidenceTest.class.getResourceAsStream("/openpgpjs/" + name)) {
            if (input == null) throw new AssertionError(name);
            return input.readAllBytes();
        }
    }

    private enum PacketShape { LITERAL, COMPRESSED_LITERAL, SIGNED_ONLY }

    private enum ImportRoute {
        FLUENT_EML, STATIC_EML, FLUENT_MIME, STATIC_MIME;

        private Email read(final byte[] input, final OpenPgpReceiveConfig keys) throws Exception {
            switch (this) {
                case FLUENT_EML:
                    return SimpleJavaMail.fromDefaults().converter().fromEml(input).withOpenPgpReceiveConfig(keys).toEmail();
                case STATIC_EML:
                    return EmailConverter.emlToEmailWithOpenPgp(new ByteArrayInputStream(input), keys);
                case FLUENT_MIME:
                    return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(message(input)).withOpenPgpReceiveConfig(keys).toEmail();
                case STATIC_MIME:
                    return EmailConverter.mimeMessageToEmail(message(input), null, keys);
                default:
                    throw new AssertionError(this);
            }
        }

        private static MimeMessage message(final byte[] input) throws Exception {
            return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(input));
        }
    }
}
