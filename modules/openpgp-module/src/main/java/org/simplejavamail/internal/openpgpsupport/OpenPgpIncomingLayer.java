package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.bouncycastle.openpgp.PGPException;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.openpgpsupport.PgpainlessOpenPgpAdapter.LayerDecryption;
import org.simplejavamail.internal.openpgpsupport.PgpainlessOpenPgpAdapter.LayerSignatures;
import org.simplejavamail.internal.openpgpsupport.PgpainlessOpenPgpAdapter.PassphraseProviderFailure;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.protection.ProtectionInputReadException;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Byte-preserving RFC 3156 processing for one envelope; repeated and cross-family layers belong to the importer. */
final class OpenPgpIncomingLayer {
    private final PgpainlessOpenPgpAdapter adapter;

    OpenPgpIncomingLayer(final PgpainlessOpenPgpAdapter adapter) {
        this.adapter = adapter;
    }

    ProtectionLayerResult open(final Session session, final MimeMessage original,
            @Nullable final OpenPgpReceiveConfig config) {
        final ContentType declaration;
        final boolean signedShape;
        final boolean encryptedShape;
        try {
            signedShape = original.isMimeType("multipart/signed");
            encryptedShape = original.isMimeType("multipart/encrypted");
            declaration = new ContentType(original.getContentType());
        } catch (MessagingException unavailableDeclaration) {
            InterruptedIoGuard.rethrowIfInterrupted(unavailableDeclaration);
            return result(original, false).inspectionComplete(false).build();
        }
        final boolean signed = signedShape
                && "application/pgp-signature".equalsIgnoreCase(declaration.getParameter("protocol"));
        final boolean encrypted = encryptedShape
                && "application/pgp-encrypted".equalsIgnoreCase(declaration.getParameter("protocol"));
        if (!signed && !encrypted && ("application/pgp-signature".equalsIgnoreCase(declaration.getParameter("protocol"))
                || "application/pgp-encrypted".equalsIgnoreCase(declaration.getParameter("protocol")))) {
            return result(original, false).inspectionComplete(false)
                    .failureReason("The OpenPGP MIME type conflicts with its declared protocol").build();
        }
        if (!signed && !encrypted) return result(original, false).inspectionComplete(true).build();
        try {
            final byte[] bytes = MimeEntitySupport.serializeIncoming(original);
            final List<byte[]> parts = parts(bytes, declaration.getParameter("boundary"));
            if (parts.size() != 2) throw new MessagingException("The OpenPGP envelope does not contain its required two MIME parts");
            return signed ? openSigned(session, original, bytes, parts, config)
                    : openEncrypted(session, original, bytes, parts, config);
        } catch (PassphraseProviderFailure callbackFailure) {
            throw callbackFailure.originalFailure();
        } catch (MessagingException | IOException | PGPException inputFailure) {
            PassphraseProviderFailure.rethrowIfPresent(inputFailure);
            InterruptedIoGuard.rethrowIfInterrupted(inputFailure);
            final String reason = "Unable to open OpenPGP/MIME content: " + inputFailure.getClass().getSimpleName();
            final ProtectionLayerResult.ProtectionLayerResultBuilder failed = result(original, true)
                    .signatureEnvelope(signed).inspectionComplete(false).failureReason(reason).processingFailure(inputFailure);
            if (!signed) {
                failed.decryptions(Collections.singletonList(DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP)
                        .status(DecryptionStatus.FAILED).coverage(ProtectionCoverage.UNKNOWN).failureReason(reason).build()));
            }
            return acquisition(failed, inputFailure).build();
        }
    }

    private ProtectionLayerResult openSigned(final Session session, final MimeMessage original,
            final byte[] originalBytes, final List<byte[]> parts, @Nullable final OpenPgpReceiveConfig config)
            throws IOException, PGPException, MessagingException {
        final byte[] signedEntity = parts.get(0);
        List<SignatureObservation> signatures = Collections.emptyList();
        boolean complete = false;
        String reason = null;
        Throwable signatureFailure = null;
        if (contradictsDeclaredType(parts.get(1), "application/pgp-signature")) {
            reason = "The OpenPGP signature part does not declare application/pgp-signature";
        } else {
            try {
                final LayerSignatures verification = adapter.verifyLayerSignatures(MimeEntitySupport.normalizeCrlf(signedEntity),
                        MimeEntitySupport.decodedPartBody(parts.get(1)), config);
                signatures = verification.signatures;
                complete = verification.complete;
                reason = verification.failureReason;
            } catch (IOException | PGPException unavailableSignature) {
                InterruptedIoGuard.rethrowIfInterrupted(unavailableSignature);
                reason = "The OpenPGP signature could not be read or verified";
                signatureFailure = unavailableSignature;
            }
        }
        boolean authored = false;
        try {
            final MimeBodyPart entity = part(signedEntity);
            authored = entity.isMimeType("message/rfc822");
            return acquisition(opened(session, originalBytes, signedEntity, entity, authored).signatureEnvelope(true)
                    .signatures(signatures).inspectionComplete(complete)
                    .failureReason(reason).processingFailure(signatureFailure), signatureFailure).build();
        } catch (MessagingException | IOException interpretationFailure) {
            InterruptedIoGuard.rethrowIfInterrupted(interpretationFailure);
            final MessagingException failure = new MessagingException("The OpenPGP signed MIME content could not be interpreted", interpretationFailure);
            if (signatureFailure != null) failure.addSuppressed(signatureFailure);
            final Throwable inputFailure = interpretationFailure instanceof ProtectionInputReadException
                    ? interpretationFailure : signatureFailure;
            return acquisition(result(original, true).signatureEnvelope(true).signatures(signatures).inspectionComplete(false)
                    .authoredHeaders(authored).failureReason(failure.getMessage()).processingFailure(failure), inputFailure).build();
        }
    }

    private ProtectionLayerResult openEncrypted(final Session session, final MimeMessage original,
            final byte[] originalBytes, final List<byte[]> parts, @Nullable final OpenPgpReceiveConfig config)
            throws IOException, PGPException, MessagingException {
        if (contradictsDeclaredType(parts.get(0), "application/pgp-encrypted")) {
            throw new MessagingException("The OpenPGP control part does not declare application/pgp-encrypted");
        }
        final String control = new String(MimeEntitySupport.decodedPartBody(parts.get(0)), StandardCharsets.US_ASCII).trim();
        if (!control.matches("(?i)Version:[ \\t]*1")) {
            throw new MessagingException("The OpenPGP control part does not contain its supported Version: 1 declaration");
        }
        if (contradictsDeclaredType(parts.get(1), "application/octet-stream")) {
            throw new MessagingException("The OpenPGP encrypted part does not declare application/octet-stream");
        }
        final LayerDecryption decryption = adapter.decryptLayer(MimeEntitySupport.decodedPartBody(parts.get(1)), config);
        if (decryption.clearContent == null) {
            return result(original, true).inspectionComplete(false).signatures(decryption.signatures)
                    .failureReason(decryption.failureReason).processingFailure(decryption.processingFailure)
                    .decryptions(Collections.singletonList(DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP)
                            .status(decryption.status).coverage(ProtectionCoverage.UNKNOWN)
                            .recipientKeyIds(decryption.recipientKeyIds)
                            .failureReason(decryption.failureReason).build())).build();
        }
        boolean authored = false;
        try {
            final MimeBodyPart entity = part(decryption.clearContent);
            authored = entity.isMimeType("message/rfc822");
            return opened(session, originalBytes, decryption.clearContent, entity, authored).inspectionComplete(true).signatures(decryption.signatures)
                    .decryptions(Collections.singletonList(DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP)
                            .status(DecryptionStatus.DECRYPTED).coverage(ProtectionCoverage.FULL)
                            .recipientKeyIds(decryption.recipientKeyIds)
                            .encryptionAlgorithm(decryption.algorithm).build())).build();
        } catch (MessagingException | IOException unusableClearContent) {
            InterruptedIoGuard.rethrowIfInterrupted(unusableClearContent);
            final String reason = "OpenPGP decryption completed, but its MIME content could not be interpreted";
            return acquisition(result(original, true).inspectionComplete(false).signatures(decryption.signatures).failureReason(reason)
                    .authoredHeaders(authored)
                    .processingFailure(unusableClearContent)
                    .decryptions(Collections.singletonList(DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP)
                            .status(DecryptionStatus.DECRYPTED).coverage(ProtectionCoverage.UNKNOWN)
                            .recipientKeyIds(decryption.recipientKeyIds)
                            .encryptionAlgorithm(decryption.algorithm).failureReason(reason).build())), unusableClearContent).build();
        }
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder opened(final Session session,
            final byte[] originalBytes, final byte[] clearEntity, final MimeBodyPart entity, final boolean authored)
            throws MessagingException, IOException {
        final byte[] effectiveBytes;
        if (authored) {
            effectiveBytes = ProtectionInputReadException.acquire(entity::getInputStream);
        } else {
            try {
                effectiveBytes = MimeEntitySupport.restoreMessage(originalBytes, clearEntity);
            } catch (IllegalArgumentException malformedEntity) {
                throw new MessagingException("The protected OpenPGP MIME entity has no complete header section", malformedEntity);
            }
        }
        final MimeMessage effective = FinalizedMimeMessage.fromMessageBytes(session, effectiveBytes,
                FinalizedMimeMessage.ProtectionState.NONE);
        return result(effective, true).opened(true).openedEntity(entity).authoredHeaders(authored);
    }

    private static List<byte[]> parts(final byte[] bytes, @Nullable final String boundary) throws MessagingException {
        if (boundary == null) throw new MessagingException("The OpenPGP multipart boundary is missing");
        try {
            return MimeEntitySupport.multipartParts(bytes, boundary);
        } catch (IllegalArgumentException malformedEntity) {
            throw new MessagingException("The OpenPGP envelope has no complete MIME header section", malformedEntity);
        }
    }

    private static MimeBodyPart part(final byte[] bytes) throws MessagingException {
        return new MimeBodyPart(new ByteArrayInputStream(bytes));
    }

    private static boolean contradictsDeclaredType(final byte[] bytes, final String supportedType) throws MessagingException {
        final MimeBodyPart part = part(bytes);
        // Preserve the existing headerless-payload interpretation; an explicit incompatible declaration is different.
        return part.getHeader("Content-Type") != null && !part.isMimeType(supportedType);
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder result(final MimeMessage message, final boolean recognized) {
        return ProtectionLayerResult.builder().recognized(recognized).effectiveMimeMessage(message);
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder acquisition(
            final ProtectionLayerResult.ProtectionLayerResultBuilder result, @Nullable final Throwable failure) {
        if (failure instanceof ProtectionInputReadException) {
            final ProtectionInputReadException input = (ProtectionInputReadException) failure;
            result.inputReadFailure(input.getReadFailure()).inputCloseFailure(input.getCloseFailure());
        }
        return result;
    }
}
