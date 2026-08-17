package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.OpenPgpMode;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.internal.openpgpsupport.model.OpenPgpParseResult;
import org.simplejavamail.internal.modules.OpenPgpModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** RFC 3156 OpenPGP/MIME implementation. */
public final class OpenPgpSupport implements OpenPgpModule {

    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();
    private static final int MAX_PROTECTION_DEPTH = 2;
    private static final int BUFFER_SIZE = 64 * 1024;

    @NotNull
    @Override
    public MimeMessage signMessage(@NotNull final Session session, @NotNull final Email email,
                                   @NotNull final MimeMessage message,
                                   @NotNull final OpenPgpSigningConfig signingConfig) {
        try {
            final byte[] source = MimeEntitySupport.serialize(message);
            final byte[] entity = MimeEntitySupport.normalizeCrlf(MimeEntitySupport.contentEntity(source));
            requireSevenBit(entity);
            final SigningMaterial signingMaterial = signingMaterial(signingConfig);
            final int hashAlgorithm = hashAlgorithm(signingConfig.getHashAlgorithm());
            final byte[] signature = detachedSignature(entity, signingMaterial, hashAlgorithm);
            final String boundary = boundary("signed");
            final byte[] result = MimeEntitySupport.wrapSigned(source, signature, boundary,
                    "pgp-" + hashName(hashAlgorithm).toLowerCase(Locale.ROOT));
            return FinalizedMimeMessage.fromMessageBytes(session, result,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException e) {
            throw new IllegalStateException("Unable to sign OpenPGP/MIME message", e);
        }
    }

    @NotNull
    @Override
    public MimeMessage encryptMessage(@NotNull final Session session, @NotNull final Email email,
                                      @NotNull final MimeMessage message,
                                      @NotNull final OpenPgpEncryptionConfig encryptionConfig) {
        try {
            final byte[] source = MimeEntitySupport.serialize(message);
            final byte[] entity = MimeEntitySupport.contentEntity(source);
            final List<PGPPublicKey> recipientKeys = encryptionKeys(encryptionConfig);
            final byte[] encrypted = encrypt(entity, recipientKeys,
                    symmetricAlgorithm(encryptionConfig.getSymmetricAlgorithm()));
            final byte[] result = MimeEntitySupport.wrapEncrypted(source, encrypted, boundary("encrypted"));
            return FinalizedMimeMessage.fromMessageBytes(session, result,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException e) {
            throw new IllegalStateException("Unable to encrypt OpenPGP/MIME message", e);
        }
    }

    @NotNull
    @Override
    public OpenPgpParseResult processIncoming(@NotNull final Session session,
                                              @NotNull final MimeMessage originalMessage,
                                              @Nullable final OpenPgpReceiveConfig receiveConfig) {
        try {
            return process(session, originalMessage, MimeEntitySupport.serialize(originalMessage), receiveConfig, 0);
        } catch (MessagingException e) {
            return failure(originalMessage, OpenPgpMode.PLAIN, SignatureStatus.ERROR,
                    DecryptionStatus.NOT_ENCRYPTED, "Unable to inspect OpenPGP/MIME structure", null);
        }
    }

    private OpenPgpParseResult process(final Session session, final MimeMessage message, final byte[] raw,
                                       @Nullable final OpenPgpReceiveConfig receiveConfig, final int depth)
            throws MessagingException {
        if (isOpenPgpSigned(message)) {
            return processSigned(session, message, raw, receiveConfig);
        }
        if (isOpenPgpEncrypted(message)) {
            if (depth >= MAX_PROTECTION_DEPTH) {
                return failure(message, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                        DecryptionStatus.FAILED, "OpenPGP/MIME nesting limit exceeded", raw);
            }
            return processEncrypted(session, message, raw, receiveConfig, depth);
        }
        return new OpenPgpParseResult(false, message, OpenPgpDetails.plain());
    }

    private OpenPgpParseResult processSigned(final Session session, final MimeMessage original,
                                             final byte[] raw, @Nullable final OpenPgpReceiveConfig config) {
        byte[] effectiveBytes = null;
        try {
            final String boundary = MimeEntitySupport.boundary(original);
            final List<byte[]> parts = MimeEntitySupport.multipartParts(raw, boundary);
            if (parts.size() != 2) {
                return failure(original, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                        DecryptionStatus.NOT_ENCRYPTED, "Malformed OpenPGP multipart/signed message", raw);
            }
            final byte[] signedEntity = parts.get(0);
            effectiveBytes = MimeEntitySupport.restoreMessage(raw, signedEntity);
            final MimeMessage effective = FinalizedMimeMessage.fromMessageBytes(session, effectiveBytes,
                    FinalizedMimeMessage.ProtectionState.NONE);
            final PGPSignature signature = readDetachedSignature(MimeEntitySupport.partBody(parts.get(1)));
            final String keyId = keyId(signature.getKeyID());
            final OpenPgpDetails.Builder details = OpenPgpDetails.builder()
                    .openPgpMode(OpenPgpMode.SIGNED)
                    .decryptionStatus(DecryptionStatus.NOT_ENCRYPTED)
                    .signerKeyId(keyId)
                    .signatureAlgorithm(publicKeyAlgorithmName(signature.getKeyAlgorithm()))
                    .hashAlgorithm(hashName(signature.getHashAlgorithm()))
                    .originalProtectedMessage(raw);

            final PGPPublicKey verificationKey = findVerificationKey(config, signature.getKeyID());
            if (verificationKey == null) {
                return new OpenPgpParseResult(true, effective,
                        details.signatureStatus(SignatureStatus.KEY_MISSING)
                                .failureReason("No verification key matched OpenPGP signer " + keyId)
                                .build());
            }
            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(BOUNCY_CASTLE), verificationKey);
            signature.update(MimeEntitySupport.normalizeCrlf(signedEntity));
            final boolean valid = signature.verify();
            return new OpenPgpParseResult(true, effective,
                    details.signatureStatus(valid ? SignatureStatus.VALID : SignatureStatus.INVALID)
                            .signerFingerprint(hex(verificationKey.getFingerprint()))
                            .failureReason(valid ? null : "OpenPGP signature does not match the signed MIME entity")
                            .build());
        } catch (Exception e) {
            try {
                final MimeMessage effective = effectiveBytes == null ? original
                        : FinalizedMimeMessage.fromMessageBytes(session, effectiveBytes,
                        FinalizedMimeMessage.ProtectionState.NONE);
                return failure(effective, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                        DecryptionStatus.NOT_ENCRYPTED, "Unable to verify OpenPGP signature", raw);
            } catch (MessagingException ignored) {
                return failure(original, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                        DecryptionStatus.NOT_ENCRYPTED, "Unable to verify OpenPGP signature", raw);
            }
        }
    }

    private OpenPgpParseResult processEncrypted(final Session session, final MimeMessage original,
                                                final byte[] raw, @Nullable final OpenPgpReceiveConfig config,
                                                final int depth) {
        try {
            final String boundary = MimeEntitySupport.boundary(original);
            final List<byte[]> parts = MimeEntitySupport.multipartParts(raw, boundary);
            if (parts.size() != 2) {
                return failure(original, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                        DecryptionStatus.FAILED, "Malformed OpenPGP multipart/encrypted message", raw);
            }
            final PGPEncryptedDataList encryptedDataList = readEncryptedDataList(
                    MimeEntitySupport.partBody(parts.get(1)));
            final List<String> recipientIds = recipientIds(encryptedDataList);
            final DecryptionMaterial material = decryptionMaterial(encryptedDataList, config);
            if (material == null) {
                return new OpenPgpParseResult(true, original, OpenPgpDetails.builder()
                        .openPgpMode(OpenPgpMode.ENCRYPTED)
                        .signatureStatus(SignatureStatus.NOT_PRESENT)
                        .decryptionStatus(DecryptionStatus.KEY_MISSING)
                        .recipientKeyIds(recipientIds)
                        .failureReason("No decryption key matched an OpenPGP recipient")
                        .originalProtectedMessage(raw)
                        .build());
            }

            final PublicKeyDataDecryptorFactory decryptorFactory = new JcePublicKeyDataDecryptorFactoryBuilder()
                    .setProvider(BOUNCY_CASTLE).build(material.privateKey);
            final int symmetricAlgorithm = material.encryptedData.getSymmetricAlgorithm(decryptorFactory);
            final InputStream clearStream = material.encryptedData.getDataStream(decryptorFactory);
            final byte[] clearEntity = readLiteralData(clearStream);
            if (material.encryptedData.isIntegrityProtected() && !material.encryptedData.verify()) {
                return failure(original, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                        DecryptionStatus.FAILED, "OpenPGP encrypted data failed its integrity check", raw);
            }

            final byte[] clearMessageBytes = MimeEntitySupport.restoreMessage(raw, clearEntity);
            final MimeMessage clearMessage = FinalizedMimeMessage.fromMessageBytes(session, clearMessageBytes,
                    FinalizedMimeMessage.ProtectionState.NONE);
            if (isOpenPgpSigned(clearMessage)) {
                final OpenPgpParseResult signed = process(session, clearMessage, clearMessageBytes, config, depth + 1);
                final OriginalOpenPgpDetails signedDetails = signed.getDetails();
                final OpenPgpDetails combined = OpenPgpDetails.builder()
                        .openPgpMode(OpenPgpMode.SIGNED_ENCRYPTED)
                        .signatureStatus(signedDetails.getSignatureStatus())
                        .decryptionStatus(DecryptionStatus.DECRYPTED)
                        .signerKeyId(signedDetails.getSignerKeyId())
                        .signerFingerprint(signedDetails.getSignerFingerprint())
                        .signatureAlgorithm(signedDetails.getSignatureAlgorithm())
                        .hashAlgorithm(signedDetails.getHashAlgorithm())
                        .encryptionAlgorithm(symmetricAlgorithmName(symmetricAlgorithm))
                        .recipientKeyIds(recipientIds)
                        .failureReason(signedDetails.getFailureReason())
                        .originalProtectedMessage(raw)
                        .build();
                return new OpenPgpParseResult(true, signed.getEffectiveMimeMessage(), combined);
            }
            return new OpenPgpParseResult(true, clearMessage, OpenPgpDetails.builder()
                    .openPgpMode(OpenPgpMode.ENCRYPTED)
                    .signatureStatus(SignatureStatus.NOT_PRESENT)
                    .decryptionStatus(DecryptionStatus.DECRYPTED)
                    .encryptionAlgorithm(symmetricAlgorithmName(symmetricAlgorithm))
                    .recipientKeyIds(recipientIds)
                    .originalProtectedMessage(raw)
                    .build());
        } catch (Exception e) {
            return failure(original, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                    DecryptionStatus.FAILED, "Unable to decrypt OpenPGP/MIME message", raw);
        }
    }

    private static boolean isOpenPgpSigned(final MimeMessage message) throws MessagingException {
        if (!message.isMimeType("multipart/signed")) return false;
        final String protocol = new ContentType(message.getContentType()).getParameter("protocol");
        return "application/pgp-signature".equalsIgnoreCase(protocol);
    }

    private static boolean isOpenPgpEncrypted(final MimeMessage message) throws MessagingException {
        if (!message.isMimeType("multipart/encrypted")) return false;
        final String protocol = new ContentType(message.getContentType()).getParameter("protocol");
        return "application/pgp-encrypted".equalsIgnoreCase(protocol);
    }

    private static SigningMaterial signingMaterial(final OpenPgpSigningConfig config)
            throws IOException, PGPException {
        final PGPSecretKeyRingCollection keyRings = secretKeyRings(config.getSecretKeyRing());
        PGPSecretKey signingKey = config.getSigningKeyId() == null
                ? firstSigningKey(keyRings) : keyRings.getSecretKey(config.getSigningKeyId());
        if (signingKey == null || !signingKey.isSigningKey()) {
            throw new PGPException("No signing key found in the supplied OpenPGP secret key ring");
        }
        final char[] passphrase = config.getPassphrase();
        try {
            final PGPPrivateKey privateKey = signingKey.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder().setProvider(BOUNCY_CASTLE).build(passphrase));
            if (privateKey == null) {
                throw new PGPException("OpenPGP signing key has no private key material");
            }
            return new SigningMaterial(signingKey.getPublicKey(), privateKey);
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private static PGPSecretKey firstSigningKey(final PGPSecretKeyRingCollection keyRings) {
        final Iterator<PGPSecretKeyRing> rings = keyRings.getKeyRings();
        while (rings.hasNext()) {
            final Iterator<PGPSecretKey> keys = rings.next().getSecretKeys();
            while (keys.hasNext()) {
                final PGPSecretKey key = keys.next();
                if (key.isSigningKey()) return key;
            }
        }
        return null;
    }

    private static byte[] detachedSignature(final byte[] entity, final SigningMaterial material,
                                            final int hashAlgorithm) throws PGPException, IOException {
        final PGPSignatureGenerator generator = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(material.publicKey.getAlgorithm(), hashAlgorithm)
                        .setProvider(BOUNCY_CASTLE));
        generator.init(PGPSignature.BINARY_DOCUMENT, material.privateKey);
        generator.update(entity);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) {
            armor.setHeader("Version", "Simple Java Mail");
            generator.generate().encode(armor);
        }
        return output.toByteArray();
    }

