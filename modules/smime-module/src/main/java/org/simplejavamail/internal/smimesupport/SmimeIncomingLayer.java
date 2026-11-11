package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.asn1.ASN1ParsingException;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSRuntimeException;
import org.bouncycastle.cms.CMSSignerDigestMismatchException;
import org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator;
import org.bouncycastle.cms.RecipientInformation;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId;
import org.bouncycastle.mail.smime.SMIMEEnveloped;
import org.bouncycastle.mail.smime.SMIMEException;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.mail.smime.SMIMEUtil;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DecryptionInspection;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.SignatureObservationImpl;
import org.simplejavamail.internal.email.SigningKeyFingerprint;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.internal.protection.ProtectionInputReadException;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.internal.util.InterruptedIoGuard;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Reads one protection envelope. No logical-role selection, recursion, key cache or retained parser is owned here. */
final class SmimeIncomingLayer {
    private final Function<Pkcs12Config, SmimeKey> keyLoader;

    SmimeIncomingLayer(final Function<Pkcs12Config, SmimeKey> keyLoader) {
        this.keyLoader = keyLoader;
    }

    ProtectionLayerResult open(final Session session, final MimeMessage original,
            @Nullable final Pkcs12Config keys) {
        final ContentType type;
        try {
            type = new ContentType(original.getContentType());
        } catch (MessagingException declarationFailure) {
            InterruptedIoGuard.rethrowIfInterrupted(declarationFailure);
            return result(original, false).inspectionComplete(false).build();
        }
        if (!SmimeRecognitionUtil.isSmimeContentType(type)) {
            return result(original, false).inspectionComplete(true).build();
        }
        final MimeBodyPart protectedEntity;
        final Kind kind;
        try {
            protectedEntity = contentEntity(original);
            kind = kind(type, protectedEntity);
        } catch (MessagingException | IOException | ASN1ParsingException inputFailure) {
            InterruptedIoGuard.rethrowIfInterrupted(inputFailure);
            return acquisition(result(original, true).signatureEnvelope(declaresSignedContent(type)).inspectionComplete(false)
                    .failureReason("The S/MIME envelope structure could not be read: " + inputFailure.getClass().getSimpleName())
                    .processingFailure(inputFailure), inputFailure).build();
        }
        if (kind == Kind.UNKNOWN) {
            return result(original, true).inspectionComplete(false)
                    .failureReason("The S/MIME content does not establish a supported signed or encrypted envelope").build();
        }
        final boolean signed = kind == Kind.SIGNED;
        // Configuration failure is operation-wide and must never enter input-content recovery.
        final SmimeKey key = !signed && keys != null ? keyLoader.apply(keys) : null;
        try {
            return signed ? openSigned(session, original, protectedEntity)
                    : openEncrypted(session, original, protectedEntity, key);
        } catch (MessagingException | IOException | CMSException | SMIMEException | ASN1ParsingException | CMSRuntimeException inputFailure) {
            InterruptedIoGuard.rethrowIfInterrupted(inputFailure);
            return failed(original, signed, inputFailure);
        }
    }

    private static ProtectionLayerResult openSigned(final Session session, final MimeMessage original,
            final MimeBodyPart entity) throws MessagingException, IOException, CMSException, SMIMEException {
        final SMIMESigned signed = entity.isMimeType("multipart/signed")
                ? new SMIMESigned((MimeMultipart) entity.getContent()) : new SMIMESigned(entity);
        final List<SignatureObservation> signatures = inspectSignatures(signed);
        final MimeBodyPart clearEntity = signed.getContent();
        if (clearEntity == null) {
            return result(original, true).signatureEnvelope(true).signatures(signatures).inspectionComplete(false)
                    .failureReason("The S/MIME signed envelope contains no readable MIME content").build();
        }
        boolean authored = false;
        try {
            authored = clearEntity.isMimeType("message/rfc822");
            final ProtectionLayerResult.ProtectionLayerResultBuilder result = opened(session, original, clearEntity, authored)
                    .signatureEnvelope(true).signatures(signatures).inspectionComplete(!signatures.isEmpty());
            if (signatures.isEmpty()) result.failureReason("The S/MIME signed envelope contains no readable signer information");
            return result.build();
        } catch (MessagingException | IOException interpretationFailure) {
            InterruptedIoGuard.rethrowIfInterrupted(interpretationFailure);
            return acquisition(result(original, true).signatureEnvelope(true).signatures(signatures).inspectionComplete(false)
                    .authoredHeaders(authored)
                    .failureReason("The S/MIME signed MIME content could not be interpreted").processingFailure(interpretationFailure), interpretationFailure).build();
        }
    }

