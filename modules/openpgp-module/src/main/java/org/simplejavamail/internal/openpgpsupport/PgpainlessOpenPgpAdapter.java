package org.simplejavamail.internal.openpgpsupport;

import org.bouncycastle.bcpg.KeyIdentifier;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.api.MessageEncryptionMechanism;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.openpgp.api.OpenPGPSignature.OpenPGPDocumentSignature;
import org.jetbrains.annotations.Nullable;
import org.pgpainless.PGPainless;
import org.pgpainless.algorithm.HashAlgorithm;
import org.pgpainless.algorithm.PublicKeyAlgorithm;
import org.pgpainless.algorithm.SymmetricKeyAlgorithm;
import org.pgpainless.decryption_verification.ConsumerOptions;
import org.pgpainless.decryption_verification.DecryptionStream;
import org.pgpainless.decryption_verification.MessageInspector;
import org.pgpainless.decryption_verification.MessageMetadata;
import org.pgpainless.decryption_verification.SignatureVerification;
import org.pgpainless.encryption_signing.EncryptionOptions;
import org.pgpainless.encryption_signing.EncryptionStream;
import org.pgpainless.encryption_signing.ProducerOptions;
import org.pgpainless.encryption_signing.SigningOptions;
import org.pgpainless.exception.MissingDecryptionMethodException;
import org.pgpainless.key.parsing.KeyRingReader;
import org.pgpainless.key.protection.SecretKeyRingProtector;
import org.pgpainless.key.protection.passphrase_provider.SecretKeyPassphraseProvider;
import org.pgpainless.signature.SignatureUtils;
import org.pgpainless.util.Passphrase;
import org.simplejavamail.api.email.OpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.OpenPgpMode;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Translates Simple Java Mail's OpenPGP configuration and results to and from PGPainless. */
final class PgpainlessOpenPgpAdapter {

    private final PGPainless pgpainless = new PGPainless();

    byte[] createDetachedSignature(final byte[] content, final OpenPgpSigningConfig signingConfig)
            throws IOException, PGPException {
        final PGPSecretKeyRing keyRing = new KeyRingReader().secretKeyRing(signingConfig.getSecretKeyRing());
        final Long signingKeyId = signingConfig.getSigningKeyId();
        final Passphrase passphrase = toPgpainlessPassphrase(signingConfig.getPassphrase());
        try {
            final SecretKeyRingProtector protector = signingKeyId == null
                    ? SecretKeyRingProtector.unlockEachKeyWith(passphrase, keyRing)
                    : SecretKeyRingProtector.unlockSingleKeyWith(passphrase, new KeyIdentifier(signingKeyId));
            final SigningOptions signingOptions = SigningOptions.get(pgpainless)
                    .overrideHashAlgorithm(hashAlgorithm(signingConfig.getHashAlgorithm()));
            if (signingKeyId == null) {
                signingOptions.addDetachedSignature(protector, keyRing);
            } else {
                signingOptions.addDetachedSignature(protector, keyRing, signingKeyId);
            }

            final EncryptionStream signingStream = pgpainless.generateMessage().discardOutput()
                    .withOptions(ProducerOptions.sign(signingOptions));
            try (EncryptionStream closingSigningStream = signingStream) {
                closingSigningStream.write(content);
            }
            final OpenPGPDocumentSignature signature = signingStream.getResult()
                    .getDetachedDocumentSignatures().getSignatures().get(0);
            return pgpainless.toAsciiArmor(signature).getBytes(StandardCharsets.US_ASCII);
        } finally {
            passphrase.clear();
        }
    }

    byte[] encrypt(final byte[] content, final OpenPgpEncryptionConfig encryptionConfig)
            throws IOException, PGPException {
        final SymmetricKeyAlgorithm symmetricAlgorithm = symmetricAlgorithm(encryptionConfig.getSymmetricAlgorithm());
        final EncryptionOptions encryptionOptions = EncryptionOptions.encryptCommunications(pgpainless)
                .overrideEncryptionMechanism(
                        MessageEncryptionMechanism.integrityProtected(symmetricAlgorithm.getAlgorithmId()));
        for (byte[] encodedCertificate : encryptionConfig.getRecipientPublicKeyRings()) {
            encryptionOptions.addRecipient(pgpainless.readKey().parseCertificate(encodedCertificate));
        }

        final ByteArrayOutputStream encryptedContent = new ByteArrayOutputStream();
        try (EncryptionStream encryptionStream = pgpainless.generateMessage().onOutputStream(encryptedContent)
                .withOptions(ProducerOptions.encrypt(encryptionOptions).setAsciiArmor(true))) {
            encryptionStream.write(content);
        }
        return encryptedContent.toByteArray();
    }