    private static List<PGPPublicKey> encryptionKeys(final OpenPgpEncryptionConfig config)
            throws IOException, PGPException {
        final List<PGPPublicKey> result = new ArrayList<>();
        for (byte[] keyRingData : config.getRecipientPublicKeyRings()) {
            final PGPPublicKeyRingCollection keyRings = publicKeyRings(keyRingData);
            final PGPPublicKey key = firstEncryptionKey(keyRings);
            if (key == null) {
                throw new PGPException("No encryption-capable key found in an OpenPGP public key ring");
            }
            result.add(key);
        }
        return result;
    }

    private static PGPPublicKey firstEncryptionKey(final PGPPublicKeyRingCollection keyRings) {
		PGPPublicKey masterKeyFallback = null;
        final Iterator<PGPPublicKeyRing> rings = keyRings.getKeyRings();
        while (rings.hasNext()) {
            final Iterator<PGPPublicKey> keys = rings.next().getPublicKeys();
            while (keys.hasNext()) {
                final PGPPublicKey key = keys.next();
				if (!isUsableEncryptionKey(key)) continue;
				if (!key.isMasterKey()) return key;
				if (masterKeyFallback == null) masterKeyFallback = key;
            }
        }
		return masterKeyFallback;
    }

	private static boolean isUsableEncryptionKey(final PGPPublicKey key) {
		if (!key.isEncryptionKey() || key.isRevoked()) return false;
		final long validSeconds = key.getValidSeconds();
		if (validSeconds > 0 && System.currentTimeMillis() / 1000L
				>= key.getCreationTime().getTime() / 1000L + validSeconds) return false;

		boolean keyFlagsPresent = false;
		int keyFlags = 0;
		final Iterator<PGPSignature> signatures = key.getSignatures();
		while (signatures.hasNext()) {
			final PGPSignature signature = signatures.next();
			final PGPSignatureSubpacketVector hashed = signature.getHashedSubPackets();
			if (hashed != null && hashed.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) {
				keyFlagsPresent = true;
				keyFlags |= hashed.getKeyFlags();
			}
			final PGPSignatureSubpacketVector unhashed = signature.getUnhashedSubPackets();
			if (unhashed != null && unhashed.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) {
				keyFlagsPresent = true;
				keyFlags |= unhashed.getKeyFlags();
			}
		}
		return !keyFlagsPresent || (keyFlags & (KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE)) != 0;
	}

