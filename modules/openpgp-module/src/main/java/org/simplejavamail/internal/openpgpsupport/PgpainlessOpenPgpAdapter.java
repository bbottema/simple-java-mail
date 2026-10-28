package org.simplejavamail.internal.openpgpsupport;

import org.bouncycastle.bcpg.KeyIdentifier;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.api.MessageEncryptionMechanism;
import org.bouncycastle.openpgp.api.OpenPGPKey;
import org.bouncycastle.openpgp.api.OpenPGPCertificate;
import org.bouncycastle.openpgp.api.exception.MalformedOpenPGPSignatureException;
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
import org.pgpainless.exception.ModificationDetectionException;
import org.pgpainless.key.parsing.KeyRingReader;
import org.pgpainless.key.protection.SecretKeyRingProtector;
import org.pgpainless.key.protection.passphrase_provider.SecretKeyPassphraseProvider;
import org.pgpainless.util.Passphrase;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;
import org.simplejavamail.internal.email.OpenPgpSignatureResultImpl;
import org.simplejavamail.api.email.OpenPgpSignatureResult;
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
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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

    OpenPgpDetailsImpl verifyDetachedSignatures(final byte[] content, final byte[] encodedSignature,
            @Nullable final OpenPgpReceiveConfig receiveConfig, final byte[] originalProtectedMessage)
            throws IOException, PGPException {
        final SignaturePacketReader.Packets packets = SignaturePacketReader.read(encodedSignature);
        final VerificationKeys keys = verificationKeys(receiveConfig);
        final List<OpenPgpSignatureResult> results = new ArrayList<>();
        for (PGPSignature signature : packets.signatures) results.add(verifyDetached(content, signature, keys));
        return signatureDetails(results, packets.complete, packets.failureReason, keys)
                .openPgpMode(OpenPgpMode.SIGNED).decryptionStatus(DecryptionStatus.NOT_ENCRYPTED)
                .originalProtectedMessage(originalProtectedMessage).build();
    }

    private OpenPgpSignatureResult verifyDetached(final byte[] content, final PGPSignature signature, final VerificationKeys keys) {
        try {
            final ConsumerOptions options = ConsumerOptions.get(pgpainless).addVerificationOfDetachedSignature(signature).forceNonOpenPgpData();
            keys.apply(options);
            final DecryptionStream stream = pgpainless.processMessage().onInputStream(new ByteArrayInputStream(content)).withOptions(options);
            try (DecryptionStream closing = stream) { consumeForVerification(closing); }
            final MessageMetadata metadata = stream.getMetadata();
            if (!metadata.getVerifiedDetachedSignatures().isEmpty()) return valid(metadata.getVerifiedDetachedSignatures().get(0));
            if (!metadata.getRejectedDetachedSignatures().isEmpty()) return rejected(metadata.getRejectedDetachedSignatures().get(0), keys.failed);
        } catch (Exception failure) {
            return result(signature, SignatureStatus.ERROR, null, "Unable to process the OpenPGP signature");
        }
        return result(signature, SignatureStatus.ERROR, null, "No result was available for the OpenPGP signature");
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
        final VerificationKeys verificationKeys = verificationKeys(receiveConfig);
        verificationKeys.apply(decryptionOptions);
        final List<Passphrase> passphrasesToClear = new ArrayList<>();
        try {
            for (OpenPgpReceiveConfig.SecretKeyRing configuredRing : receiveConfig.getDecryptionKeyRings()) {
                final OpenPGPKey keyRing = pgpainless.readKey().parseKey(configuredRing.getKeyRing());
                decryptionOptions.addDecryptionKey(keyRing,
                        decryptionKeyProtector(receiveConfig, configuredRing, passphrasesToClear));
            }
            return readEncryptedMessage(encryptedContent, decryptionOptions, verificationKeys, recipientKeyIds);
        } catch (MissingDecryptionMethodException e) {
            return DecryptionResult.keyMissing(recipientKeyIds);
        } finally {
            clearPassphrases(passphrasesToClear);
        }
    }

    private DecryptionResult readEncryptedMessage(final byte[] encryptedContent, final ConsumerOptions decryptionOptions,
            final VerificationKeys verificationKeys, final List<String> recipientKeyIds) throws IOException, PGPException {
        try {
            final DecryptionStream decryptionStream = pgpainless.processMessage()
                    .onInputStream(new ByteArrayInputStream(encryptedContent))
                    .withOptions(decryptionOptions);
            final byte[] clearContent;
            try (DecryptionStream closingDecryptionStream = decryptionStream) {
                clearContent = closingDecryptionStream.readAllBytes();
            }
            // Only successful close establishes decrypted content and integrity; rejected signatures remain independent facts.
            final MessageMetadata metadata = decryptionStream.getMetadata();
            final List<OpenPgpSignatureResult> signatures = new ArrayList<>();
            for (SignatureVerification signature : metadata.getVerifiedInlineSignatures()) signatures.add(valid(signature));
            for (SignatureVerification.Failure signature : metadata.getRejectedInlineSignatures()) signatures.add(rejected(signature, verificationKeys.failed));
            final OpenPgpDetailsImpl details = signatureDetails(signatures, true, null, verificationKeys)
                    .openPgpMode(signatures.isEmpty() ? OpenPgpMode.ENCRYPTED : OpenPgpMode.SIGNED_ENCRYPTED)
                    .decryptionStatus(DecryptionStatus.DECRYPTED)
                    .encryptionAlgorithm(symmetricAlgorithmName(metadata.getEncryptionMechanism())).recipientKeyIds(recipientKeyIds).build();
            return DecryptionResult.decrypted(clearContent, details);
        } catch (IOException | PGPException | RuntimeException failure) {
            final OpenPgpDetailsImpl interrupted = signatureProcessingFailure(failure, recipientKeyIds);
            if (interrupted != null) return new DecryptionResult(null, interrupted);
            throw failure;
        }
    }

    /** Translates only established native failure types; incomplete close never authorizes emitting decrypted data. */
    @Nullable
    private static OpenPgpDetailsImpl signatureProcessingFailure(final Throwable failure, final List<String> recipientKeyIds) {
        if (findFailure(failure, ModificationDetectionException.class) != null) return null;
        final MalformedOpenPGPSignatureException malformed = findFailure(failure, MalformedOpenPGPSignatureException.class);
        if (malformed == null || !(malformed.getSignature() instanceof OpenPGPDocumentSignature)) return null;
        final String reason = "Unable to process signatures before OpenPGP decryption completed";
        final List<OpenPgpSignatureResult> known = new ArrayList<>();
        {
            final var represented = malformed.getSignature();
            final PGPSignature packet = represented.getSignature();
            final var issuer = represented.getIssuer();
            final byte[] fingerprint = issuer == null ? null : issuer.getKeyIdentifier().getFingerprint();
            final long id = packet.getKeyID() != 0 ? packet.getKeyID() : issuer == null ? 0 : issuer.getKeyIdentifier().getKeyId();
            known.add(new OpenPgpSignatureResultImpl(SignatureStatus.ERROR, id == 0 ? null : keyId(id),
                    fingerprint == null ? null : hexadecimal(fingerprint), publicKeyAlgorithmName(packet.getKeyAlgorithm()),
                    hashAlgorithmName(packet.getHashAlgorithm()), reason));
        }
        return OpenPgpDetailsImpl.builder().openPgpMode(OpenPgpMode.SIGNED_ENCRYPTED).signatureStatus(SignatureStatus.ERROR)
                .signatureInspectionComplete(false).signatures(known).decryptionStatus(DecryptionStatus.UNKNOWN)
                .recipientKeyIds(recipientKeyIds).failureReason(reason).build();
    }

    private static <T extends Throwable> T findFailure(final Throwable failure, final Class<T> type) {
        final ArrayDeque<Throwable> pending = new ArrayDeque<>();
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            final Throwable next = pending.removeFirst();
            if (!visited.add(next)) continue;
            if (type.isInstance(next)) return type.cast(next);
            if (next.getCause() != null) pending.addLast(next.getCause());
            for (Throwable suppressed : next.getSuppressed()) pending.addLast(suppressed);
        }
        return null;
    }

    private static String hexadecimal(final byte[] bytes) {
        final StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        return text.toString();
    }

    private VerificationKeys verificationKeys(@Nullable final OpenPgpReceiveConfig config) {
        final VerificationKeys result = new VerificationKeys();
        if (config != null) {
            for (byte[] encoded : config.getVerificationKeyRings()) {
                try { result.certificates.add(pgpainless.readKey().parseCertificate(encoded)); }
                catch (Exception failure) { result.failed = true; }
            }
        }
        return result;
    }

    private static OpenPgpDetailsImpl.OpenPgpDetailsImplBuilder signatureDetails(final List<OpenPgpSignatureResult> results,
            final boolean complete, @Nullable final String failure, final VerificationKeys keys) {
        final boolean keyFailure = keys.failed && !results.isEmpty();
        return OpenPgpDetailsImpl.builder().signatures(results).signatureInspectionComplete(complete && !keyFailure)
                .signatureStatus(failure != null || keyFailure ? SignatureStatus.ERROR : SignatureStatus.NOT_PRESENT)
                .failureReason(failure != null ? failure : keyFailure ? "Unable to read configured OpenPGP verification keys" : null);
    }

    private static OpenPgpSignatureResult valid(final SignatureVerification verification) {
        return result(verification.getSignature(), SignatureStatus.VALID, verification.getSigningKey(), null);
    }

    private static OpenPgpSignatureResult rejected(final SignatureVerification.Failure failure, final boolean keyFailure) {
        final boolean missing = failure.getSigningKey() == null;
        final SignatureStatus status = missing ? keyFailure ? SignatureStatus.ERROR : SignatureStatus.KEY_MISSING : SignatureStatus.INVALID;
        final String reason = missing ? keyFailure ? "Unable to read configured OpenPGP verification keys"
                : "No verification key matched OpenPGP signer " + keyId(failure.getSignature().getKeyID())
                : "OpenPGP signature or signing key did not satisfy verification policy";
        return result(failure.getSignature(), status, failure.getSigningKey(), reason);
    }

    private static OpenPgpSignatureResult result(final PGPSignature signature, final SignatureStatus status,
            @Nullable final org.pgpainless.key.SubkeyIdentifier signingKey, @Nullable final String reason) {
        final long id = signature.getKeyID() != 0 ? signature.getKeyID()
                : signingKey == null ? 0 : signingKey.getComponentKeyIdentifier().getKeyId();
        return new OpenPgpSignatureResultImpl(status, id == 0 ? null : keyId(id),
                signingKey == null ? null : signingKey.getComponentKeyFingerprint().toString(),
                publicKeyAlgorithmName(signature.getKeyAlgorithm()), hashAlgorithmName(signature.getHashAlgorithm()), reason);
    }

    private static final class VerificationKeys {
        private final List<OpenPGPCertificate> certificates = new ArrayList<>();
        private boolean failed;
        private void apply(final ConsumerOptions options) { for (OpenPGPCertificate certificate : certificates) options.addVerificationCert(certificate); }
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
        final OpenPgpDetailsImpl details;

        private DecryptionResult(@Nullable final byte[] clearContent, final OpenPgpDetailsImpl details) {
            this.clearContent = clearContent;
            this.details = details;
        }

        private static DecryptionResult keyMissing(final List<String> recipientKeyIds) {
            return new DecryptionResult(null, OpenPgpDetailsImpl.builder().openPgpMode(OpenPgpMode.ENCRYPTED)
                    .signatureStatus(SignatureStatus.UNKNOWN).decryptionStatus(DecryptionStatus.KEY_MISSING)
                    .recipientKeyIds(recipientKeyIds).failureReason("No decryption key matched an OpenPGP recipient").build());
        }

        private static DecryptionResult decrypted(final byte[] clearContent, final OpenPgpDetailsImpl details) {
            return new DecryptionResult(clearContent, details);
        }

        boolean isDecrypted() {
            return clearContent != null;
        }
    }
}