    OpenPgpDetails verifyDetachedSignature(final byte[] content, final byte[] encodedSignature,
            @Nullable final OpenPgpReceiveConfig receiveConfig, final byte[] originalProtectedMessage)
            throws IOException, PGPException {
        final PGPSignature signature = SignatureUtils.readSignatures(encodedSignature).get(0);
        final String signerKeyId = keyId(signature.getKeyID());
        final OpenPgpDetails.Builder detailBuilder = OpenPgpDetails.builder()
                .openPgpMode(OpenPgpMode.SIGNED)
                .decryptionStatus(DecryptionStatus.NOT_ENCRYPTED)
                .signerKeyId(signerKeyId)
                .signatureAlgorithm(publicKeyAlgorithmName(signature.getKeyAlgorithm()))
                .hashAlgorithm(hashAlgorithmName(signature.getHashAlgorithm()))
                .originalProtectedMessage(originalProtectedMessage);
        final ConsumerOptions verificationOptions = ConsumerOptions.get(pgpainless)
                .addVerificationOfDetachedSignature(signature)
                .forceNonOpenPgpData();
        if (receiveConfig != null) {
            for (byte[] encodedCertificate : receiveConfig.getVerificationKeyRings()) {
                verificationOptions.addVerificationCert(pgpainless.readKey().parseCertificate(encodedCertificate));
            }
        }
        final DecryptionStream verificationStream = pgpainless.processMessage()
                .onInputStream(new ByteArrayInputStream(content))
                .withOptions(verificationOptions);
        try (DecryptionStream closingVerificationStream = verificationStream) {
            consumeForVerification(closingVerificationStream);
        }

        final MessageMetadata metadata = verificationStream.getMetadata();
        if (!metadata.getVerifiedDetachedSignatures().isEmpty()) {
            final SignatureVerification verification = metadata.getVerifiedDetachedSignatures().get(0);
            return detailBuilder
                    .signatureStatus(SignatureStatus.VALID)
                    .signerFingerprint(verification.getSigningKey().getComponentKeyFingerprint().toString())
                    .build();
        }
        if (metadata.getRejectedDetachedSignatures().isEmpty()) {
            throw new PGPException("PGPainless did not report a result for the detached signature");
        }
        final SignatureVerification.Failure rejection = metadata.getRejectedDetachedSignatures().get(0);
        final boolean verificationKeyMissing = rejection.getSigningKey() == null;
        return detailBuilder
                .signatureStatus(verificationKeyMissing ? SignatureStatus.KEY_MISSING : SignatureStatus.INVALID)
                .signerFingerprint(verificationKeyMissing
                        ? null : rejection.getSigningKey().getComponentKeyFingerprint().toString())
                .failureReason(verificationKeyMissing
                        ? "No verification key matched OpenPGP signer " + signerKeyId
                        : "OpenPGP signature or signing key did not satisfy verification policy")
                .build();
    }

    DecryptionResult decrypt(final byte[] encryptedContent, @Nullable final OpenPgpReceiveConfig receiveConfig)
            throws IOException, PGPException {
        final MessageInspector.EncryptionInfo encryptionInfo = new MessageInspector(pgpainless)
                .determineEncryptionInfoForMessage(new ByteArrayInputStream(encryptedContent));
        final List<String> recipientKeyIds = formatKeyIds(encryptionInfo.getKeyIds());
        if (receiveConfig == null || receiveConfig.getDecryptionKeyRings().isEmpty()) {
            return DecryptionResult.keyMissing(recipientKeyIds);
        }

        final ConsumerOptions decryptionOptions = ConsumerOptions.get(pgpainless);
        final List<Passphrase> passphrasesToClear = new ArrayList<>();
        try {
            for (OpenPgpReceiveConfig.SecretKeyRing configuredRing : receiveConfig.getDecryptionKeyRings()) {
                final OpenPGPKey keyRing = pgpainless.readKey().parseKey(configuredRing.getKeyRing());
                decryptionOptions.addDecryptionKey(keyRing,
                        decryptionKeyProtector(receiveConfig, configuredRing, passphrasesToClear));
            }

            final DecryptionStream decryptionStream = pgpainless.processMessage()
                    .onInputStream(new ByteArrayInputStream(encryptedContent))
                    .withOptions(decryptionOptions);
            final byte[] clearContent;
            try (DecryptionStream closingDecryptionStream = decryptionStream) {
                clearContent = closingDecryptionStream.readAllBytes();
            }
            return DecryptionResult.decrypted(clearContent,
                    symmetricAlgorithmName(decryptionStream.getMetadata().getEncryptionMechanism()), recipientKeyIds);
        } catch (MissingDecryptionMethodException e) {
            return DecryptionResult.keyMissing(recipientKeyIds);
        } finally {
            clearPassphrases(passphrasesToClear);
        }
    }