    private static byte[] encrypt(final byte[] entity, final List<PGPPublicKey> recipientKeys,
                                  final int symmetricAlgorithm) throws IOException, PGPException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(output)) {
            armor.setHeader("Version", "Simple Java Mail");
            final org.bouncycastle.openpgp.PGPEncryptedDataGenerator encryptedGenerator =
                    new org.bouncycastle.openpgp.PGPEncryptedDataGenerator(
                            new JcePGPDataEncryptorBuilder(symmetricAlgorithm)
                                    .setWithIntegrityPacket(true)
                                    .setSecureRandom(new SecureRandom())
                                    .setProvider(BOUNCY_CASTLE));
            for (PGPPublicKey recipientKey : recipientKeys) {
                encryptedGenerator.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(recipientKey)
                        .setProvider(BOUNCY_CASTLE).setSecureRandom(new SecureRandom()));
            }
            final OutputStream encryptedOutput = encryptedGenerator.open(armor, new byte[BUFFER_SIZE]);
            final PGPLiteralDataGenerator literalGenerator = new PGPLiteralDataGenerator();
            final OutputStream literalOutput = literalGenerator.open(encryptedOutput, PGPLiteralData.BINARY,
                    PGPLiteralData.CONSOLE, entity.length, new Date());
            literalOutput.write(entity);
            literalOutput.close();
            literalGenerator.close();
            encryptedOutput.close();
            encryptedGenerator.close();
        }
        return output.toByteArray();
    }

    private static PGPSignature readDetachedSignature(final byte[] encoded) throws IOException, PGPException {
        final InputStream decoder = PGPUtil.getDecoderStream(new ByteArrayInputStream(encoded));
        final PGPObjectFactory factory = new PGPObjectFactory(decoder, new JcaKeyFingerprintCalculator());
        Object object = factory.nextObject();
        if (object instanceof PGPCompressedData) {
            object = new PGPObjectFactory(((PGPCompressedData) object).getDataStream(),
                    new JcaKeyFingerprintCalculator()).nextObject();
        }
        if (!(object instanceof PGPSignatureList) || ((PGPSignatureList) object).isEmpty()) {
            throw new PGPException("OpenPGP signature part does not contain a detached signature");
        }
        return ((PGPSignatureList) object).get(0);
    }

    @Nullable
    private static PGPPublicKey findVerificationKey(@Nullable final OpenPgpReceiveConfig config,
                                                    final long keyId) throws IOException, PGPException {
        if (config == null) return null;
        for (byte[] keyRing : config.getVerificationKeyRings()) {
            final PGPPublicKey key = publicKeyRings(keyRing).getPublicKey(keyId);
            if (key != null) return key;
        }
        return null;
    }

    private static PGPEncryptedDataList readEncryptedDataList(final byte[] encoded)
            throws IOException, PGPException {
        final InputStream decoder = PGPUtil.getDecoderStream(new ByteArrayInputStream(encoded));
        final PGPObjectFactory factory = new PGPObjectFactory(decoder, new JcaKeyFingerprintCalculator());
        Object object = factory.nextObject();
        while (object != null && !(object instanceof PGPEncryptedDataList)) {
            object = factory.nextObject();
        }
        if (!(object instanceof PGPEncryptedDataList)) {
            throw new PGPException("OpenPGP encrypted part does not contain encrypted data");
        }
        return (PGPEncryptedDataList) object;
    }

    @Nullable
    private static DecryptionMaterial decryptionMaterial(final PGPEncryptedDataList encryptedDataList,
                                                         @Nullable final OpenPgpReceiveConfig config)
            throws IOException, PGPException {
        if (config == null) return null;
		boolean matchingSecretKeyFound = false;
		PGPException lastUnlockFailure = null;
        final Iterator<?> encryptedObjects = encryptedDataList.getEncryptedDataObjects();
        while (encryptedObjects.hasNext()) {
            final Object object = encryptedObjects.next();
            if (!(object instanceof PGPPublicKeyEncryptedData)) continue;
            final PGPPublicKeyEncryptedData encryptedData = (PGPPublicKeyEncryptedData) object;
            for (OpenPgpReceiveConfig.SecretKeyRing configuredRing : config.getDecryptionKeyRings()) {
                final PGPSecretKey secretKey = secretKeyRings(configuredRing.getKeyRing())
                        .getSecretKey(encryptedData.getKeyID());
                if (secretKey == null) continue;
				matchingSecretKeyFound = true;
                char[] passphrase = config.getPassphraseProvider() == null ? null
                        : config.getPassphraseProvider().passphraseFor(encryptedData.getKeyID());
                if (passphrase == null) passphrase = configuredRing.getPassphrase();
                else passphrase = passphrase.clone();
                try {
                    final PGPPrivateKey privateKey = secretKey.extractPrivateKey(
                            new JcePBESecretKeyDecryptorBuilder().setProvider(BOUNCY_CASTLE).build(passphrase));
                    if (privateKey != null) return new DecryptionMaterial(encryptedData, privateKey);
				} catch (PGPException unlockFailure) {
					lastUnlockFailure = unlockFailure;
					// Try another configured ring or recipient before reporting a decryption failure.
                } finally {
                    Arrays.fill(passphrase, '\0');
                }
            }
        }
		if (matchingSecretKeyFound) {
			throw new PGPException("Unable to unlock a matching OpenPGP secret key", lastUnlockFailure);
		}
        return null;
    }

    private static byte[] readLiteralData(final InputStream clearStream) throws IOException, PGPException {
        PGPObjectFactory factory = new PGPObjectFactory(clearStream, new JcaKeyFingerprintCalculator());
        Object object = factory.nextObject();
        while (object instanceof PGPCompressedData) {
            factory = new PGPObjectFactory(((PGPCompressedData) object).getDataStream(),
                    new JcaKeyFingerprintCalculator());
            object = factory.nextObject();
        }
        if (!(object instanceof PGPLiteralData)) {
            throw new PGPException("Decrypted OpenPGP data is not a literal MIME entity");
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputStream input = ((PGPLiteralData) object).getInputStream();
        final byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static List<String> recipientIds(final PGPEncryptedDataList encryptedDataList) {
        final List<String> result = new ArrayList<>();
        final Iterator<?> values = encryptedDataList.getEncryptedDataObjects();
        while (values.hasNext()) {
            final Object value = values.next();
            if (value instanceof PGPPublicKeyEncryptedData) {
                result.add(keyId(((PGPPublicKeyEncryptedData) value).getKeyID()));
            }
        }
        return result;
    }

    private static PGPSecretKeyRingCollection secretKeyRings(final byte[] encoded)
            throws IOException, PGPException {
        return new PGPSecretKeyRingCollection(
                PGPUtil.getDecoderStream(new ByteArrayInputStream(encoded)), new JcaKeyFingerprintCalculator());
    }

    private static PGPPublicKeyRingCollection publicKeyRings(final byte[] encoded)
            throws IOException, PGPException {
        return new PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(new ByteArrayInputStream(encoded)), new JcaKeyFingerprintCalculator());
    }

    private static OpenPgpParseResult failure(final MimeMessage effective, final OpenPgpMode mode,
                                              final SignatureStatus signatureStatus,
                                              final DecryptionStatus decryptionStatus,
                                              final String reason, @Nullable final byte[] original) {
        return new OpenPgpParseResult(mode != OpenPgpMode.PLAIN, effective, OpenPgpDetails.builder()
                .openPgpMode(mode)
                .signatureStatus(signatureStatus)
                .decryptionStatus(decryptionStatus)
                .failureReason(reason)
                .originalProtectedMessage(original)
                .build());
    }

    private static int hashAlgorithm(final OpenPgpSigningConfig.HashAlgorithm algorithm) {
        switch (algorithm) {
            case SHA384: return HashAlgorithmTags.SHA384;
            case SHA512: return HashAlgorithmTags.SHA512;
            case SHA256:
            default: return HashAlgorithmTags.SHA256;
        }
    }

    private static int symmetricAlgorithm(final OpenPgpEncryptionConfig.SymmetricAlgorithm algorithm) {
        switch (algorithm) {
            case AES_128: return SymmetricKeyAlgorithmTags.AES_128;
            case AES_192: return SymmetricKeyAlgorithmTags.AES_192;
            case AES_256:
            default: return SymmetricKeyAlgorithmTags.AES_256;
        }
    }

    private static String hashName(final int algorithm) {
        switch (algorithm) {
            case HashAlgorithmTags.SHA256: return "SHA256";
            case HashAlgorithmTags.SHA384: return "SHA384";
            case HashAlgorithmTags.SHA512: return "SHA512";
            case HashAlgorithmTags.SHA1: return "SHA1";
            default: return "OPENPGP-" + algorithm;
        }
    }

    private static String symmetricAlgorithmName(final int algorithm) {
        switch (algorithm) {
            case SymmetricKeyAlgorithmTags.AES_128: return "AES128";
            case SymmetricKeyAlgorithmTags.AES_192: return "AES192";
            case SymmetricKeyAlgorithmTags.AES_256: return "AES256";
            case SymmetricKeyAlgorithmTags.TRIPLE_DES: return "TRIPLE_DES";
            default: return "OPENPGP-" + algorithm;
        }
    }

    private static String publicKeyAlgorithmName(final int algorithm) {
        switch (algorithm) {
            case PublicKeyAlgorithmTags.RSA_GENERAL: return "RSA";
            case PublicKeyAlgorithmTags.RSA_SIGN: return "RSA_SIGN";
            case PublicKeyAlgorithmTags.RSA_ENCRYPT: return "RSA_ENCRYPT";
            case PublicKeyAlgorithmTags.DSA: return "DSA";
            case PublicKeyAlgorithmTags.ECDSA: return "ECDSA";
            default: return "OPENPGP-" + algorithm;
        }
    }

    private static String keyId(final long keyId) {
        return String.format(Locale.ROOT, "%016X", keyId);
    }

    private static String hex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) result.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return result.toString();
    }

    private static String boundary(final String type) {
        return "----=_SimpleJavaMail_OpenPGP_" + type + '_' + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireSevenBit(final byte[] signedEntity) {
        for (byte value : signedEntity) {
            if ((value & 0x80) != 0) {
                throw new IllegalArgumentException("OpenPGP/MIME signed entities must use a 7-bit-safe Content-Transfer-Encoding");
            }
        }
    }

    private static final class SigningMaterial {
        final PGPPublicKey publicKey;
        final PGPPrivateKey privateKey;

        SigningMaterial(final PGPPublicKey publicKey, final PGPPrivateKey privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }
    }

    private static final class DecryptionMaterial {
        final PGPPublicKeyEncryptedData encryptedData;
        final PGPPrivateKey privateKey;

        DecryptionMaterial(final PGPPublicKeyEncryptedData encryptedData, final PGPPrivateKey privateKey) {
            this.encryptedData = encryptedData;
            this.privateKey = privateKey;
        }
    }
}
