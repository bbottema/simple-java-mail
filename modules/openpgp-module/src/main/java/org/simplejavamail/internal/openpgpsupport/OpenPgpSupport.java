package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.openpgp.PGPException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pgpainless.exception.KeyException;
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
import org.simplejavamail.internal.openpgpsupport.PgpainlessOpenPgpAdapter.DecryptionResult;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** RFC 3156 OpenPGP/MIME implementation backed by PGPainless. */
public final class OpenPgpSupport implements OpenPgpModule {

    // Two wrappers cover the normal sign-then-encrypt shape. Stopping there bounds work on hostile recursive input.
    private static final int MAX_PROTECTION_DEPTH = 2;

    private final PgpainlessOpenPgpAdapter pgpainlessAdapter = new PgpainlessOpenPgpAdapter();

    @NotNull
    @Override
    public MimeMessage signMessage(@NotNull final Session session, @NotNull final Email email,
            @NotNull final MimeMessage message, @NotNull final OpenPgpSigningConfig signingConfig) {
        try {
            final byte[] serializedMessage = MimeEntitySupport.serialize(message);
            final byte[] signedEntity = MimeEntitySupport.normalizeCrlf(
                    MimeEntitySupport.contentEntity(serializedMessage));
            requireSevenBit(signedEntity);
            final byte[] signature = pgpainlessAdapter.createDetachedSignature(signedEntity, signingConfig);
            final byte[] protectedMessage = MimeEntitySupport.wrapSigned(serializedMessage, signature, boundary("signed"),
                    "pgp-" + signingConfig.getHashAlgorithm().name().toLowerCase(Locale.ROOT));
            return FinalizedMimeMessage.fromMessageBytes(session, protectedMessage,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException | KeyException e) {
            throw new IllegalStateException("Unable to sign OpenPGP/MIME message", e);
        }
    }

    @NotNull
    @Override
    public MimeMessage encryptMessage(@NotNull final Session session, @NotNull final Email email,
            @NotNull final MimeMessage message, @NotNull final OpenPgpEncryptionConfig encryptionConfig) {
        try {
            final byte[] serializedMessage = MimeEntitySupport.serialize(message);
            final byte[] encryptedEntity = pgpainlessAdapter.encrypt(
                    MimeEntitySupport.contentEntity(serializedMessage), encryptionConfig);
            final byte[] protectedMessage = MimeEntitySupport.wrapEncrypted(
                    serializedMessage, encryptedEntity, boundary("encrypted"));
            return FinalizedMimeMessage.fromMessageBytes(session, protectedMessage,
                    FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        } catch (IOException | PGPException | MessagingException | KeyException e) {
            throw new IllegalStateException("Unable to encrypt OpenPGP/MIME message", e);
        }
    }

    @NotNull
    @Override
    public OpenPgpParseResult processIncoming(@NotNull final Session session,
            @NotNull final MimeMessage originalMessage, @Nullable final OpenPgpReceiveConfig receiveConfig) {
        try {
            return inspectProtection(session, originalMessage, MimeEntitySupport.serialize(originalMessage),
                    receiveConfig, 0);
        } catch (MessagingException e) {
            return failure(originalMessage, OpenPgpMode.PLAIN, SignatureStatus.ERROR,
                    DecryptionStatus.NOT_ENCRYPTED, "Unable to inspect OpenPGP/MIME structure", null);
        }
    }

    private OpenPgpParseResult inspectProtection(final Session session, final MimeMessage message,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig,
            final int depth) throws MessagingException {
        final boolean signed = isOpenPgpSigned(message);
        final boolean encrypted = isOpenPgpEncrypted(message);
        if ((signed || encrypted) && depth >= MAX_PROTECTION_DEPTH) {
            return failure(message, signed ? OpenPgpMode.SIGNED : OpenPgpMode.ENCRYPTED,
                    signed ? SignatureStatus.ERROR : SignatureStatus.NOT_PRESENT,
                    encrypted ? DecryptionStatus.FAILED : DecryptionStatus.NOT_ENCRYPTED,
                    "OpenPGP/MIME nesting limit exceeded", protectedMessage);
        }
        if (signed) {
            return verifySignedMessage(session, message, protectedMessage, receiveConfig);
        }
        if (encrypted) {
            return decryptEncryptedMessage(session, message, protectedMessage, receiveConfig, depth);
        }
        return new OpenPgpParseResult(false, message, OpenPgpDetails.plain());
    }

    private OpenPgpParseResult verifySignedMessage(final Session session, final MimeMessage originalMessage,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig) {
        byte[] readableMessageBytes = null;
        try {
            final List<byte[]> mimeParts = MimeEntitySupport.multipartParts(
                    protectedMessage, MimeEntitySupport.boundary(originalMessage));
            if (mimeParts.size() != 2) {
                return failure(originalMessage, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                        DecryptionStatus.NOT_ENCRYPTED, "Malformed OpenPGP multipart/signed message", protectedMessage);
            }

            final byte[] signedEntity = mimeParts.get(0);
            readableMessageBytes = MimeEntitySupport.restoreMessage(protectedMessage, signedEntity);
            final MimeMessage readableMessage = FinalizedMimeMessage.fromMessageBytes(session, readableMessageBytes,
                    FinalizedMimeMessage.ProtectionState.NONE);
            final OpenPgpDetails details = pgpainlessAdapter.verifyDetachedSignature(
                    MimeEntitySupport.normalizeCrlf(signedEntity), MimeEntitySupport.partBody(mimeParts.get(1)),
                    receiveConfig, protectedMessage);
            return new OpenPgpParseResult(true, readableMessage, details);
        } catch (Exception e) {
            return signatureFailure(session, originalMessage, readableMessageBytes, protectedMessage);
        }
    }

    private static OpenPgpParseResult signatureFailure(final Session session, final MimeMessage originalMessage,
            @Nullable final byte[] readableMessageBytes, final byte[] protectedMessage) {
        try {
            final MimeMessage readableMessage = readableMessageBytes == null ? originalMessage
                    : FinalizedMimeMessage.fromMessageBytes(session, readableMessageBytes,
                            FinalizedMimeMessage.ProtectionState.NONE);
            return failure(readableMessage, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                    DecryptionStatus.NOT_ENCRYPTED, "Unable to verify OpenPGP signature", protectedMessage);
        } catch (MessagingException ignored) {
            return failure(originalMessage, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                    DecryptionStatus.NOT_ENCRYPTED, "Unable to verify OpenPGP signature", protectedMessage);
        }
    }

    private OpenPgpParseResult decryptEncryptedMessage(final Session session, final MimeMessage originalMessage,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig, final int depth) {
        try {
            final List<byte[]> mimeParts = MimeEntitySupport.multipartParts(
                    protectedMessage, MimeEntitySupport.boundary(originalMessage));
            if (mimeParts.size() != 2) {
                return failure(originalMessage, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                        DecryptionStatus.FAILED, "Malformed OpenPGP multipart/encrypted message", protectedMessage);
            }

            final DecryptionResult decryptionResult = pgpainlessAdapter.decrypt(
                    MimeEntitySupport.partBody(mimeParts.get(1)), receiveConfig);
            if (!decryptionResult.isDecrypted()) {
                return missingDecryptionKey(originalMessage, protectedMessage, decryptionResult.recipientKeyIds);
            }
            return inspectDecryptedMessage(session, protectedMessage, receiveConfig, depth, decryptionResult);
        } catch (Exception e) {
            return failure(originalMessage, OpenPgpMode.ENCRYPTED, SignatureStatus.NOT_PRESENT,
                    DecryptionStatus.FAILED, "Unable to decrypt OpenPGP/MIME message", protectedMessage);
        }
    }

    private OpenPgpParseResult inspectDecryptedMessage(final Session session, final byte[] protectedMessage,
            @Nullable final OpenPgpReceiveConfig receiveConfig, final int depth,
            final DecryptionResult decryptionResult)
            throws MessagingException {
        final byte[] clearMessageBytes = MimeEntitySupport.restoreMessage(
                protectedMessage, decryptionResult.clearContent);
        final MimeMessage clearMessage = FinalizedMimeMessage.fromMessageBytes(session, clearMessageBytes,
                FinalizedMimeMessage.ProtectionState.NONE);
        if (!isOpenPgpSigned(clearMessage) && !isOpenPgpEncrypted(clearMessage)) {
            return decryptedMessage(clearMessage, protectedMessage, decryptionResult);
        }

        final OpenPgpParseResult nestedProtection = inspectProtection(
                session, clearMessage, clearMessageBytes, receiveConfig, depth + 1);
        return combinedProtection(nestedProtection, protectedMessage, decryptionResult);
    }

    private static OpenPgpParseResult decryptedMessage(final MimeMessage clearMessage, final byte[] protectedMessage,
            final DecryptionResult decryptionResult) {
        return new OpenPgpParseResult(true, clearMessage, OpenPgpDetails.builder()
                .openPgpMode(OpenPgpMode.ENCRYPTED)
                .signatureStatus(SignatureStatus.NOT_PRESENT)
                .decryptionStatus(DecryptionStatus.DECRYPTED)
                .encryptionAlgorithm(decryptionResult.encryptionAlgorithm)
                .recipientKeyIds(decryptionResult.recipientKeyIds)
                .originalProtectedMessage(protectedMessage)
                .build());
    }

    private static OpenPgpParseResult combinedProtection(final OpenPgpParseResult nestedProtection,
            final byte[] protectedMessage, final DecryptionResult decryptionResult) {
        final OriginalOpenPgpDetails nestedDetails = nestedProtection.getDetails();
        final boolean containsSignature = nestedDetails.getOpenPgpMode() == OpenPgpMode.SIGNED
                || nestedDetails.getOpenPgpMode() == OpenPgpMode.SIGNED_ENCRYPTED;
        final DecryptionStatus nestedDecryption = nestedDetails.getDecryptionStatus();
        final OpenPgpDetails combined = OpenPgpDetails.builder()
                .openPgpMode(containsSignature ? OpenPgpMode.SIGNED_ENCRYPTED : OpenPgpMode.ENCRYPTED)
                .signatureStatus(nestedDetails.getSignatureStatus())
                .decryptionStatus(nestedDecryption == DecryptionStatus.NOT_ENCRYPTED
                        ? DecryptionStatus.DECRYPTED : nestedDecryption)
                .signerKeyId(nestedDetails.getSignerKeyId())
                .signerFingerprint(nestedDetails.getSignerFingerprint())
                .signatureAlgorithm(nestedDetails.getSignatureAlgorithm())
                .hashAlgorithm(nestedDetails.getHashAlgorithm())
                .encryptionAlgorithm(decryptionResult.encryptionAlgorithm)
                .recipientKeyIds(decryptionResult.recipientKeyIds)
                .failureReason(nestedDetails.getFailureReason())
                .originalProtectedMessage(protectedMessage)
                .build();
        return new OpenPgpParseResult(true, nestedProtection.getEffectiveMimeMessage(), combined);
    }

    private static OpenPgpParseResult missingDecryptionKey(final MimeMessage originalMessage,
            final byte[] protectedMessage,
            final List<String> recipientKeyIds) {
        return new OpenPgpParseResult(true, originalMessage, OpenPgpDetails.builder()
                .openPgpMode(OpenPgpMode.ENCRYPTED)
                .signatureStatus(SignatureStatus.NOT_PRESENT)
                .decryptionStatus(DecryptionStatus.KEY_MISSING)
                .recipientKeyIds(recipientKeyIds)
                .failureReason("No decryption key matched an OpenPGP recipient")
                .originalProtectedMessage(protectedMessage)
                .build());
    }

    private static boolean isOpenPgpSigned(final MimeMessage message) throws MessagingException {
        if (!message.isMimeType("multipart/signed")) {
            return false;
        }
        final String protocol = new ContentType(message.getContentType()).getParameter("protocol");
        return "application/pgp-signature".equalsIgnoreCase(protocol);
    }

    private static boolean isOpenPgpEncrypted(final MimeMessage message) throws MessagingException {
        if (!message.isMimeType("multipart/encrypted")) {
            return false;
        }
        final String protocol = new ContentType(message.getContentType()).getParameter("protocol");
        return "application/pgp-encrypted".equalsIgnoreCase(protocol);
    }

    private static OpenPgpParseResult failure(final MimeMessage effectiveMessage, final OpenPgpMode mode,
            final SignatureStatus signatureStatus, final DecryptionStatus decryptionStatus,
            final String reason, @Nullable final byte[] originalProtectedMessage) {
        return new OpenPgpParseResult(mode != OpenPgpMode.PLAIN, effectiveMessage, OpenPgpDetails.builder()
                .openPgpMode(mode)
                .signatureStatus(signatureStatus)
                .decryptionStatus(decryptionStatus)
                .failureReason(reason)
                .originalProtectedMessage(originalProtectedMessage)
                .build());
    }

    private static String boundary(final String type) {
        return "----=_SimpleJavaMail_OpenPGP_" + type + '_' + UUID.randomUUID().toString().replace("-", "");
    }

    private static void requireSevenBit(final byte[] signedEntity) {
        for (byte value : signedEntity) {
            if ((value & 0x80) != 0) {
                throw new IllegalArgumentException(
                        "OpenPGP/MIME signed entities must use a 7-bit-safe Content-Transfer-Encoding");
            }
        }
    }
}
