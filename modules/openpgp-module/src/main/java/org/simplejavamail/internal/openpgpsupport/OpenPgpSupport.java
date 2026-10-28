package org.simplejavamail.internal.openpgpsupport;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.openpgp.PGPException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pgpainless.exception.KeyException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OpenPgpSignatureResult;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** RFC 3156 OpenPGP/MIME implementation backed by PGPainless. */
public final class OpenPgpSupport implements OpenPgpModule {

    // Two wrappers cover the normal sign-then-encrypt shape. Stopping there bounds work on hostile recursive input.
    private static final int MAX_PROTECTION_DEPTH = 2;

    private final PgpainlessOpenPgpAdapter pgpainlessAdapter = new PgpainlessOpenPgpAdapter();

    private static final class PreparationFailure extends org.simplejavamail.MailException {
        private static final long serialVersionUID = 1L;
        private PreparationFailure(final String message, final Throwable cause) {
            super(MailFailureKind.PREPARATION, message, cause, null);
        }
    }

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
            throw new PreparationFailure("Unable to sign OpenPGP/MIME message", e);
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
            throw new PreparationFailure("Unable to encrypt OpenPGP/MIME message", e);
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
            return failure(originalMessage, OpenPgpMode.UNKNOWN, SignatureStatus.UNKNOWN,
                    DecryptionStatus.UNKNOWN, "Unable to inspect OpenPGP/MIME structure", null);
        }
    }

    private OpenPgpParseResult inspectProtection(final Session session, final MimeMessage message,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig,
            final int depth) throws MessagingException {
        final boolean signed = isOpenPgpSigned(message);
        final boolean encrypted = isOpenPgpEncrypted(message);
        if ((signed || encrypted) && depth >= MAX_PROTECTION_DEPTH) {
            return failure(message, signed ? OpenPgpMode.SIGNED : OpenPgpMode.ENCRYPTED,
                    signed ? SignatureStatus.ERROR : SignatureStatus.UNKNOWN,
                    encrypted ? DecryptionStatus.FAILED : DecryptionStatus.NOT_ENCRYPTED,
                    "OpenPGP/MIME nesting limit exceeded", protectedMessage);
        }
        if (signed) {
            return verifySignedMessage(session, message, protectedMessage, receiveConfig, depth);
        }
        if (encrypted) {
            return decryptEncryptedMessage(session, message, protectedMessage, receiveConfig, depth);
        }
        return OpenPgpParseResultImpl.builder()
            .recognized(false)
            .effectiveMimeMessage(message)
            .details(OpenPgpDetailsImpl.plain())
            .build();
    }

    private OpenPgpParseResult verifySignedMessage(final Session session, final MimeMessage originalMessage,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig, final int depth) {
        byte[] readableMessageBytes = null;
        OpenPgpDetailsImpl established = null;
        try {
            final List<byte[]> mimeParts = MimeEntitySupport.multipartParts(
                    protectedMessage, MimeEntitySupport.boundary(originalMessage));
            if (mimeParts.size() != 2) {
                return failure(originalMessage, OpenPgpMode.SIGNED, SignatureStatus.ERROR,
                        DecryptionStatus.NOT_ENCRYPTED, "Malformed OpenPGP multipart/signed message", protectedMessage);
            }

            final byte[] signedEntity = mimeParts.get(0);
            readableMessageBytes = MimeEntitySupport.restoreMessage(protectedMessage, signedEntity);
            established = pgpainlessAdapter.verifyDetachedSignatures(
                    MimeEntitySupport.normalizeCrlf(signedEntity), MimeEntitySupport.decodedPartBody(mimeParts.get(1)),
                    receiveConfig, protectedMessage);
            final MimeMessage readableMessage = FinalizedMimeMessage.fromMessageBytes(session, readableMessageBytes,
                    FinalizedMimeMessage.ProtectionState.NONE);
            if (isOpenPgpSigned(readableMessage) || isOpenPgpEncrypted(readableMessage)) {
                final OpenPgpParseResult nested = inspectProtection(session, readableMessage, readableMessageBytes, receiveConfig, depth + 1);
                return combinedProtection(nested, protectedMessage, established);
            }
            return OpenPgpParseResultImpl.builder()
                .recognized(true)
                .effectiveMimeMessage(readableMessage)
                .details(established)
                .build();
        } catch (Exception e) {
            return signatureFailure(session, originalMessage, readableMessageBytes, protectedMessage, established);
        }
    }

    private static OpenPgpParseResult signatureFailure(final Session session, final MimeMessage originalMessage,
            @Nullable final byte[] readableMessageBytes, final byte[] protectedMessage, @Nullable final OpenPgpDetailsImpl established) {
        final OpenPgpDetailsImpl details = (established == null ? OpenPgpDetailsImpl.builder().openPgpMode(OpenPgpMode.SIGNED)
                .decryptionStatus(DecryptionStatus.NOT_ENCRYPTED) : established.toBuilder())
                .signatureStatus(OpenPgpDetailsImpl.strongestProblem(SignatureStatus.ERROR,
                        established == null ? SignatureStatus.NOT_PRESENT : established.getSignatureStatus()))
                .signatureInspectionComplete(false).failureReason("Unable to verify OpenPGP signature")
                .originalProtectedMessage(protectedMessage).build();
        try {
            final MimeMessage readableMessage = readableMessageBytes == null ? originalMessage
                    : FinalizedMimeMessage.fromMessageBytes(session, readableMessageBytes,
                            FinalizedMimeMessage.ProtectionState.NONE);
            return OpenPgpParseResultImpl.builder().recognized(true).effectiveMimeMessage(readableMessage).details(details).build();
        } catch (MessagingException ignored) {
            return OpenPgpParseResultImpl.builder().recognized(true).effectiveMimeMessage(originalMessage).details(details).build();
        }
    }

    private OpenPgpParseResult decryptEncryptedMessage(final Session session, final MimeMessage originalMessage,
            final byte[] protectedMessage, @Nullable final OpenPgpReceiveConfig receiveConfig, final int depth) {
        final DecryptionResult decryptionResult;
        try {
            final List<byte[]> mimeParts = MimeEntitySupport.multipartParts(
                    protectedMessage, MimeEntitySupport.boundary(originalMessage));
            if (mimeParts.size() != 2) {
                return failure(originalMessage, OpenPgpMode.ENCRYPTED, SignatureStatus.UNKNOWN,
                        DecryptionStatus.FAILED, "Malformed OpenPGP multipart/encrypted message", protectedMessage);
            }

            decryptionResult = pgpainlessAdapter.decrypt(
                    MimeEntitySupport.decodedPartBody(mimeParts.get(1)), receiveConfig);
        } catch (Exception e) {
            return failure(originalMessage, OpenPgpMode.ENCRYPTED, SignatureStatus.UNKNOWN,
                    DecryptionStatus.FAILED, "Unable to decrypt OpenPGP/MIME message", protectedMessage);
        }
        if (!decryptionResult.isDecrypted()) {
            return OpenPgpParseResultImpl.builder().recognized(true).effectiveMimeMessage(originalMessage)
                    .details(decryptionResult.details.toBuilder().originalProtectedMessage(protectedMessage).build()).build();
        }
        try {
            return inspectDecryptedMessage(session, protectedMessage, receiveConfig, depth, decryptionResult);
        } catch (Exception processingFailure) {
            // Successful native close already established decryption; a later MIME/signature problem cannot erase that fact.
            return OpenPgpParseResultImpl.builder().recognized(true).effectiveMimeMessage(originalMessage)
                    .details(decryptionResult.details.toBuilder().originalProtectedMessage(protectedMessage)
                            .signatureStatus(OpenPgpDetailsImpl.strongestProblem(SignatureStatus.ERROR, decryptionResult.details.getSignatureStatus()))
                            .signatureInspectionComplete(false).failureReason("Unable to inspect decrypted OpenPGP MIME content").build()).build();
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
        return combinedProtection(nestedProtection, protectedMessage, decryptionResult.details);
    }

    private static OpenPgpParseResult decryptedMessage(final MimeMessage clearMessage, final byte[] protectedMessage,
            final DecryptionResult decryptionResult) {
        return OpenPgpParseResultImpl.builder()
            .recognized(true)
            .effectiveMimeMessage(clearMessage)
            .details(decryptionResult.details.toBuilder().originalProtectedMessage(protectedMessage).build())
            .build();
    }

    private static OpenPgpParseResult combinedProtection(final OpenPgpParseResult nestedProtection,
            final byte[] protectedMessage, final OpenPgpDetailsImpl outer) {
        final OpenPgpDetailsImpl nestedDetails = OpenPgpDetailsImpl.copyOf(nestedProtection.getDetails());
        final List<OpenPgpSignatureResult> signatures = new ArrayList<>(outer.getSignatures());
        signatures.addAll(nestedDetails.getSignatures());
        final boolean signed = signed(outer) || signed(nestedDetails);
        final boolean encrypted = encrypted(outer) || encrypted(nestedDetails);
        final DecryptionStatus nestedDecryption = nestedDetails.getDecryptionStatus();
        final OpenPgpDetailsImpl combined = OpenPgpDetailsImpl.builder()
                .openPgpMode(encrypted ? signed ? OpenPgpMode.SIGNED_ENCRYPTED : OpenPgpMode.ENCRYPTED : signed ? OpenPgpMode.SIGNED : OpenPgpMode.PLAIN)
                .signatures(signatures).signatureInspectionComplete(outer.isSignatureInspectionComplete() && nestedDetails.isSignatureInspectionComplete())
                .signatureStatus(OpenPgpDetailsImpl.strongestProblem(outer.getSignatureStatus(), nestedDetails.getSignatureStatus()))
                .decryptionStatus(nestedDecryption == DecryptionStatus.NOT_ENCRYPTED
                        ? outer.getDecryptionStatus() : nestedDecryption)
                .encryptionAlgorithm(outer.getEncryptionAlgorithm() != null ? outer.getEncryptionAlgorithm() : nestedDetails.getEncryptionAlgorithm())
                .recipientKeyIds(encrypted(outer) ? outer.getRecipientKeyIds() : nestedDetails.getRecipientKeyIds())
                .failureReason(outer.getFailureReason() != null ? outer.getFailureReason() : nestedDetails.getFailureReason())
                .originalProtectedMessage(protectedMessage)
                .build();
        return OpenPgpParseResultImpl.builder()
            .recognized(true)
            .effectiveMimeMessage(nestedProtection.getEffectiveMimeMessage())
            .details(combined)
            .build();
    }

    private static boolean signed(final OriginalOpenPgpDetails details) {
        return details.getOpenPgpMode() == OpenPgpMode.SIGNED || details.getOpenPgpMode() == OpenPgpMode.SIGNED_ENCRYPTED;
    }

    private static boolean encrypted(final OriginalOpenPgpDetails details) {
        return details.getOpenPgpMode() == OpenPgpMode.ENCRYPTED || details.getOpenPgpMode() == OpenPgpMode.SIGNED_ENCRYPTED;
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
        return OpenPgpParseResultImpl.builder()
            .recognized(mode != OpenPgpMode.PLAIN && mode != OpenPgpMode.UNKNOWN)
            .effectiveMimeMessage(effectiveMessage)
            .details(OpenPgpDetailsImpl.builder()
                .openPgpMode(mode)
                .signatureStatus(signatureStatus)
                .decryptionStatus(decryptionStatus)
                .failureReason(reason)
                .originalProtectedMessage(originalProtectedMessage)
                .build())
            .build();
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
