package org.simplejavamail.converter.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime;
import org.simplejavamail.api.internal.smimesupport.model.PlainSmimeDetails;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEMLByteArray;
import static org.simplejavamail.internal.util.InterruptedIoGuard.rethrowIfInterrupted;

/** Declaration recognition and available source evidence when ordinary import cannot use cryptographic interpretation. */
final class UnavailableSmimeInspection {

    private UnavailableSmimeInspection() {
    }

    static OriginalSmimeDetails unknown() {
        return Details.builder().smimeMode(SmimeMode.UNKNOWN).build();
    }

    static OriginalSmimeDetails fromMessage(final MimeMessage message) {
        return inspect(message, true);
    }

    /** A limited projection must not acquire an omitted payload merely to complete diagnostic evidence. */
    static OriginalSmimeDetails fromDeclaration(final MimeMessage message) {
        return inspect(message, false);
    }

    private static OriginalSmimeDetails inspect(final MimeMessage message, final boolean allowSerialization) {
        final ContentType type;
        try {
            type = new ContentType(message.getContentType());
        } catch (MessagingException invalidContentType) {
            rethrowIfInterrupted(invalidContentType);
            // A malformed declaration establishes neither plain content nor a cryptographic failure.
            return unknown();
        }
        if (!isPotentialSmime(type.getBaseType(), type.getParameter("protocol"))) {
            return new PlainSmimeDetails();
        }
        return Details.builder()
                .smimeMode(mode(type.getBaseType(), type.getParameter("smime-type"), type.getParameter("protocol")))
                .smimeMime(type.getBaseType())
                .smimeType(type.getParameter("smime-type"))
                .smimeName(type.getParameter("name"))
                .smimeProtocol(type.getParameter("protocol"))
                .smimeMicalg(type.getParameter("micalg"))
                .originalProtectedMessage(originalBytes(message, allowSerialization))
                .build();
    }

    private static byte @Nullable [] originalBytes(final MimeMessage message, final boolean allowSerialization) {
        if (message instanceof FinalizedMimeMessage) {
            return ((FinalizedMimeMessage) message).getSerializedBytes();
        }
        return allowSerialization ? mimeMessageToEMLByteArray(message) : null;
    }

    static OriginalSmimeDetails fromOutlook(final OutlookMessage message) {
        final OutlookSmime smime = message.getSmimeMime();
        if (smime instanceof OutlookSmime.OutlookSmimeApplicationSmime) {
            final OutlookSmime.OutlookSmimeApplicationSmime type = (OutlookSmime.OutlookSmimeApplicationSmime) smime;
            return Details.builder()
                    .smimeMode(mode(type.getSmimeMime(), type.getSmimeType(), null))
                    .smimeMime(type.getSmimeMime())
                    .smimeType(type.getSmimeType())
                    .smimeName(type.getSmimeName())
                    .build();
        }
        if (smime instanceof OutlookSmime.OutlookSmimeMultipartSigned) {
            final OutlookSmime.OutlookSmimeMultipartSigned type = (OutlookSmime.OutlookSmimeMultipartSigned) smime;
            if (!isPotentialSmime(type.getSmimeMime(), type.getSmimeProtocol())) {
                return new PlainSmimeDetails();
            }
            return Details.builder()
                    .smimeMode(mode(type.getSmimeMime(), null, type.getSmimeProtocol()))
                    .smimeMime(type.getSmimeMime())
                    .smimeProtocol(type.getSmimeProtocol())
                    .smimeMicalg(type.getSmimeMicalg())
                    .build();
        }
        return smime != null ? Details.builder().smimeMode(SmimeMode.UNKNOWN).build() : new PlainSmimeDetails();
    }

    private static boolean isPotentialSmime(@Nullable final String mime, @Nullable final String protocol) {
        return isPkcs7Mime(mime) || "application/pkcs7-signature".equalsIgnoreCase(mime)
                || "application/x-pkcs7-signature".equalsIgnoreCase(mime)
                || "multipart/signed".equalsIgnoreCase(mime) && !"application/pgp-signature".equalsIgnoreCase(protocol);
    }

    private static SmimeMode mode(@Nullable final String mime, @Nullable final String type, @Nullable final String protocol) {
        if (isPkcs7Mime(mime)) {
            if ("enveloped-data".equalsIgnoreCase(type)) return SmimeMode.ENCRYPTED;
            if ("signed-data".equalsIgnoreCase(type)) return SmimeMode.SIGNED;
        }
        if ("multipart/signed".equalsIgnoreCase(mime) && (protocol == null
                || "application/pkcs7-signature".equalsIgnoreCase(protocol)
                || "application/x-pkcs7-signature".equalsIgnoreCase(protocol))) {
            return SmimeMode.SIGNED;
        }
        return SmimeMode.UNKNOWN;
    }

    private static boolean isPkcs7Mime(@Nullable final String mime) {
        return "application/pkcs7-mime".equalsIgnoreCase(mime) || "application/x-pkcs7-mime".equalsIgnoreCase(mime);
    }

    @Value
    @Builder
    private static class Details implements OriginalSmimeDetails {
        private static final long serialVersionUID = 1L;

        SmimeMode smimeMode;
        @Nullable String smimeMime;
        @Nullable String smimeType;
        @Nullable String smimeName;
        @Nullable String smimeProtocol;
        @Nullable String smimeMicalg;
        @ToString.Exclude byte @Nullable [] originalProtectedMessage;

        @Override public @Nullable String getSmimeSignedBy() { return null; }
        @Override public @Nullable Boolean getSmimeSignatureValid() { return null; }
        @Override public VerificationStatus getVerificationStatus() { return VerificationStatus.UNKNOWN; }
        @Override public DecryptionStatus getDecryptionStatus() { return DecryptionStatus.UNKNOWN; }

        @Override
        public byte @Nullable [] getOriginalProtectedMessage() {
            return originalProtectedMessage == null ? null : originalProtectedMessage.clone();
        }

        private Object readResolve() {
            return Details.builder().smimeMode(smimeMode).smimeMime(smimeMime).smimeType(smimeType).smimeName(smimeName)
                    .smimeProtocol(smimeProtocol).smimeMicalg(smimeMicalg)
                    .originalProtectedMessage(getOriginalProtectedMessage()).build();
        }
    }
}