    private static SecretKeyRingProtector decryptionKeyProtector(final OpenPgpReceiveConfig receiveConfig,
            final OpenPgpReceiveConfig.SecretKeyRing configuredRing,
            final List<Passphrase> passphrasesToClear) {
        return SecretKeyRingProtector.defaultSecretKeyRingProtector(new SecretKeyPassphraseProvider() {
            @Override
            public Passphrase getPassphraseFor(final KeyIdentifier keyIdentifier) {
                final OpenPgpReceiveConfig.PassphraseProvider passphraseProvider = receiveConfig.getPassphraseProvider();
                final char[] providedPassphrase = passphraseProvider == null ? null
                        : passphraseProvider.passphraseFor(keyIdentifier.getKeyId());
                final Passphrase passphrase = toPgpainlessPassphrase(
                        providedPassphrase == null ? configuredRing.getPassphrase() : providedPassphrase.clone());
                passphrasesToClear.add(passphrase);
                return passphrase;
            }

            @Override
            public boolean hasPassphrase(final KeyIdentifier keyIdentifier) {
                return true;
            }
        });
    }

    private static void consumeForVerification(final InputStream input) throws IOException {
        // PGPainless finalizes verification only after the complete signed entity is consumed.
        input.transferTo(OutputStream.nullOutputStream());
    }

    private static Passphrase toPgpainlessPassphrase(final char[] passphraseChars) {
        return passphraseChars.length == 0 ? Passphrase.emptyPassphrase() : new Passphrase(passphraseChars);
    }

    private static void clearPassphrases(final List<Passphrase> passphrases) {
        for (Passphrase passphrase : passphrases) {
            passphrase.clear();
        }
    }

    private static HashAlgorithm hashAlgorithm(final OpenPgpSigningConfig.HashAlgorithm algorithm) {
        return HashAlgorithm.valueOf(algorithm.name());
    }

    private static SymmetricKeyAlgorithm symmetricAlgorithm(
            final OpenPgpEncryptionConfig.SymmetricAlgorithm algorithm) {
        return SymmetricKeyAlgorithm.valueOf(algorithm.name());
    }

    private static String hashAlgorithmName(final int algorithmId) {
        final HashAlgorithm algorithm = HashAlgorithm.fromId(algorithmId);
        return algorithm == null ? "OPENPGP-" + algorithmId : algorithm.getAlgorithmName();
    }

    private static String symmetricAlgorithmName(@Nullable final MessageEncryptionMechanism mechanism) {
        if (mechanism == null) {
            return "OPENPGP-UNKNOWN";
        }
        final SymmetricKeyAlgorithm algorithm = SymmetricKeyAlgorithm.fromId(mechanism.getSymmetricKeyAlgorithm());
        if (algorithm == null) {
            return "OPENPGP-" + mechanism.getSymmetricKeyAlgorithm();
        }
        return algorithm.name().replace("_", "");
    }

    private static String publicKeyAlgorithmName(final int algorithmId) {
        final PublicKeyAlgorithm algorithm = PublicKeyAlgorithm.fromId(algorithmId);
        if (algorithm == null) {
            return "OPENPGP-" + algorithmId;
        }
        return algorithm == PublicKeyAlgorithm.RSA_GENERAL ? "RSA" : algorithm.name();
    }

    private static List<String> formatKeyIds(final List<Long> recipientKeyIds) {
        final List<String> formattedKeyIds = new ArrayList<>();
        for (Long recipientKeyId : recipientKeyIds) {
            formattedKeyIds.add(keyId(recipientKeyId));
        }
        return formattedKeyIds;
    }

    private static String keyId(final long keyId) {
        return String.format(Locale.ROOT, "%016X", keyId);
    }

    static final class DecryptionResult {
        @Nullable final byte[] clearContent;
        @Nullable final String encryptionAlgorithm;
        final List<String> recipientKeyIds;

        private DecryptionResult(@Nullable final byte[] clearContent, @Nullable final String encryptionAlgorithm,
                final List<String> recipientKeyIds) {
            this.clearContent = clearContent;
            this.encryptionAlgorithm = encryptionAlgorithm;
            this.recipientKeyIds = recipientKeyIds;
        }

        private static DecryptionResult keyMissing(final List<String> recipientKeyIds) {
            return new DecryptionResult(null, null, recipientKeyIds);
        }

        private static DecryptionResult decrypted(final byte[] clearContent, final String encryptionAlgorithm,
                final List<String> recipientKeyIds) {
            return new DecryptionResult(clearContent, encryptionAlgorithm, recipientKeyIds);
        }

        boolean isDecrypted() {
            return clearContent != null;
        }
    }
}