    private static ProtectionLayerResult openEncrypted(final Session session, final MimeMessage original,
            final MimeBodyPart entity, @Nullable final SmimeKey key)
            throws MessagingException, IOException, CMSException, SMIMEException {
        if (key == null) {
            return result(original, true).inspectionComplete(false)
                    .decryptions(Collections.singletonList(decryption(DecryptionStatus.UNAVAILABLE, null,
                            "No S/MIME decryption key was supplied", ProtectionCoverage.UNKNOWN))).build();
        }
        final SMIMEEnveloped encrypted = new SMIMEEnveloped(entity);
        final RecipientInformation recipient = encrypted.getRecipientInfos()
                .get(new JceKeyTransRecipientId(key.getCertificate()));
        if (recipient == null) {
            return result(original, true).inspectionComplete(false)
                    .decryptions(Collections.singletonList(decryption(DecryptionStatus.UNAVAILABLE,
                            encrypted.getEncryptionAlgOID(), "The configured key is not an S/MIME recipient of this content",
                            ProtectionCoverage.UNKNOWN))).build();
        }
        final JceKeyTransEnvelopedRecipient recipientKey = new JceKeyTransEnvelopedRecipient(key.getPrivateKey());
        recipientKey.setProvider(SmimeCryptoSupport.BOUNCY_CASTLE);
        final byte[] clearBytes = recipient.getContent(recipientKey);
        boolean authored = false;
        try {
            final MimeBodyPart clearEntity = SMIMEUtil.toMimeBodyPart(clearBytes);
            authored = clearEntity.isMimeType("message/rfc822");
            return opened(session, original, clearEntity, authored).inspectionComplete(true)
                    .decryptions(Collections.singletonList(decryption(DecryptionStatus.DECRYPTED,
                            encrypted.getEncryptionAlgOID(), null, ProtectionCoverage.FULL))).build();
        } catch (MessagingException | IOException | SMIMEException | ASN1ParsingException unusableContent) {
            InterruptedIoGuard.rethrowIfInterrupted(unusableContent);
            final String reason = "S/MIME decryption completed, but its MIME content could not be interpreted";
            return acquisition(result(original, true).inspectionComplete(false).failureReason(reason).processingFailure(unusableContent)
                    .authoredHeaders(authored)
                    .decryptions(Collections.singletonList(decryption(DecryptionStatus.DECRYPTED,
                            encrypted.getEncryptionAlgOID(), reason, ProtectionCoverage.UNKNOWN))), unusableContent).build();
        }
    }

