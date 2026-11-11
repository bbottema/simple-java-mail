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
import org.pgpainless.decryption_verification.MessageMetadata;
import org.pgpainless.decryption_verification.MessageInspector;
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
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.internal.email.SignatureObservationImpl;
import org.simplejavamail.internal.email.SigningKeyFingerprint;
import org.simplejavamail.internal.util.InterruptedIoGuard;

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

    /** Captures one detached packet set without retaining a provider object or a flattened message verdict. */
    LayerSignatures verifyLayerSignatures(final byte[] content, final byte[] signatureBytes,
            @Nullable final OpenPgpReceiveConfig receiveConfig) throws IOException, PGPException {
        final VerificationKeys keys = configuredVerificationKeys(receiveConfig);
        final SignaturePacketReader.Packets packets = SignaturePacketReader.read(signatureBytes);
        final List<SignatureObservation> signatures = new ArrayList<>();
        for (PGPSignature signature : packets.signatures) signatures.add(verifyLayerSignature(content, signature, keys));
        return new LayerSignatures(signatures, packets.complete, packets.failureReason);
    }

    private SignatureObservation verifyLayerSignature(final byte[] content, final PGPSignature signature,
            final VerificationKeys keys) throws IOException, PGPException {
        try {
            final ConsumerOptions options = ConsumerOptions.get(pgpainless)
                    .addVerificationOfDetachedSignature(signature).forceNonOpenPgpData();
            keys.apply(options);
            final DecryptionStream stream = pgpainless.processMessage().onInputStream(new ByteArrayInputStream(content)).withOptions(options);
            try (DecryptionStream closing = stream) { consumeForVerification(closing); }
            final MessageMetadata metadata = stream.getMetadata();
            if (!metadata.getVerifiedDetachedSignatures().isEmpty()) {
                final SignatureVerification valid = metadata.getVerifiedDetachedSignatures().get(0);
                return observation(valid.getSignature(), org.simplejavamail.api.email.SignatureVerification.VERIFIED, valid.getSigningKey(), null);
            }
            if (!metadata.getRejectedDetachedSignatures().isEmpty()) return rejectedObservation(metadata.getRejectedDetachedSignatures().get(0));
            return observation(signature, org.simplejavamail.api.email.SignatureVerification.UNVERIFIED, null, "No result was available for the OpenPGP signature");
        } catch (IOException | PGPException unavailableVerification) {
            InterruptedIoGuard.rethrowIfInterrupted(unavailableVerification);
            return observation(signature, org.simplejavamail.api.email.SignatureVerification.UNVERIFIED, null,
                    "Unable to verify OpenPGP signature: " + unavailableVerification.getClass().getSimpleName());
        }
    }

    LayerDecryption decryptLayer(final byte[] encryptedBytes, @Nullable final OpenPgpReceiveConfig receiveConfig)
            throws IOException, PGPException {
        final List<String> recipientKeyIds = new ArrayList<>();
        for (Long recipient : new MessageInspector(pgpainless)
                .determineEncryptionInfoForMessage(new ByteArrayInputStream(encryptedBytes)).getKeyIds()) {
            recipientKeyIds.add(keyId(recipient));
        }
        final VerificationKeys verificationKeys = configuredVerificationKeys(receiveConfig);
        final ConsumerOptions options = ConsumerOptions.get(pgpainless);
        verificationKeys.apply(options);
        final List<Passphrase> passphrases = new ArrayList<>();
        try {
            if (receiveConfig == null || receiveConfig.getDecryptionKeyRings().isEmpty()) return LayerDecryption.keyMissing(recipientKeyIds);
            for (OpenPgpReceiveConfig.SecretKeyRing configured : receiveConfig.getDecryptionKeyRings()) {
                final OpenPGPKey key;
                try {
                    key = pgpainless.readKey().parseKey(configured.getKeyRing());
                } catch (IOException configurationFailure) {
                    InterruptedIoGuard.rethrowIfInterrupted(configurationFailure);
                    throw new IllegalArgumentException("Unable to load the configured OpenPGP decryption key", configurationFailure);
                }
                options.addDecryptionKey(key, decryptionKeyProtector(receiveConfig, configured, passphrases));
            }
            return readEncryptedLayer(encryptedBytes, options, recipientKeyIds);
        } finally {
            clearPassphrases(passphrases);
        }
    }

    private LayerDecryption readEncryptedLayer(final byte[] encryptedBytes, final ConsumerOptions options, final List<String> recipientKeyIds)
            throws IOException, PGPException {
        try {
            final DecryptionStream stream = pgpainless.processMessage().onInputStream(new ByteArrayInputStream(encryptedBytes)).withOptions(options);
            final byte[] clear = readCompleteEncryptedContent(stream);
            final MessageMetadata metadata = stream.getMetadata();
            if (!metadata.isEncrypted()) throw new PGPException("OpenPGP/MIME payload contains no encrypted data");
            final List<SignatureObservation> signatures = new ArrayList<>();
            for (SignatureVerification signature : metadata.getVerifiedInlineSignatures()) {
                signatures.add(observation(signature.getSignature(), org.simplejavamail.api.email.SignatureVerification.VERIFIED, signature.getSigningKey(), null));
            }
            for (SignatureVerification.Failure signature : metadata.getRejectedInlineSignatures()) signatures.add(rejectedObservation(signature));
            return LayerDecryption.builder().clearContent(clear).signatures(signatures)
                    .algorithm(symmetricAlgorithmName(metadata.getEncryptionMechanism())).recipientKeyIds(recipientKeyIds)
                    .status(org.simplejavamail.api.email.DecryptionStatus.DECRYPTED).inspectionComplete(true).build();
        } catch (IOException | PGPException failure) {
            PassphraseProviderFailure.rethrowIfPresent(failure);
            InterruptedIoGuard.rethrowIfInterrupted(failure);
            if (findFailure(failure, org.pgpainless.exception.WrongPassphraseException.class) != null
                    || findFailure(failure, org.pgpainless.exception.MissingPassphraseException.class) != null) {
                throw new IllegalArgumentException("The configured OpenPGP decryption key could not be unlocked", failure);
            }
            if (findFailure(failure, MissingDecryptionMethodException.class) != null) return LayerDecryption.keyMissing(recipientKeyIds);
            final MalformedOpenPGPSignatureException malformed = findFailure(failure, MalformedOpenPGPSignatureException.class);
            if (malformed != null) {
                if (findFailure(failure, ModificationDetectionException.class) != null) {
                    return LayerDecryption.builder().recipientKeyIds(recipientKeyIds)
                            .status(org.simplejavamail.api.email.DecryptionStatus.FAILED)
                            .failureReason("The OpenPGP ciphertext integrity could not be established").processingFailure(failure).build();
                }
                final List<SignatureObservation> observations = new ArrayList<>();
                if (malformed.getSignature() instanceof OpenPGPDocumentSignature) {
                    final PGPSignature packet = malformed.getSignature().getSignature();
                    observations.add(observation(packet, org.simplejavamail.api.email.SignatureVerification.UNVERIFIED, null,
                            "A recognized OpenPGP signature could not be processed before decryption completed"));
                }
                return LayerDecryption.builder().recipientKeyIds(recipientKeyIds).signatures(observations)
                        .status(org.simplejavamail.api.email.DecryptionStatus.UNAVAILABLE)
                        .failureReason("OpenPGP decryption could not be completed while reading its signatures").processingFailure(failure).build();
            }
            throw failure;
        }
    }

    private static byte[] readCompleteEncryptedContent(final DecryptionStream stream) throws IOException, PGPException {
        try (DecryptionStream closing = stream) {
            return closing.readAllBytes();
        } catch (RuntimeException nativeWrapper) {
            PassphraseProviderFailure.rethrowIfPresent(nativeWrapper);
            InterruptedIoGuard.rethrowIfInterrupted(nativeWrapper);
            // Native InputStream.read wraps these checked packet failures; unrelated runtime defects keep their identity.
            if (findFailure(nativeWrapper, MalformedOpenPGPSignatureException.class) != null
                    || findFailure(nativeWrapper, ModificationDetectionException.class) != null) {
                throw new PGPException("Unable to complete OpenPGP packet processing", nativeWrapper);
            }
            throw nativeWrapper;
        }
    }

    private VerificationKeys configuredVerificationKeys(@Nullable final OpenPgpReceiveConfig config) {
        final VerificationKeys result = new VerificationKeys();
        if (config != null) {
            for (byte[] encoded : config.getVerificationKeyRings()) {
                try {
                    result.certificates.add(pgpainless.readKey().parseCertificate(encoded));
                } catch (IOException configurationFailure) {
                    InterruptedIoGuard.rethrowIfInterrupted(configurationFailure);
                    throw new IllegalArgumentException("Unable to load the configured OpenPGP verification key", configurationFailure);
                }
            }
        }
        return result;
    }

    private static SignatureObservation rejectedObservation(final SignatureVerification.Failure failure) {
        final boolean keyMissing = failure.getSigningKey() == null;
        return observation(failure.getSignature(), keyMissing ? org.simplejavamail.api.email.SignatureVerification.UNVERIFIED : org.simplejavamail.api.email.SignatureVerification.INVALID,
                failure.getSigningKey(), keyMissing ? "No verification key matched the OpenPGP signer"
                        : "The OpenPGP signature did not satisfy verification");
    }


    private static SignatureObservation observation(final PGPSignature signature, final org.simplejavamail.api.email.SignatureVerification status,
            @Nullable final org.pgpainless.key.SubkeyIdentifier signingKey, @Nullable final String reason) {
        final long id = signature.getKeyID() != 0 ? signature.getKeyID()
                : signingKey == null ? 0 : signingKey.getComponentKeyIdentifier().getKeyId();
        return SignatureObservationImpl.builder().family(ProtectionFamily.OPENPGP)
                .status(status).signerKeyId(id == 0 ? null : keyId(id))
                .signingKeyFingerprint(signingKey == null ? null
                        : SigningKeyFingerprint.forOpenPgp(signingKey.getComponentKeyFingerprint().toString()))
                .signatureAlgorithm(publicKeyAlgorithmName(signature.getKeyAlgorithm()))
                .hashAlgorithm(hashAlgorithmName(signature.getHashAlgorithm())).failureReason(reason).build();
    }

    static final class LayerSignatures {
        final List<SignatureObservation> signatures;
        final boolean complete;
        @Nullable final String failureReason;

        private LayerSignatures(final List<SignatureObservation> signatures, final boolean complete, @Nullable final String failureReason) {
            this.signatures = signatures;
            this.complete = complete;
            this.failureReason = failureReason;
        }
    }

    static final class LayerDecryption {
        @Nullable final byte[] clearContent;
        final List<SignatureObservation> signatures;
        @Nullable final String algorithm;
        final List<String> recipientKeyIds;
        final org.simplejavamail.api.email.DecryptionStatus status;
        final boolean inspectionComplete;
        @Nullable final String failureReason;
        @Nullable final Throwable processingFailure;

        @lombok.Builder
        private LayerDecryption(@Nullable final byte[] clearContent, final List<SignatureObservation> signatures,
                @Nullable final String algorithm, final List<String> recipientKeyIds,
                final org.simplejavamail.api.email.DecryptionStatus status, final boolean inspectionComplete,
                @Nullable final String failureReason, @Nullable final Throwable processingFailure) {
            this.clearContent = clearContent;
            this.signatures = signatures == null ? List.of() : List.copyOf(signatures);
            this.algorithm = algorithm;
            this.recipientKeyIds = recipientKeyIds == null ? List.of() : List.copyOf(recipientKeyIds);
            this.status = java.util.Objects.requireNonNull(status, "status");
            this.inspectionComplete = inspectionComplete;
            this.failureReason = failureReason;
            this.processingFailure = processingFailure;
        }

        private static LayerDecryption keyMissing(final List<String> recipientKeyIds) {
            return builder().recipientKeyIds(recipientKeyIds).status(org.simplejavamail.api.email.DecryptionStatus.UNAVAILABLE)
                    .failureReason("No configured key matched an OpenPGP recipient").build();
        }
    }

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

    private static final class VerificationKeys {
        private final List<OpenPGPCertificate> certificates = new ArrayList<>();
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
                        : passphraseFor(passphraseProvider, keyIdentifier.getKeyId());
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

    private static char[] passphraseFor(final OpenPgpReceiveConfig.PassphraseProvider provider, final long keyId) {
        try {
            return provider.passphraseFor(keyId);
        } catch (RuntimeException failure) {
            throw new PassphraseProviderFailure(failure);
        }
    }

    /** Carries an application failure through native and nested-message recovery without classifying it as bad mail. */
    static final class PassphraseProviderFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PassphraseProviderFailure(final RuntimeException failure) {
            super("Application OpenPGP passphrase provider failed", failure);
        }

        static void rethrowIfPresent(final Throwable failure) {
            final PassphraseProviderFailure providerFailure = findFailure(failure, PassphraseProviderFailure.class);
            if (providerFailure != null) {
                providerFailure.retainSuppressedFailures(failure);
                throw providerFailure;
            }
        }

        RuntimeException originalFailure() {
            retainSuppressedFailures(this);
            return (RuntimeException) getCause();
        }

        private void retainSuppressedFailures(final Throwable failure) {
            final Throwable original = getCause();
            final Set<Throwable> retained = Collections.newSetFromMap(new IdentityHashMap<>());
            Collections.addAll(retained, original.getSuppressed());
            final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            visited.add(original);
            final ArrayDeque<Throwable> pending = new ArrayDeque<>();
            pending.add(failure);
            while (!pending.isEmpty()) {
                final Throwable current = pending.removeFirst();
                if (!visited.add(current)) continue;
                if (current.getCause() != null) pending.addLast(current.getCause());
                for (Throwable suppressed : current.getSuppressed()) {
                    // Native wrappers carrying this marker must not become another public route to the internal marker.
                    if (findFailure(suppressed, PassphraseProviderFailure.class) != null) {
                        pending.addLast(suppressed);
                    } else if (suppressed != original && retained.add(suppressed)) {
                        original.addSuppressed(suppressed);
                    }
                }
            }
        }
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

    private static String keyId(final long keyId) {
        return String.format(Locale.ROOT, "%016X", keyId);
    }

}
