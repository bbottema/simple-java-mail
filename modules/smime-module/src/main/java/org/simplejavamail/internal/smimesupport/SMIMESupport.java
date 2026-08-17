package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEEnvelopedGenerator;
import org.bouncycastle.mail.smime.SMIMEException;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaAlgorithmParametersConverter;
import org.bouncycastle.util.Store;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.general.MessageHeader;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeMultipartSigned;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;
import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.SmimePreprocessingResult;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.smimesupport.builder.SmimeParseResultBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.internal.smimesupport.model.SmimeDetailsImpl;
import org.simplejavamail.internal.util.MessageIdFixingMimeMessage;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.utils.mail.smime.KeyEncapsulationAlgorithm;
import org.simplejavamail.utils.mail.smime.SmimeKey;
import org.simplejavamail.utils.mail.smime.SmimeKeyStore;
import org.simplejavamail.utils.mail.smime.SmimeState;
import org.simplejavamail.utils.mail.smime.SmimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_ENCRYPTING_SMIME_FOR_RECIPIENTS;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DETERMINING_SMIME_SIGNER;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_OBTAINING_SMIME_KEY;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_READING_SMIME_CONTENT_TYPE;
import static org.simplejavamail.internal.smimesupport.SmimeException.MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;


/**
 * This class only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class SMIMESupport implements SMIMEModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(SMIMESupport.class);
	private static final List<String> SMIME_MIMETYPES = asList("application/pkcs7-mime", "application/x-pkcs7-mime", "multipart/signed");
	private static final Map<Pkcs12Config, SmimeKey> SIMPLE_SMIMESTORE_CACHE = new HashMap<>();

	static {
		Security.addProvider(new BouncyCastleProvider());
	}

	/** Inspect and unwrap top-level S/MIME before the ordinary MIME parser traverses the entity. */
	@NotNull
	@Override
	public SmimePreprocessingResult processIncoming(@NotNull final Session session,
			@NotNull final MimeMessage originalMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		byte[] originalBytes = null;
		try {
			final ContentType contentType = new ContentType(originalMessage.getContentType());
			if (!SmimeRecognitionUtil.isSmimeContentType(contentType)) {
				return unrecognized(originalMessage);
			}
			originalBytes = serializeMessage(originalMessage);
			final OriginalSmimeDetailsImpl baseDetails = details(contentType, originalBytes);
			final SmimeState state = determineIncomingState(originalMessage, baseDetails);
			if (state == SmimeState.ENCRYPTED) {
				return processIncomingEncrypted(session, originalMessage, originalBytes, baseDetails, pkcs12Config);
			}
			if (state == SmimeState.SIGNED || state == SmimeState.PROBABLY_SIGNED || state == SmimeState.SIGNED_ENVELOPED) {
				final boolean opaqueSigned = !originalMessage.isMimeType("multipart/signed");
				return processIncomingSigned(session, originalMessage, originalMessage, originalBytes, baseDetails,
						opaqueSigned ? protectedTopLevelAttachment(originalMessage) : Collections.emptyList(),
						false, opaqueSigned);
			}
			return unrecognized(originalMessage);
		} catch (Exception e) {
			LOGGER.debug("Unable to process S/MIME message before MIME parsing", e);
			final boolean encrypted = isEncrypted(originalMessage);
			final OriginalSmimeDetailsImpl failed = OriginalSmimeDetailsImpl.builder()
					.smimeMode(encrypted ? SmimeMode.ENCRYPTED : SmimeMode.SIGNED)
					.verificationStatus(encrypted
							? OriginalSmimeDetails.VerificationStatus.NOT_SIGNED
							: OriginalSmimeDetails.VerificationStatus.ERROR)
					.decryptionStatus(encrypted
							? OriginalSmimeDetails.DecryptionStatus.FAILED
							: OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED)
					.failureReason("Unable to process S/MIME message before MIME parsing: "
							+ e.getClass().getSimpleName() + ": " + e.getMessage())
					.originalProtectedMessage(originalBytes)
					.build();
			return new SmimePreprocessingResult(true, originalMessage, failed,
					protectedTopLevelAttachment(originalMessage), Collections.emptyList());
		}
	}

	@NotNull
	private SmimePreprocessingResult processIncomingEncrypted(@NotNull final Session session,
			@NotNull final MimeMessage originalMessage,
			final byte @NotNull [] originalBytes,
			@NotNull final OriginalSmimeDetailsImpl baseDetails,
			@Nullable final Pkcs12Config pkcs12Config) throws Exception {
		final List<AttachmentResource> protectedAttachments = protectedTopLevelAttachment(originalMessage);
		if (pkcs12Config == null) {
			final OriginalSmimeDetailsImpl details = copyDetails(baseDetails)
					.decryptionStatus(OriginalSmimeDetails.DecryptionStatus.KEY_MISSING)
					.failureReason("No PKCS12 decryption key was provided for the S/MIME message")
					.build();
			return new SmimePreprocessingResult(true, originalMessage, details,
					protectedAttachments, Collections.emptyList());
		}

		final MimeBodyPart encryptedPart = messageBodyPart(originalMessage, originalBytes);
		final MimeBodyPart decryptedPart = SmimeUtil.decrypt(encryptedPart,
				retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config));
		final SmimeState nestedState = SmimeUtil.getStatus(decryptedPart);
		if (nestedState == SmimeState.SIGNED || nestedState == SmimeState.PROBABLY_SIGNED
				|| nestedState == SmimeState.SIGNED_ENVELOPED) {
			protectedAttachments.addAll(detachedSignatureAttachments(decryptedPart));
			return processIncomingSigned(session, originalMessage, decryptedPart, originalBytes, baseDetails,
					protectedAttachments, true, true);
		}

		final MimeMessage effective = effectiveMessage(session, originalMessage, decryptedPart);
		final List<AttachmentResource> decryptedArtifacts = Collections.singletonList(asMessageAttachment(decryptedPart, effective));
		final OriginalSmimeDetailsImpl details = copyDetails(baseDetails)
				.decryptionStatus(OriginalSmimeDetails.DecryptionStatus.DECRYPTED)
				.build();
		return new SmimePreprocessingResult(true, effective, details, protectedAttachments, decryptedArtifacts);
	}

	@NotNull
	private SmimePreprocessingResult processIncomingSigned(@NotNull final Session session,
			@NotNull final MimeMessage originalMessage,
			@NotNull final MimePart signedPart,
			final byte @NotNull [] originalBytes,
			@NotNull final OriginalSmimeDetailsImpl baseDetails,
			@NotNull final List<AttachmentResource> existingProtectedAttachments,
			final boolean wasEncrypted,
			final boolean exposeEffectiveMessageArtifact) throws Exception {
		final boolean valid = verifyIncomingSignature(signedPart);
		final String signedBy = getSignedByAddress(signedPart);
		final ContentType signedContentType = new ContentType(signedPart.getContentType());
		final MimeBodyPart clearPart = SmimeUtil.getSignedContent(signedPart);
		final MimeMessage effective = effectiveMessage(session, originalMessage, clearPart);
		final List<AttachmentResource> protectedAttachments = new ArrayList<>(existingProtectedAttachments);
		final List<AttachmentResource> signatures = detachedSignatureAttachments(signedPart);
		for (AttachmentResource signature : signatures) {
			if (!containsAttachment(protectedAttachments, signature.getName())) {
				protectedAttachments.add(signature);
			}
		}
		final List<AttachmentResource> decryptedArtifacts = new ArrayList<>(signatures);
		if (exposeEffectiveMessageArtifact) {
			decryptedArtifacts.add(asMessageAttachment(clearPart, effective));
		}
		final OriginalSmimeDetailsImpl details = copyDetails(baseDetails)
				.smimeMode(wasEncrypted ? SmimeMode.SIGNED_ENCRYPTED : SmimeMode.SIGNED)
				.smimeSignedBy(wasEncrypted ? null : signedBy)
				.smimeSignatureValid(wasEncrypted ? null : valid)
				.verificationStatus(valid
						? OriginalSmimeDetails.VerificationStatus.VALID
						: OriginalSmimeDetails.VerificationStatus.INVALID)
				.decryptionStatus(wasEncrypted
						? OriginalSmimeDetails.DecryptionStatus.DECRYPTED
						: OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED)
				.failureReason(valid ? null : "S/MIME signature does not match the signed MIME entity")
				.build();
		final OriginalSmimeDetailsImpl nestedSignedDetails = OriginalSmimeDetailsImpl.builder()
				.smimeMode(SmimeMode.SIGNED)
				.smimeMime(signedContentType.getBaseType())
				.smimeType(signedContentType.getParameter("smime-type"))
				.smimeName(signedContentType.getParameter("name"))
				.smimeProtocol(signedContentType.getParameter("protocol"))
				.smimeMicalg(signedContentType.getParameter("micalg"))
				.smimeSignedBy(signedBy)
				.smimeSignatureValid(valid)
				.verificationStatus(valid
						? OriginalSmimeDetails.VerificationStatus.VALID
						: OriginalSmimeDetails.VerificationStatus.INVALID)
				.failureReason(valid ? null : "S/MIME signature does not match the signed MIME entity")
				.build();
		return new SmimePreprocessingResult(true, effective, details, protectedAttachments, decryptedArtifacts,
				exposeEffectiveMessageArtifact ? nestedSignedDetails : null);
	}

	private static boolean verifyIncomingSignature(@NotNull final MimePart signedPart) {
		try {
			return hasSignerInformationStatic(signedPart) && SmimeUtil.checkSignature(signedPart);
		} catch (org.simplejavamail.utils.mail.smime.SmimeException e) {
			LOGGER.warn("Content is S/MIME signed, but signature verification failed. Reason: {}", e.getMessage());
			LOGGER.debug("S/MIME pre-parse signature verification failure: {}", e.getMessage());
			return false;
		}
	}

	private static boolean hasSignerInformationStatic(@NotNull final MimePart mimePart) {
		try {
			return !determineSMIMESigned(mimePart).getSignerInfos().getSigners().isEmpty();
		} catch (SmimeException e) {
			return false;
		}
	}

	private static SmimeState determineIncomingState(@NotNull final MimeMessage message,
			@NotNull final OriginalSmimeDetails details) {
		final SmimeState detected = SmimeUtil.getStatus(message);
		if (detected == SmimeState.ENCRYPTED && "signed-data".equals(details.getSmimeType())) {
			return SmimeState.SIGNED;
		}
		return detected;
	}

	@NotNull
	private static OriginalSmimeDetailsImpl details(@NotNull final ContentType contentType,
			final byte @NotNull [] originalBytes) {
		return OriginalSmimeDetailsImpl.builder()
				.smimeMime(contentType.getBaseType())
				.smimeType(contentType.getParameter("smime-type"))
				.smimeName(contentType.getParameter("name"))
				.smimeProtocol(contentType.getParameter("protocol"))
				.smimeMicalg(contentType.getParameter("micalg"))
				.originalProtectedMessage(originalBytes)
				.build();
	}

	@NotNull
	private static OriginalSmimeDetailsImpl.OriginalSmimeDetailsBuilder copyDetails(
			@NotNull final OriginalSmimeDetails details) {
		return OriginalSmimeDetailsImpl.builder()
				.smimeMode(details.getSmimeMode())
				.smimeMime(details.getSmimeMime())
				.smimeType(details.getSmimeType())
				.smimeName(details.getSmimeName())
				.smimeProtocol(details.getSmimeProtocol())
				.smimeMicalg(details.getSmimeMicalg())
				.smimeSignedBy(details.getSmimeSignedBy())
				.smimeSignatureValid(details.getSmimeSignatureValid())
				.verificationStatus(details.getVerificationStatus())
				.decryptionStatus(details.getDecryptionStatus())
				.failureReason(details.getFailureReason())
				.originalProtectedMessage(details.getOriginalProtectedMessage());
	}

	@NotNull
	private static SmimePreprocessingResult unrecognized(@NotNull final MimeMessage message) {
		return new SmimePreprocessingResult(false, message, OriginalSmimeDetailsImpl.builder().build(),
				Collections.emptyList(), Collections.emptyList());
	}

	@NotNull
	private static MimeBodyPart messageBodyPart(@NotNull final MimeMessage message,
			final byte @NotNull [] serialized) throws MessagingException {
		final InternetHeaders contentHeaders = new InternetHeaders();
		final Enumeration<Header> headers = message.getAllHeaders();
		while (headers.hasMoreElements()) {
			final Header header = headers.nextElement();
			if (header.getName().toLowerCase(java.util.Locale.ROOT).startsWith("content-")) {
				contentHeaders.addHeader(header.getName(), header.getValue());
			}
		}
		final int bodyOffset = bodyOffset(serialized);
		return new MimeBodyPart(contentHeaders, java.util.Arrays.copyOfRange(serialized, bodyOffset, serialized.length));
	}

	@NotNull
	private static MimeMessage effectiveMessage(@NotNull final Session session,
			@NotNull final MimeMessage original,
			@NotNull final MimeBodyPart clearPart) throws MessagingException, IOException {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		final Enumeration<String> headerLines = original.getAllHeaderLines();
		boolean skipContinuation = false;
		while (headerLines.hasMoreElements()) {
			final String line = headerLines.nextElement();
			final boolean continuation = !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
			if (!continuation) {
				final int colon = line.indexOf(':');
				final String name = colon < 0 ? line : line.substring(0, colon);
				skipContinuation = name.toLowerCase(java.util.Locale.ROOT).startsWith("content-");
			}
			if (!skipContinuation) {
				output.write(line.getBytes(StandardCharsets.ISO_8859_1));
				output.write('\r');
				output.write('\n');
			}
		}
		clearPart.writeTo(output);
		return FinalizedMimeMessage.fromMessageBytes(session, output.toByteArray(),
				FinalizedMimeMessage.ProtectionState.NONE);
	}

	@NotNull
	private static List<AttachmentResource> protectedTopLevelAttachment(@NotNull final MimeMessage message) {
		try {
			if (message.isMimeType("multipart/signed")) {
				return detachedSignatureAttachments(message);
			}
			final String contentType = message.getContentType();
			final ContentType parsed = new ContentType(contentType);
			final String name = ofNullable(parsed.getParameter("name")).orElse("smime.p7m");
			final ByteArrayDataSource dataSource = new ByteArrayDataSource(
					readAll(message.getInputStream()), parsed.getBaseType());
			dataSource.setName(name);
			return new ArrayList<>(Collections.singletonList(new AttachmentResource(name, dataSource)));
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	@NotNull
	private static List<AttachmentResource> detachedSignatureAttachments(@NotNull final MimePart signedPart) {
		try {
			if (!signedPart.isMimeType("multipart/signed")) {
				return new ArrayList<>();
			}
			final MimeMultipart multipart = (MimeMultipart) signedPart.getContent();
			if (multipart.getCount() < 2) {
				return new ArrayList<>();
			}
			final MimeBodyPart signature = (MimeBodyPart) multipart.getBodyPart(1);
			final String name = ofNullable(signature.getFileName()).orElse("smime.p7s");
			return new ArrayList<>(Collections.singletonList(new AttachmentResource(name,
					new ByteArrayDataSource(readAll(signature.getInputStream()), signature.getContentType()))));
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	@NotNull
	private static AttachmentResource asMessageAttachment(@NotNull final MimeMessage message)
			throws MessagingException, IOException {
		return new AttachmentResource("signed-email.eml",
				new ByteArrayDataSource(serializeMessage(message), "message/rfc822"));
	}

	@NotNull
	private AttachmentResource asMessageAttachment(@NotNull final MimeBodyPart clearPart,
			@NotNull final MimeMessage effectiveMessage) throws MessagingException, IOException {
		final AttachmentResource legacyView = handleLiberatedContent(clearPart.getContent());
		return legacyView != null ? legacyView : asMessageAttachment(effectiveMessage);
	}

	private static boolean containsAttachment(@NotNull final List<AttachmentResource> attachments,
			@Nullable final String name) {
		for (AttachmentResource attachment : attachments) {
			if (java.util.Objects.equals(attachment.getName(), name)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] serializeMessage(@NotNull final MimeMessage message) throws MessagingException, IOException {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		message.writeTo(output);
		return output.toByteArray();
	}

	private static byte[] readAll(@NotNull final java.io.InputStream input) throws IOException {
		try (java.io.InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			final byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = closeable.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static int bodyOffset(final byte[] serialized) throws MessagingException {
		for (int i = 0; i <= serialized.length - 4; i++) {
			if (serialized[i] == '\r' && serialized[i + 1] == '\n'
					&& serialized[i + 2] == '\r' && serialized[i + 3] == '\n') {
				return i + 4;
			}
		}
		for (int i = 0; i <= serialized.length - 2; i++) {
			if (serialized[i] == '\n' && serialized[i + 1] == '\n') {
				return i + 2;
			}
		}
		throw new MessagingException("S/MIME message has no header/body separator");
	}

	private static boolean isEncrypted(@NotNull final MimeMessage message) {
		try {
			final ContentType type = new ContentType(message.getContentType());
			return ("application/pkcs7-mime".equals(type.getBaseType())
					|| "application/x-pkcs7-mime".equals(type.getBaseType()))
					&& !"signed-data".equals(type.getParameter("smime-type"));
		} catch (MessagingException e) {
			return false;
		}
	}

	public SmimeParseResultBuilder decryptAttachments(@NotNull final List<AttachmentResource> attachments, @NotNull final OutlookMessage outlookMessage,
			@Nullable final Pkcs12Config pkcs12Config) {
		final SmimeParseResultBuilder smimeBuilder = new SmimeParseResultBuilder();

		if (outlookMessage.getSmimeMime() instanceof OutlookSmimeApplicationSmime) {
			final OutlookSmimeApplicationSmime s = (OutlookSmimeApplicationSmime) outlookMessage.getSmimeMime();
			smimeBuilder.getOriginalSmimeDetails().completeWith(OriginalSmimeDetailsImpl.builder()
					.smimeMime(s.getSmimeMime())
					.smimeType(s.getSmimeType())
					.smimeName(s.getSmimeName())
					.build());
		} else if (outlookMessage.getSmimeMime() instanceof OutlookSmimeMultipartSigned) {
			final OutlookSmimeMultipartSigned s = (OutlookSmimeMultipartSigned) outlookMessage.getSmimeMime();
			smimeBuilder.getOriginalSmimeDetails().completeWith(OriginalSmimeDetailsImpl.builder()
					.smimeMime(s.getSmimeMime())
					.smimeProtocol(s.getSmimeProtocol())
					.smimeMicalg(s.getSmimeMicalg())
					.build());
		}

		decryptAttachments(smimeBuilder, attachments, pkcs12Config);

		return smimeBuilder;
	}

	public SmimeParseResultBuilder decryptAttachments(@NotNull final List<AttachmentResource> attachments, @NotNull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		final SmimeParseResultBuilder smimeBuilder = new SmimeParseResultBuilder();

		initSmimeMetadata(smimeBuilder, mimeMessage);
		decryptAttachments(smimeBuilder, attachments, pkcs12Config);
		finalizeSmimeMetadata(smimeBuilder, mimeMessage);

		return smimeBuilder;
	}

	private void initSmimeMetadata(final SmimeParseResultBuilder smimeBuilder, @NotNull final MimeMessage mimeMessage) {
		try {
			if (mimeMessage.getHeader(MessageHeader.CONTENT_TYPE.getName(), null) != null) {
				ContentType ct = new ContentType(mimeMessage.getHeader(MessageHeader.CONTENT_TYPE.getName(), null));
				if (SmimeRecognitionUtil.isSmimeContentType(ct)) {
					smimeBuilder.getOriginalSmimeDetails()
							.completeWith(OriginalSmimeDetailsImpl.builder()
									.smimeMime(ct.getBaseType())
									.smimeType(ct.getParameter("smime-type"))
									.smimeName(ct.getParameter("name"))
									.smimeProtocol(ct.getParameter("protocol"))
									.smimeMicalg(ct.getParameter("micalg"))
									.build());
				}
			}
		} catch (MessagingException e) {
			throw new SmimeException(ERROR_READING_SMIME_CONTENT_TYPE, e);
		}
	}

	private void finalizeSmimeMetadata(final SmimeParseResultBuilder smimeBuilder, @NotNull final MimeMessage mimeMessage) {
		final OriginalSmimeDetailsImpl originalSmimeDetails = smimeBuilder.getOriginalSmimeDetails();

		if (originalSmimeDetails.getSmimeMode() != SmimeMode.PLAIN) {
			LOGGER.debug("checking who signed this message...");
			originalSmimeDetails.completeWithSmimeSignedBy(getSignedByAddress(mimeMessage));
			if (originalSmimeDetails.getSmimeMode() == SmimeMode.SIGNED) {
				originalSmimeDetails.completeWithSmimeSignatureValid(checkSignature(mimeMessage, originalSmimeDetails));
			}
		}
	}

	private boolean checkSignature(@NotNull final MimeMessage mimeMessage, @Nullable final OriginalSmimeDetails messageSmimeDetails) {
		if (messageSmimeDetails != null) {
			LOGGER.debug("verifying signed mimemessage...");
			final boolean validSignature;
			try {
				validSignature = verifyValidSignature(mimeMessage, messageSmimeDetails);
			} catch (org.simplejavamail.utils.mail.smime.SmimeException e) {
				LOGGER.warn("Message contains invalid S/MIME signature! Assume this email has been tampered with. Reason: {}", e.getMessage());
				LOGGER.debug("S/MIME signature verification failure: {}", e.getMessage());
				return false;
			}
			if (!validSignature) {
				LOGGER.warn("Message contains invalid S/MIME signature! Assume this email has been tampered with.");
			}
			return validSignature;
		}
		return false;
	}

	private void decryptAttachments(@NotNull final SmimeParseResultBuilder smimeBuilder, @NotNull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config) {
		LOGGER.debug("checking for S/MIME signed / encrypted attachments...");
		List<AttachmentDecryptionResult> decryptedAttachments = decryptAttachments(attachments, pkcs12Config, smimeBuilder.getOriginalSmimeDetails(),
				smimeBuilder.getOriginalSmimeDetails());
		smimeBuilder.addDecryptedAttachments(decryptedAttachments);

		if (attachments.size() == 1) {
			final AttachmentResource onlyAttachment = attachments.get(0);
			final AttachmentDecryptionResult onlyAttachmentDecrypted = smimeBuilder.getDecryptedAttachmentResults().get(0);
			if (isSmimeAttachment(onlyAttachment) && isMimeMessageAttachment(onlyAttachmentDecrypted.getAttachmentResource())) {
				smimeBuilder.getOriginalSmimeDetails().completeWith(determineSmimeDetails(onlyAttachment));
				smimeBuilder.setSmimeSignedOrEncryptedEmail(onlyAttachmentDecrypted.getAttachmentResource());
			}
		}
	}

	private boolean isMimeMessageAttachment(final AttachmentResource attachment) {
		return attachment.getDataSource().getContentType().equals("message/rfc822");
	}

	@NotNull
	private OriginalSmimeDetailsImpl determineSmimeDetails(final AttachmentResource attachment) {
		LOGGER.debug("Single S/MIME signed / encrypted attachment found; assuming the attachment is the message "
				+ "body, a record of the original S/MIME details will be stored on the Email root...");
		SmimeDetails smimeDetails = getSmimeDetails(attachment);
		return OriginalSmimeDetailsImpl.builder()
				.smimeMime(smimeDetails.getSmimeMime())
				.smimeSignedBy(smimeDetails.getSignedBy())
				.build();
	}

	/**
	 * @see SMIMEModule#decryptAttachments(List, Pkcs12Config, OriginalSmimeDetails)
	 */
	@NotNull
	@Override
	public List<AttachmentDecryptionResult> decryptAttachments(
			@NotNull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config,
			@NotNull final OriginalSmimeDetails messageSmimeDetails) {
		return decryptAttachments(attachments, pkcs12Config, messageSmimeDetails, null);
	}

	@NotNull
	private List<AttachmentDecryptionResult> decryptAttachments(
			@NotNull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config,
			@NotNull final OriginalSmimeDetails messageSmimeDetails,
			@Nullable final OriginalSmimeDetailsImpl smimeDetailsToUpdate) {
		final List<AttachmentDecryptionResult> decryptedAttachments = new ArrayList<>();
		for (final AttachmentResource attachment : attachments) {
			if (isSmimeAttachment(attachment)) {
				try {
					LOGGER.debug("decrypting S/MIME signed attachment '{}'...", attachment.getName());
					decryptedAttachments.add(decryptAndUnsignAttachment(attachment, pkcs12Config, messageSmimeDetails, smimeDetailsToUpdate));
				} catch (Exception e) {
					throw new SmimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
				}
			} else {
				decryptedAttachments.add(new AttachmentDecryptionResultImpl(SmimeMode.PLAIN, attachment));
			}
		}

		return decryptedAttachments;
	}

	/**
	 * @see SMIMEModule#isSmimeAttachment(AttachmentResource)
	 */
	@Override
	public boolean isSmimeAttachment(@NotNull final AttachmentResource attachment) {
		return SMIME_MIMETYPES.contains(attachment.getDataSource().getContentType());
	}

	private AttachmentDecryptionResult decryptAndUnsignAttachment(
			@NotNull final AttachmentResource attachment,
			@Nullable final Pkcs12Config pkcs12Config,
			@NotNull final OriginalSmimeDetails messageSmimeDetails,
			@Nullable final OriginalSmimeDetailsImpl smimeDetailsToUpdate) {
		try {
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(new InternetHeaders(), attachment.readAllBytes());
			mimeBodyPart.addHeader("Content-Type", restoreSmimeContentType(attachment, messageSmimeDetails));

			AttachmentDecryptionResult liberatedContent = null;

			final SmimeState smimeState = determineStatus(mimeBodyPart, messageSmimeDetails);
			if (smimeState == SmimeState.ENCRYPTED) {
				liberatedContent = getEncryptedContent(pkcs12Config, mimeBodyPart, smimeDetailsToUpdate);
			} else if (smimeState == SmimeState.SIGNED) {
				liberatedContent = getSignedContent(mimeBodyPart, smimeDetailsToUpdate);
			} else if (smimeState == SmimeState.PROBABLY_SIGNED) {
				liberatedContent = tryGetSignedContent(mimeBodyPart, smimeDetailsToUpdate);
			}

			return liberatedContent != null ? liberatedContent : new AttachmentDecryptionResultImpl(SmimeMode.PLAIN, attachment);
		} catch (MessagingException | IOException e) {
			throw new SmimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
		}
	}

	@Nullable
	private AttachmentDecryptionResult getEncryptedContent(final @Nullable Pkcs12Config pkcs12Config, final MimeBodyPart mimeBodyPart,
			@Nullable final OriginalSmimeDetailsImpl smimeDetailsToUpdate)
			throws MessagingException, IOException {
		if (pkcs12Config != null) {
			MimeBodyPart liberatedBodyPart = SmimeUtil.decrypt(mimeBodyPart, retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config));
			if (SmimeUtil.getStatus(liberatedBodyPart) == SmimeState.SIGNED_ENVELOPED) {
				final AttachmentDecryptionResult signedContent = getSignedContent(liberatedBodyPart, null);
				if (signedContent != null) {
					return new AttachmentDecryptionResultImpl(SmimeMode.SIGNED_ENCRYPTED, signedContent.getAttachmentResource());
				}
				// apparently the sign was invalid, so ignore and continue with the decrypted attachment instead
			}
			return toAttachmentDecryptionResult(SmimeMode.ENCRYPTED, handleLiberatedContent(liberatedBodyPart.getContent()));
		}
		LOGGER.warn("Message was encrypted, but no Pkcs12Config was given to decrypt it with, skipping attachment...");
		return null;
	}

	@Nullable
	private AttachmentDecryptionResult getSignedContent(final MimeBodyPart mimeBodyPart, @Nullable final OriginalSmimeDetailsImpl smimeDetailsToUpdate)
			throws MessagingException, IOException {
		final boolean validSignature = checkSmimePartSignature(mimeBodyPart);
		if (smimeDetailsToUpdate != null) {
			smimeDetailsToUpdate.completeWithSmimeSignatureValid(validSignature);
		}
		if (!validSignature) {
			LOGGER.warn("Content is S/MIME signed, but signature is not valid; keeping the signed content for parsing.");
		}
		MimeBodyPart liberatedBodyPart = SmimeUtil.getSignedContent(mimeBodyPart);
		return toAttachmentDecryptionResult(SmimeMode.SIGNED, handleLiberatedContent(liberatedBodyPart.getContent()));
	}

	@Nullable
	private AttachmentDecryptionResult tryGetSignedContent(final MimeBodyPart mimeBodyPart, @Nullable final OriginalSmimeDetailsImpl smimeDetailsToUpdate)
			throws MessagingException, IOException {
		try {
			return getSignedContent(mimeBodyPart, smimeDetailsToUpdate);
		} catch (MessagingException | IOException | org.simplejavamail.utils.mail.smime.SmimeException e) {
			// ignore, apparently not S/MIME after all
		}
		LOGGER.warn("Content classified as signed, but apparently not using S/MIME; skipping S/MIME interpreter...");
		return null;
	}

	private boolean checkSmimePartSignature(final MimeBodyPart mimeBodyPart) {
		try {
			return hasSignerInformation(mimeBodyPart) && SmimeUtil.checkSignature(mimeBodyPart);
		} catch (org.simplejavamail.utils.mail.smime.SmimeException e) {
			LOGGER.warn("Content is S/MIME signed, but signature verification failed. Reason: {}", e.getMessage());
			LOGGER.debug("S/MIME attachment signature verification failure: {}", e.getMessage());
			return false;
		}
	}

	@Nullable
	private AttachmentDecryptionResult toAttachmentDecryptionResult(final SmimeMode smimeMode, @Nullable final AttachmentResource attachmentResource) {
		return attachmentResource != null ? new AttachmentDecryptionResultImpl(smimeMode, attachmentResource) : null;
	}

	private String restoreSmimeContentType(@NotNull final AttachmentResource attachment, final OriginalSmimeDetails originalSmimeDetails) {
		String contentType = attachment.getDataSource().getContentType();
		if (contentType.contains("multipart/signed") && !contentType.contains("protocol") && originalSmimeDetails.getSmimeProtocol() != null) {
			// this step is needed, because converted messages from Outlook don't come out correctly
			contentType = format("multipart/signed;protocol=\"%s\";micalg=%s",
					originalSmimeDetails.getSmimeProtocol(), originalSmimeDetails.getSmimeMicalg());
		}
		return contentType;
	}

	@Nullable
	private AttachmentResource handleLiberatedContent(@Nullable final Object content)
			throws MessagingException, IOException {

		if (content instanceof MimeMultipart) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream();
			final MimeMessage decryptedMessage = new MimeMessage((Session) null) {
				@Override
				protected void updateMessageID() throws MessagingException {
					setHeader("Message-ID", SmimeRecognitionUtil.SMIME_ATTACHMENT_MESSAGE_ID);
				}
			};
			decryptedMessage.setContent((Multipart) content);
			decryptedMessage.writeTo(os);
			return new AttachmentResource("signed-email.eml", new ByteArrayDataSource(os.toByteArray(), "message/rfc822"));
		}
		LOGGER.warn("S/MIME content could not be converted to an email attachment, keeping the original S/MIME attachment. Content type: {}",
				content != null ? content.getClass() : null);
		return null;
	}

	private SmimeState determineStatus(@NotNull final MimePart mimeBodyPart, @NotNull final OriginalSmimeDetails messageSmimeDetails) {
		SmimeState status = SmimeUtil.getStatus(mimeBodyPart);
		boolean trustStatus = status != SmimeState.ENCRYPTED || messageSmimeDetails.getSmimeMode() == SmimeMode.PLAIN;
		if (trustStatus) {
			return status;
		}
		return "signed-data".equals(messageSmimeDetails.getSmimeType()) ? SmimeState.SIGNED : SmimeState.ENCRYPTED;
	}

	/**
	 * @see SMIMEModule#getSmimeDetails(AttachmentResource)
	 */
	@NotNull
	@Override
	public SmimeDetails getSmimeDetails(@NotNull final AttachmentResource attachment) {
		final String contentType = attachment.getDataSource().getContentType();
		final String signedByAddress = getSignedByAddress(attachment);
		return new SmimeDetailsImpl(contentType, signedByAddress);
	}

	/**
	 * @see SMIMEModule#getSignedByAddress(AttachmentResource)
	 */
	@Override
	@Nullable
	public String getSignedByAddress(@NotNull AttachmentResource smimeAttachment) {
		try {
			final InternetHeaders internetHeaders = new InternetHeaders();
			internetHeaders.addHeader("Content-Type", smimeAttachment.getDataSource().getContentType());
			return getSignedByAddress(new MimeBodyPart(internetHeaders, smimeAttachment.readAllBytes()));
		} catch (MessagingException | IOException e) {
			throw new SmimeException(format(ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT, smimeAttachment), e);
		}
	}

	/**
	 * Delegates to {@link #determineSMIMESigned(MimePart)} and {@link #getSignedByAddress(SMIMESigned)}.
	 *
	 * @see SMIMEModule#getSignedByAddress(MimePart)
	 */
	@Nullable
	public String getSignedByAddress(@NotNull MimePart mimePart) {
		try {
			return getSignedByAddress(determineSMIMESigned(mimePart));
		} catch (SmimeException e) {
			// not the right scenario to find signed-by, skip attempt
			return null;
		}
	}

	public boolean verifyValidSignature(@NotNull MimeMessage mimeMessage, @NotNull OriginalSmimeDetails messageSmimeDetails) {
		final SmimeState smimeState = determineStatus(mimeMessage, messageSmimeDetails);
		final boolean signedState = smimeState == SmimeState.SIGNED ||
				smimeState == SmimeState.PROBABLY_SIGNED ||
				smimeState == SmimeState.SIGNED_ENVELOPED;
		return signedState && hasSignerInformation(mimeMessage) && SmimeUtil.checkSignature(mimeMessage);
	}

	private boolean hasSignerInformation(@NotNull final MimePart mimePart) {
		try {
			return !determineSMIMESigned(mimePart).getSignerInfos().getSigners().isEmpty();
		} catch (SmimeException e) {
			return false;
		}
	}

	@NotNull
	private static SMIMESigned determineSMIMESigned(MimePart mimePart) {
		try {
			if (mimePart.isMimeType("multipart/signed")) {
				return new SMIMESigned((MimeMultipart) mimePart.getContent());
			} else if (mimePart.isMimeType("application/pkcs7-mime") || mimePart.isMimeType("application/x-pkcs7-mime")) {
				return new SMIMESigned(mimePart);
			} else {
				throw new SmimeException(format(MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED, mimePart));
			}
		} catch (MessagingException | CMSException | SMIMEException | IOException e) {
			throw new SmimeException(ERROR_DETERMINING_SMIME_SIGNER, e);
		}
	}

	/**
	 * @deprecated Should be removed once the pull-request has been merged and released
	 * @see "https://github.com/markenwerk/java-utils-mail-smime/issues/5"
	 */
	@SuppressWarnings("DeprecatedIsStillUsed")
	private static String getSignedByAddress(SMIMESigned smimeSigned) {
		try {
			@SuppressWarnings("rawtypes")
			Store certificates = smimeSigned.getCertificates();

			SignerInformation signerInformation = smimeSigned.getSignerInfos().getSigners().iterator().next();
			X509Certificate certificate = getCertificate(certificates, signerInformation.getSID());
			SignerInformationVerifier verifier = getVerifier(certificate);
			X500Name x500name = verifier.getAssociatedCertificate().getSubject();
			RDN cn = x500name.getRDNs(BCStyle.CN)[0];
			return IETFUtils.valueToString(cn.getFirst().getValue());
		} catch (Exception e) {
			throw new SmimeException(ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE, e);
		}
	}

	/**
	 * @deprecated This is duplicate code from SmimeUtil and should be removed once the pull-request has been merged and released
	 * @see "https://github.com/markenwerk/java-utils-mail-smime/issues/5"
	 */
	@Deprecated
	private static X509Certificate getCertificate(@SuppressWarnings("rawtypes") Store certificates, SignerId signerId)
			throws CertificateException {
		@SuppressWarnings({ "unchecked" })
		X509CertificateHolder certificateHolder = (X509CertificateHolder) certificates.getMatches(signerId).iterator()
				.next();
		JcaX509CertificateConverter certificateConverter = new JcaX509CertificateConverter();
		certificateConverter.setProvider(BouncyCastleProvider.PROVIDER_NAME);
		return certificateConverter.getCertificate(certificateHolder);
	}

	/**
	 * @deprecated This is duplicate code from SmimeUtil and should be removed once the pull-request has been merged and released
	 * @see "https://github.com/markenwerk/java-utils-mail-smime/issues/5"
	 */
	@Deprecated
	private static SignerInformationVerifier getVerifier(X509Certificate certificate) throws OperatorCreationException {
		JcaSimpleSignerInfoVerifierBuilder builder = new JcaSimpleSignerInfoVerifierBuilder();
		builder.setProvider(BouncyCastleProvider.PROVIDER_NAME);
		return builder.build(certificate);
	}

	@NotNull
	@Override
	public MimeMessage signMessageWithSmime(@Nullable final Session session, @NotNull final Email email, @NotNull final MimeMessage messageToProtect, @NotNull final SmimeSigningConfig smimeSigningConfig) {
		try {
			final MimeBodyPart signedBodyPart = SmimeUtil.sign(extractMimeBodyPart(messageToProtect),
					retrieveSmimeKeyFromPkcs12Keystore(smimeSigningConfig.getPkcs12Config()),
					defaultTo(smimeSigningConfig.getSignatureAlgorithm(), SmimeUtil.DEFAULT_SIGNATURE_ALGORITHM_NAME));
			return createProtectedMessage(session, email, messageToProtect, signedBodyPart);
		} catch (MessagingException | IOException e) {
			throw new SmimeException("Error signing message with S/MIME", e);
		}
	}

	@NotNull
	@Override
	public MimeMessage encryptMessageWithSmime(@Nullable final Session session, @NotNull final Email email, @NotNull final MimeMessage messageToProtect, @NotNull final SmimeEncryptionConfig smimeEncryptionConfig) {
		try {
			final MimeBodyPart encryptedBodyPart = SmimeUtil.encrypt(extractMimeBodyPart(messageToProtect),
					smimeEncryptionConfig.getX509Certificate(),
					ofNullable(smimeEncryptionConfig.getKeyEncapsulationAlgorithm())
							.map(KeyEncapsulationAlgorithm::valueOf)
							.orElse(SmimeUtil.DEFAULT_KEY_ENCAPSULATION_ALGORITHM),
					ofNullable(smimeEncryptionConfig.getCipherAlgorithm())
							.map(CMSAlgorithmResolver::resolve)
							.orElse(SmimeUtil.DEFAULT_CIPHER));
			return createProtectedMessage(session, email, messageToProtect, encryptedBodyPart);
		} catch (MessagingException | IOException e) {
			throw new SmimeException("Error encrypting message with S/MIME", e);
		}
	}

	@NotNull
	@Override
	public MimeMessage encryptMessageWithSmimeForRecipients(@Nullable final Session session, @NotNull final Email email,
			@NotNull final MimeMessage messageToProtect, @NotNull final Collection<X509Certificate> recipientCerts,
			@Nullable final String keyEncapsulationAlgorithmStr, @Nullable final String cipherAlgorithmStr) {
		try {
			final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm = ofNullable(keyEncapsulationAlgorithmStr)
					.map(KeyEncapsulationAlgorithm::valueOf)
					.orElse(SmimeUtil.DEFAULT_KEY_ENCAPSULATION_ALGORITHM);
			final ASN1ObjectIdentifier cmsAlgorithm = ofNullable(cipherAlgorithmStr)
					.map(CMSAlgorithmResolver::resolve)
					.orElse(SmimeUtil.DEFAULT_CIPHER);

			final SMIMEEnvelopedGenerator generator = new SMIMEEnvelopedGenerator();
			for (X509Certificate cert : recipientCerts) {
				generator.addRecipientInfoGenerator(createRecipientInfoGenerator(cert, keyEncapsulationAlgorithm));
			}

			final OutputEncryptor encryptor = new JceCMSContentEncryptorBuilder(cmsAlgorithm)
					.setProvider(BouncyCastleProvider.PROVIDER_NAME).build();

			final MimeBodyPart encryptedBodyPart = generator.generate(extractMimeBodyPart(messageToProtect), encryptor);
			return createProtectedMessage(session, email, messageToProtect, encryptedBodyPart);
		} catch (Exception e) {
			throw new SmimeException(ERROR_ENCRYPTING_SMIME_FOR_RECIPIENTS, e);
		}
	}

	private static JceKeyTransRecipientInfoGenerator createRecipientInfoGenerator(@NotNull final X509Certificate certificate,
			@NotNull final KeyEncapsulationAlgorithm keyEncapsulationAlgorithm)
			throws CertificateEncodingException, InvalidAlgorithmParameterException {
		final JceKeyTransRecipientInfoGenerator infoGenerator;
		if (keyEncapsulationAlgorithm == KeyEncapsulationAlgorithm.RSA) {
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate);
		} else {
			final String digestName = determineDigestNameForOaep(keyEncapsulationAlgorithm);
			final AlgorithmIdentifier oaepParams = new JcaAlgorithmParametersConverter().getAlgorithmIdentifier(
					PKCSObjectIdentifiers.id_RSAES_OAEP,
					new OAEPParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName), PSource.PSpecified.DEFAULT));
			infoGenerator = new JceKeyTransRecipientInfoGenerator(certificate, oaepParams);
		}
		infoGenerator.setProvider(BouncyCastleProvider.PROVIDER_NAME);
		return infoGenerator;
	}

	@NotNull
	private static String determineDigestNameForOaep(@NotNull final KeyEncapsulationAlgorithm alg) throws InvalidAlgorithmParameterException {
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA224) return "SHA-224";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA256) return "SHA-256";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA384) return "SHA-384";
		if (alg == KeyEncapsulationAlgorithm.RSA_OAEP_SHA512) return "SHA-512";
		throw new InvalidAlgorithmParameterException("Unknown S/MIME key encapsulation algorithm: " + alg.name());
	}

	@NotNull
	private static MimeBodyPart extractMimeBodyPart(@NotNull final MimeMessage message)
			throws MessagingException, IOException {
		final Object content = message.getContent();
		final UpdatableMimeBodyPart bodyPart = new UpdatableMimeBodyPart();
		if (content instanceof Multipart) {
			bodyPart.setContent((Multipart) content);
		} else {
			bodyPart.setContent(content, message.getDataHandler().getContentType());
		}
		bodyPart.updateHeadersPublic();
		return bodyPart;
	}

	@NotNull
	private static MimeMessage createProtectedMessage(@Nullable final Session session,
			@NotNull final Email email,
			@NotNull final MimeMessage original,
			@NotNull final MimeBodyPart protectedBodyPart)
			throws MessagingException, IOException {
		final Session effectiveSession = session != null ? session : original.getSession();
		if (effectiveSession == null) {
			throw new MessagingException("A Session is required to create an S/MIME message");
		}
		final MimeMessage protectedMessage = new MessageIdFixingMimeMessage(effectiveSession, email.getId());
		copyAllHeaderLines(original, protectedMessage);
		protectedMessage.setContent(protectedBodyPart.getContent(), protectedBodyPart.getContentType());
		copyAllHeaders(protectedBodyPart, protectedMessage);
		return protectedMessage;
	}

	private static void copyAllHeaderLines(@NotNull final MimeMessage from, @NotNull final MimeMessage to)
			throws MessagingException {
		final Enumeration<String> headerLines = from.getAllHeaderLines();
		while (headerLines.hasMoreElements()) {
			to.addHeaderLine(headerLines.nextElement());
		}
	}

	private static void copyAllHeaders(@NotNull final MimeBodyPart from, @NotNull final MimeMessage to) throws MessagingException {
		final Enumeration<Header> headers = from.getAllHeaders();
		while (headers.hasMoreElements()) {
			final Header h = headers.nextElement();
			to.setHeader(h.getName(), h.getValue());
		}
	}

	private static final class UpdatableMimeBodyPart extends MimeBodyPart {
		private void updateHeadersPublic() throws MessagingException {
			super.updateHeaders();
		}
	}

	@NotNull
	private SmimeKey retrieveSmimeKeyFromPkcs12Keystore(@NotNull Pkcs12Config pkcs12Config) {
		if (!SIMPLE_SMIMESTORE_CACHE.containsKey(pkcs12Config)) {
			SmimeKey smimeKey = produceSmimeKey(pkcs12Config);
			if (smimeKey == null) {
				throw new SmimeException(ERROR_OBTAINING_SMIME_KEY);
			}
			SIMPLE_SMIMESTORE_CACHE.put(pkcs12Config, smimeKey);
		}
        return SIMPLE_SMIMESTORE_CACHE.get(pkcs12Config);
	}

	@Nullable
	private SmimeKey produceSmimeKey(final @NotNull Pkcs12Config pkcs12) {
		return new SmimeKeyStore(new ByteArrayInputStream(pkcs12.getPkcs12StoreData()), pkcs12.getStorePassword())
				.getPrivateKey(pkcs12.getKeyAlias(), pkcs12.getKeyPassword());
	}

	@Override
	public <T> boolean isGeneratedSmimeMessageId(String key, T headerValue) {
		return SmimeRecognitionUtil.isGeneratedSmimeMessageId(key, headerValue);
	}
}