    private static List<SignatureObservation> inspectSignatures(final SMIMESigned signed) {
        final List<SignatureObservation> results = new ArrayList<>();
        for (SignerInformation signer : signed.getSignerInfos().getSigners()) {
            final SignatureObservationImpl.SignatureObservationImplBuilder result = SignatureObservationImpl.builder()
                    .family(ProtectionFamily.SMIME).signatureAlgorithm(signer.getEncryptionAlgOID())
                    .hashAlgorithm(signer.getDigestAlgOID()).signerKeyId(signerIdentifier(signer.getSID()));
            try {
                final Collection<X509CertificateHolder> certificates = signed.getCertificates().getMatches(signer.getSID());
                if (certificates.isEmpty()) {
                    results.add(result.status(SignatureVerification.UNVERIFIED)
                            .failureReason("The S/MIME signer certificate was not available").build());
                    continue;
                }
                final X509Certificate certificate = new JcaX509CertificateConverter()
                        .setProvider(SmimeCryptoSupport.BOUNCY_CASTLE).getCertificate(certificates.iterator().next());
                result.signerName(certificate.getSubjectX500Principal().getName());
                final PublicKey publicKey = certificate.getPublicKey();
                if (publicKey == null) {
                    results.add(result.status(SignatureVerification.UNVERIFIED)
                            .failureReason("The S/MIME signer public key is not supported").build());
                    continue;
                }
                result.signingKeyFingerprint(SigningKeyFingerprint.forSmime(publicKey));
                if (!supportedSignatureAlgorithm(signer)) {
                    results.add(result.status(SignatureVerification.UNVERIFIED)
                            .failureReason("The S/MIME signature algorithm is not supported").build());
                    continue;
                }
                // Public-key verification deliberately does not add certificate-time/path/revocation approval policy.
                final boolean valid = signer.verify(new JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider(SmimeCryptoSupport.BOUNCY_CASTLE).build(publicKey));
                results.add(result.status(valid ? SignatureVerification.VERIFIED : SignatureVerification.INVALID)
                        .failureReason(valid ? null : "The S/MIME signature does not match its signed content").build());
            } catch (CMSSignerDigestMismatchException mismatch) {
                results.add(result.status(SignatureVerification.INVALID)
                        .failureReason("The S/MIME signature digest does not match its signed content").build());
            } catch (CMSException | CertificateException | OperatorCreationException | ASN1ParsingException | CMSRuntimeException unavailableVerification) {
                InterruptedIoGuard.rethrowIfInterrupted(unavailableVerification);
                results.add(result.status(SignatureVerification.UNVERIFIED)
                        .failureReason("The S/MIME signature could not be verified: "
                                + unavailableVerification.getClass().getSimpleName()).build());
            }
        }
        return results;
    }

    private static String signerIdentifier(final SignerId signer) {
        final byte[] subjectKeyIdentifier = signer.getSubjectKeyIdentifier();
        if (subjectKeyIdentifier != null) return org.bouncycastle.util.encoders.Hex.toHexString(subjectKeyIdentifier).toUpperCase(Locale.ROOT);
        return signer.getIssuer() == null || signer.getSerialNumber() == null ? null
                : signer.getIssuer() + ";serial=" + signer.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
    }

