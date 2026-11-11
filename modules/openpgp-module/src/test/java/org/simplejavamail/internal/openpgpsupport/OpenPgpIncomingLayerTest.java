package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.KeyFlag;
import org.pgpainless.key.generation.KeySpecBuilder;
import org.pgpainless.key.generation.type.rsa.RSA;
import org.pgpainless.key.generation.type.rsa.RsaLength;
import org.pgpainless.key.parsing.KeyRingReader;
import org.pgpainless.util.Passphrase;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpIncomingLayerTest {
    private static final Session SESSION = Session.getInstance(new Properties());
    private static final byte[] MESSAGE = ("From: sender@example.test\r\nSubject: Public subject\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nPartner document\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final char[] PASSPHRASE = "synthetic-pgp-passphrase".toCharArray();
    private static byte[] secret;
    private static byte[] certificate;
    private static long signingSubkey;
    private static String signingFingerprint;

    @BeforeAll
    static void createSyntheticSigningAndEncryptionSubkeys() throws Exception {
        final PGPainless pgpainless = new PGPainless();
        final Passphrase passphrase = new Passphrase(PASSPHRASE.clone());
        try {
            final OpenPGPKey key = pgpainless.buildKey()
                    .setPrimaryKey(new KeySpecBuilder(RSA.withLength(RsaLength._2048), KeyFlag.CERTIFY_OTHER).build())
                    .addSubkey(new KeySpecBuilder(RSA.withLength(RsaLength._2048), KeyFlag.SIGN_DATA).build())
                    .addSubkey(new KeySpecBuilder(RSA.withLength(RsaLength._2048), KeyFlag.ENCRYPT_COMMS, KeyFlag.ENCRYPT_STORAGE).build())
                    .addUserId("Synthetic Partner <partner@example.test>").setPassphrase(passphrase).build();
            secret = key.getEncoded();
            certificate = key.toCertificate().getEncoded();
            signingSubkey = pgpainless.inspect(key).getSigningSubkeys().stream().filter(signing -> !signing.isPrimaryKey())
                    .findFirst().orElseThrow().getKeyIdentifier().getKeyId();
            signingFingerprint = Hex.toHexString(new KeyRingReader().publicKeyRing(certificate).getPublicKey(signingSubkey).getFingerprint())
                    .toUpperCase(Locale.ROOT);
        } finally {
            passphrase.clear();
        }
    }

    @Test
    void verifiedSignatureUsesTheActualSigningSubkeyFingerprintAndIsNotAppliedToOuterHeaders() throws Exception {
        final ProtectionLayerResult layer = new OpenPgpSupport().openIncomingLayer(SESSION, signed(), verification());

        assertThat(layer.isOpened()).isTrue();
        assertThat(layer.isAuthoredHeaders()).isFalse();
        assertThat(layer.isInspectionComplete()).isTrue();
        assertThat(layer.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
            assertThat(signature.getSigningKeyFingerprint()).isEqualTo(signingFingerprint);
            assertThat(signature.getSigningKeyFingerprint()).hasSizeGreaterThan(16);
        });
        assertThat(layer.getOpenedEntity().getContent()).isEqualTo("Partner document\r\n");
        assertThat(layer.getEffectiveMimeMessage().getSubject()).isEqualTo("Public subject");
    }

    @Test
    void unavailableVerificationPreservesReadableContentWithoutInventingAKey() throws Exception {
        final ProtectionLayerResult layer = new OpenPgpSupport().openIncomingLayer(SESSION, signed(), null);

        assertThat(layer.isOpened()).isTrue();
        assertThat(layer.getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.UNVERIFIED);
            assertThat(signature.getSigningKeyFingerprint()).isNull();
        });
    }

    @Test
    void unusableExplicitVerificationMaterialRemainsAnOperationFailure() throws Exception {
        final OpenPgpReceiveConfig invalid = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing("invalid key ring".getBytes(StandardCharsets.US_ASCII)).build();

        assertThatThrownBy(() -> new OpenPgpSupport().openIncomingLayer(SESSION, signed(), invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("configured OpenPGP verification key");
    }

    @Test
    void realDecryptionStopsBeforeAnEnclosedSignatureLayerAndMissingKeysRemainUnavailable() throws Exception {
        final byte[] signedBytes = MimeEntitySupport.serialize(signed());
        final PgpainlessOpenPgpAdapter adapter = new PgpainlessOpenPgpAdapter();
        final byte[] ciphertext = adapter.encrypt(MimeEntitySupport.contentEntity(signedBytes),
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(certificate).build());
        final MimeMessage encrypted = message(MimeEntitySupport.wrapEncrypted(signedBytes, ciphertext, "synthetic-encrypted"));
        final OpenPgpSupport module = new OpenPgpSupport();

        final ProtectionLayerResult unavailable = module.openIncomingLayer(SESSION, encrypted, null);
        assertThat(unavailable.isOpened()).isFalse();
        assertThat(unavailable.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));

        final ProtectionLayerResult outer = module.openIncomingLayer(SESSION, encrypted, receiving(PASSPHRASE));
        assertThat(outer.isOpened()).isTrue();
        assertThat(outer.getSignatures()).isEmpty();
        assertThat(outer.getEffectiveMimeMessage().isMimeType("multipart/signed")).isTrue();
        assertThat(outer.getDecryptions()).singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
                    assertThat(attempt.getRecipientKeyIds()).hasSize(1);
                });
        final ProtectionLayerResult inner = module.openIncomingLayer(SESSION, outer.getEffectiveMimeMessage(), verification());
        assertThat(inner.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
    }

    @Test
    void configuredWrongPassphraseAndApplicationCallbackFailureAreNotContentProblems() throws Exception {
        final PgpainlessOpenPgpAdapter adapter = new PgpainlessOpenPgpAdapter();
        final byte[] ciphertext = adapter.encrypt(MimeEntitySupport.contentEntity(MESSAGE),
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(certificate).build());
        final MimeMessage encrypted = message(MimeEntitySupport.wrapEncrypted(MESSAGE, ciphertext, "synthetic-encrypted"));
        assertThatThrownBy(() -> new OpenPgpSupport().openIncomingLayer(SESSION, encrypted, receiving("incorrect".toCharArray())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("could not be unlocked");

        final IllegalStateException applicationFailure = new IllegalStateException("Synthetic key service unavailable");
        final OpenPgpReceiveConfig callback = OpenPgpReceiveConfig.builder().addDecryptionKeyRing(secret, PASSPHRASE)
                .passphraseProvider(keyId -> { throw applicationFailure; }).build();
        assertThatThrownBy(() -> new OpenPgpSupport().openIncomingLayer(SESSION, encrypted, callback)).isSameAs(applicationFailure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactReceivedLineEndingsSurviveTheNewReader(final boolean lineFeedOnly) throws Exception {
        final byte[] canonical = MimeEntitySupport.serialize(signed());
        final byte[] input = lineFeedOnly ? new String(canonical, StandardCharsets.US_ASCII)
                .replace("\r\n", "\n").getBytes(StandardCharsets.US_ASCII) : canonical;
        final MimeMessage source = message(input);

        final ProtectionLayerResult opened = new OpenPgpSupport().openIncomingLayer(SESSION, source, verification());

        assertThat(opened.getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(((FinalizedMimeMessage) source).getSerializedBytes()).containsExactly(input);
    }

    @Test
    void malformedSignatureTransferEncodingDoesNotDiscardItsReadableSignedEntity() throws Exception {
        final byte[] malformed = MimeEntitySupport.wrapSigned(MESSAGE, new byte[]{'A'}, "bad-encoding", "pgp-sha256");
        final String content = new String(malformed, StandardCharsets.US_ASCII)
                .replace("Content-Description: OpenPGP digital signature\r\n",
                        "Content-Description: OpenPGP digital signature\r\nContent-Transfer-Encoding: base64\r\n");
        final ProtectionLayerResult opened = new OpenPgpSupport().openIncomingLayer(SESSION,
                message(content.getBytes(StandardCharsets.US_ASCII)), verification());

        assertThat(opened.isOpened()).isTrue();
        assertThat(opened.isInspectionComplete()).isFalse();
        assertThat(opened.getOpenedEntity().getContent()).isEqualTo("Partner document\r\n");
        assertThat(opened.getFailureReason()).isNotBlank();
    }

    @Test
    void declarationCancellationAndUnrelatedProviderDefectsAreNotRecoveredAsMailContent() {
        final MimeMessage interrupted = new MimeMessage(SESSION) {
            @Override public String getContentType() throws jakarta.mail.MessagingException {
                throw new jakarta.mail.MessagingException("interrupted synthetic source", new java.io.InterruptedIOException());
            }
        };
        assertThatThrownBy(() -> new OpenPgpSupport().openIncomingLayer(SESSION, interrupted, null))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        final IllegalStateException defect = new IllegalStateException("synthetic provider defect");
        final MimeMessage broken = new MimeMessage(SESSION) {
            @Override public String getContentType() { throw defect; }
        };
        assertThatThrownBy(() -> new OpenPgpSupport().openIncomingLayer(SESSION, broken, null)).isSameAs(defect);
    }

    @ParameterizedTest
    @ValueSource(strings = {"control-type", "version", "ciphertext-type"})
    void malformedEncryptedPartDeclarationsAreNotOpenedAsSupportedRfc3156(final String defect) throws Exception {
        final byte[] encrypted = new PgpainlessOpenPgpAdapter().encrypt(MimeEntitySupport.contentEntity(MESSAGE),
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(certificate).build());
        String encoded = new String(MimeEntitySupport.wrapEncrypted(MESSAGE, encrypted, "declarations"), StandardCharsets.US_ASCII);
        if ("control-type".equals(defect)) {
            encoded = encoded.replace("Content-Type: application/pgp-encrypted\r\n\r\nVersion: 1",
                    "Content-Type: text/plain\r\n\r\nVersion: 1");
        } else if ("version".equals(defect)) {
            encoded = encoded.replace("Version: 1", "Version: 2");
        } else {
            encoded = encoded.replace("Content-Type: application/octet-stream; name=\"encrypted.asc\"",
                    "Content-Type: application/pgp-encrypted; name=\"encrypted.asc\"");
        }
        final byte[] input = encoded.getBytes(StandardCharsets.US_ASCII);
        final MimeMessage source = message(input);
        final ProtectionLayerResult result = new OpenPgpSupport().openIncomingLayer(SESSION, source, receiving(PASSPHRASE));

        assertThat(result.isOpened()).isFalse();
        assertThat(result.getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertThat(((FinalizedMimeMessage) source).getSerializedBytes()).containsExactly(input);
    }

    @Test
    void wrongSignaturePartDeclarationPreservesReadableContentWithoutAFalseVerifiedClaim() throws Exception {
        final String encoded = new String(MimeEntitySupport.serialize(signed()), StandardCharsets.US_ASCII)
                .replace("Content-Type: application/pgp-signature; name=\"signature.asc\"",
                        "Content-Type: application/octet-stream; name=\"signature.asc\"");
        final ProtectionLayerResult result = new OpenPgpSupport().openIncomingLayer(SESSION,
                message(encoded.getBytes(StandardCharsets.US_ASCII)), verification());

        assertThat(result.isOpened()).isTrue();
        assertThat(result.isInspectionComplete()).isFalse();
        assertThat(result.getSignatures()).isEmpty();
        assertThat(result.getOpenedEntity().getContent()).isEqualTo("Partner document\r\n");
    }

    private static OpenPgpReceiveConfig verification() {
        return OpenPgpReceiveConfig.builder().addVerificationKeyRing(certificate).build();
    }

    private static OpenPgpReceiveConfig receiving(final char[] passphrase) {
        return OpenPgpReceiveConfig.builder().addDecryptionKeyRing(secret, passphrase).addVerificationKeyRing(certificate).build();
    }

    private static MimeMessage signed() throws Exception {
        final byte[] signature = new PgpainlessOpenPgpAdapter().createDetachedSignature(MimeEntitySupport.contentEntity(MESSAGE),
                OpenPgpSigningConfig.builder().secretKeyRing(secret).passphrase(PASSPHRASE).signingKeyId(signingSubkey).build());
        return message(MimeEntitySupport.wrapSigned(MESSAGE, signature, "synthetic-signed", "pgp-sha256"));
    }

    private static MimeMessage message(final byte[] bytes) throws Exception {
        return FinalizedMimeMessage.fromMessageBytes(SESSION, bytes, FinalizedMimeMessage.ProtectionState.NONE);
    }
}