    private static boolean supportedSignatureAlgorithm(final SignerInformation signer) {
        final org.bouncycastle.asn1.cms.SignerInfo information = signer.toASN1Structure();
        final String name = new DefaultCMSSignatureAlgorithmNameGenerator()
                .getSignatureName(information.getDigestAlgorithm(), information.getDigestEncryptionAlgorithm());
        try {
            new DefaultSignatureAlgorithmIdentifierFinder().find(name);
            return true;
        } catch (IllegalArgumentException unsupportedAlgorithm) {
            // The native algorithm finder uses this type for an unrecognized input algorithm, before any verifier runs.
            return false;
        }
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder opened(final Session session,
            final MimeMessage original, final MimeBodyPart clearEntity, final boolean authored) throws MessagingException, IOException {
        final MimeMessage effective;
        if (authored) {
            final byte[] messageBytes = ProtectionInputReadException.acquire(clearEntity::getInputStream);
            effective = FinalizedMimeMessage.fromMessageBytes(session, messageBytes, FinalizedMimeMessage.ProtectionState.NONE);
        } else {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final Enumeration<Header> headers = original.getAllHeaders();
            while (headers.hasMoreElements()) {
                final Header header = headers.nextElement();
                if (!header.getName().toLowerCase(Locale.ROOT).startsWith("content-")) {
                    bytes.write((header.getName() + ": " + header.getValue() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                }
            }
            clearEntity.writeTo(bytes);
            effective = FinalizedMimeMessage.fromMessageBytes(session, bytes.toByteArray(),
                    FinalizedMimeMessage.ProtectionState.NONE);
        }
        return result(effective, true).opened(true).openedEntity(clearEntity).authoredHeaders(authored);
    }

    private static ProtectionLayerResult failed(final MimeMessage original, final boolean signed, final Exception failure) {
        final String reason = "Unable to open S/MIME content: " + failure.getClass().getSimpleName();
        final ProtectionLayerResult.ProtectionLayerResultBuilder result = result(original, true)
                .signatureEnvelope(signed).inspectionComplete(false).failureReason(reason).processingFailure(failure);
        if (!signed) {
            result.decryptions(Collections.singletonList(decryption(DecryptionStatus.FAILED, null, reason, ProtectionCoverage.UNKNOWN)));
        }
        return result.build();
    }

    private static DecryptionInspection decryption(final DecryptionStatus status, @Nullable final String algorithm,
            @Nullable final String reason, final ProtectionCoverage coverage) {
        return DecryptionInspectionImpl.builder().family(ProtectionFamily.SMIME).status(status).coverage(coverage)
                .encryptionAlgorithm(algorithm).failureReason(reason).build();
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder result(final MimeMessage message, final boolean recognized) {
        return ProtectionLayerResult.builder().recognized(recognized).effectiveMimeMessage(message);
    }

    private static ProtectionLayerResult.ProtectionLayerResultBuilder acquisition(
            final ProtectionLayerResult.ProtectionLayerResultBuilder result, final Throwable failure) {
        if (failure instanceof ProtectionInputReadException) {
            final ProtectionInputReadException input = (ProtectionInputReadException) failure;
            result.inputReadFailure(input.getReadFailure()).inputCloseFailure(input.getCloseFailure());
        }
        return result;
    }

    private enum Kind { SIGNED, ENCRYPTED, UNKNOWN }

    private static boolean declaresSignedContent(final ContentType type) {
        return type.match("multipart/signed") || "signed-data".equalsIgnoreCase(type.getParameter("smime-type"));
    }

    private static Kind kind(final ContentType type, final MimeBodyPart entity) throws MessagingException, IOException {
        if (declaresSignedContent(type)) return Kind.SIGNED;
        if ("enveloped-data".equalsIgnoreCase(type.getParameter("smime-type"))) return Kind.ENCRYPTED;
        final ContentInfo content;
        try (java.io.InputStream input = entity.getInputStream()) {
            try {
                content = ContentInfo.getInstance(ASN1Primitive.fromByteArray(input.readAllBytes()));
            } catch (IllegalArgumentException malformedContentInfo) {
                throw new IOException("The S/MIME CMS content information is malformed", malformedContentInfo);
            }
        }
        if (CMSObjectIdentifiers.signedData.equals(content.getContentType())) return Kind.SIGNED;
        if (CMSObjectIdentifiers.envelopedData.equals(content.getContentType())) return Kind.ENCRYPTED;
        return Kind.UNKNOWN;
    }

    private static MimeBodyPart contentEntity(final MimeMessage message) throws MessagingException, IOException {
        final InternetHeaders headers = contentHeaders(message);
        final String encoding = message.getEncoding();
        final boolean identityEncoding = encoding == null || "7bit".equalsIgnoreCase(encoding)
                || "8bit".equalsIgnoreCase(encoding) || "binary".equalsIgnoreCase(encoding);
        if (!(message instanceof FinalizedMimeMessage) || !identityEncoding) {
            final byte[] decoded = ProtectionInputReadException.acquire(message::getInputStream);
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (java.io.OutputStream encoded = encoding == null ? bytes : MimeUtility.encode(bytes, encoding)) {
                encoded.write(decoded);
            }
            return new MimeBodyPart(headers, bytes.toByteArray());
        }
        final byte[] messageBytes = ((FinalizedMimeMessage) message).getSerializedBytes();
        int body = -1;
        for (int index = 0; index < messageBytes.length && body < 0; index++) {
            final int first = lineEndingLength(messageBytes, index);
            if (first > 0) {
                final int second = lineEndingLength(messageBytes, index + first);
                if (second > 0) body = index + first + second;
            }
        }
        if (body < 0) throw new MessagingException("The S/MIME message has no complete MIME header section");
        return new MimeBodyPart(headers, java.util.Arrays.copyOfRange(messageBytes, body, messageBytes.length));
    }

    private static InternetHeaders contentHeaders(final MimeMessage message) throws MessagingException {
        final InternetHeaders headers = new InternetHeaders();
        final Enumeration<Header> source = message.getAllHeaders();
        while (source.hasMoreElements()) {
            final Header header = source.nextElement();
            if (header.getName().toLowerCase(Locale.ROOT).startsWith("content-")) headers.addHeader(header.getName(), header.getValue());
        }
        return headers;
    }

    private static int lineEndingLength(final byte[] bytes, final int offset) {
        if (offset >= bytes.length) return 0;
        if (bytes[offset] == '\n') return 1;
        return bytes[offset] == '\r' && offset + 1 < bytes.length && bytes[offset + 1] == '\n' ? 2 : 0;
    }
}
