package org.simplejavamail.internal.smimesupport;

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
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEException;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.util.Store;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeMultipartSigned;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;
import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.smimesupport.builder.SmimeParseResultBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.internal.smimesupport.model.SmimeDetailsImpl;
import org.simplejavamail.utils.mail.smime.SmimeKey;
import org.simplejavamail.utils.mail.smime.SmimeKeyStore;
import org.simplejavamail.utils.mail.smime.SmimeState;
import org.simplejavamail.utils.mail.smime.SmimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DETERMINING_SMIME_SIGNER;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_READING_SMIME_CONTENT_TYPE;
import static org.simplejavamail.internal.smimesupport.SmimeException.MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.SMIME_ATTACHMENT_MESSAGE_ID;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.isSmimeContentType;


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

		if (smimeBuilder.getOriginalSmimeDetails().getSmimeMode() == SmimeMode.SIGNED) {
			// this is the only way for Outlook messages to know a valid signature was included
			smimeBuilder.getOriginalSmimeDetails().completeWithSmimeSignatureValid(smimeBuilder.getSmimeSignedOrEncryptedEmail() != null);
		}

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
			if (mimeMessage.getHeader("Content-Type", null) != null) {
				ContentType ct = new ContentType(mimeMessage.getHeader("Content-Type", null));
				if (isSmimeContentType(ct)) {
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
			final boolean validSignature = verifyValidSignature(mimeMessage, messageSmimeDetails);
			if (!validSignature) {
				LOGGER.warn("Message contains invalid S/MIME signature! Assume this emal has been tampered with.");
			}
			return validSignature;
		}
		return false;
	}

	private void decryptAttachments(@NotNull final SmimeParseResultBuilder smimeBuilder, @NotNull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config) {
		LOGGER.debug("checking for S/MIME signed / encrypted attachments...");
		List<AttachmentDecryptionResult> decryptedAttachments = decryptAttachments(attachments, pkcs12Config, smimeBuilder.getOriginalSmimeDetails());
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
		final List<AttachmentDecryptionResult> decryptedAttachments = new ArrayList<>();
		for (final AttachmentResource attachment : attachments) {
			if (isSmimeAttachment(attachment)) {
				try {
					LOGGER.debug("decrypting S/MIME signed attachment '{}'...", attachment.getName());
					decryptedAttachments.add(decryptAndUnsignAttachment(attachment, pkcs12Config, messageSmimeDetails));
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
			@NotNull final OriginalSmimeDetails messageSmimeDetails) {
		try {
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(new InternetHeaders(), attachment.readAllBytes());
			mimeBodyPart.addHeader("Content-Type", restoreSmimeContentType(attachment, messageSmimeDetails));

			AttachmentDecryptionResult liberatedContent = null;

			final SmimeState smimeState = determineStatus(mimeBodyPart, messageSmimeDetails);
			if (smimeState == SmimeState.ENCRYPTED) {
				liberatedContent = getEncryptedContent(pkcs12Config, mimeBodyPart);
			} else if (smimeState == SmimeState.SIGNED) {
				liberatedContent = getSignedContent(mimeBodyPart);
			}

			return liberatedContent != null ? liberatedContent : new AttachmentDecryptionResultImpl(SmimeMode.PLAIN, attachment);
		} catch (MessagingException | IOException e) {
			throw new SmimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
		}
	}

	@Nullable
	private AttachmentDecryptionResult getEncryptedContent(final @Nullable Pkcs12Config pkcs12Config, final MimeBodyPart mimeBodyPart)
			throws MessagingException, IOException {
		if (pkcs12Config != null) {
			MimeBodyPart liberatedBodyPart = SmimeUtil.decrypt(mimeBodyPart, retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config));
			if (SmimeUtil.getStatus(liberatedBodyPart) == SmimeState.SIGNED_ENVELOPED) {
				final AttachmentDecryptionResult signedContent = getSignedContent(liberatedBodyPart);
				if (signedContent != null) {
					return new AttachmentDecryptionResultImpl(SmimeMode.SIGNED_ENCRYPTED, signedContent.getAttachmentResource());
				}
				// apparently the sign was invalid, so ignore and continue with the decrypted attachment instead
			}
			return new AttachmentDecryptionResultImpl(SmimeMode.ENCRYPTED, handleLiberatedContent(liberatedBodyPart.getContent()));
		}
		LOGGER.warn("Message was encrypted, but no Pkcs12Config was given to decrypt it with, skipping attachment...");
		return null;
	}

	@Nullable
	private AttachmentDecryptionResult getSignedContent(final MimeBodyPart mimeBodyPart)
			throws MessagingException, IOException {
		if (SmimeUtil.checkSignature(mimeBodyPart)) {
			MimeBodyPart liberatedBodyPart = SmimeUtil.getSignedContent(mimeBodyPart);
			return new AttachmentDecryptionResultImpl(SmimeMode.SIGNED, handleLiberatedContent(liberatedBodyPart.getContent()));
		}
		LOGGER.warn("Content is S/MIME signed, but signature is not valid; skipping S/MIME interpeter...");
		return null;
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
	private AttachmentResource handleLiberatedContent(final Object content)
			throws MessagingException, IOException {

		if (content instanceof MimeMultipart) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream();
			final MimeMessage decryptedMessage = new MimeMessage((Session) null) {
				@Override
				protected void updateMessageID() throws MessagingException {
					setHeader("Message-ID", SMIME_ATTACHMENT_MESSAGE_ID);
				}
			};
			decryptedMessage.setContent((Multipart) content);
			decryptedMessage.writeTo(os);
			return new AttachmentResource("signed-email.eml", new ByteArrayDataSource(os.toByteArray(), "message/rfc822"));
		}
		LOGGER.warn("S/MIME signed content type not recognized, please raise an issue for " + content.getClass());
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
		return determineStatus(mimeMessage, messageSmimeDetails) != SmimeState.SIGNED || SmimeUtil.checkSignature(mimeMessage);
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
	public MimeMessage signAndOrEncryptEmail(@Nullable final Session session, @NotNull final MimeMessage messageToProtect, @NotNull final Email emailContainingSmimeDetails,
			@Nullable final Pkcs12Config defaultSmimeSigningStore) {
		MimeMessage result = messageToProtect;

		final Pkcs12Config pkcs12Config = Optional
				.ofNullable(emailContainingSmimeDetails.getPkcs12ConfigForSmimeSigning())
				.orElse(defaultSmimeSigningStore);
		if (pkcs12Config != null) {
			result = signMessage(session, result, pkcs12Config);
		}
		if (emailContainingSmimeDetails.getX509CertificateForSmimeEncryption() != null) {
			result = encryptMessage(session, result, emailContainingSmimeDetails.getX509CertificateForSmimeEncryption());
		}
		return result;
	}

	@NotNull
	@Override
	public MimeMessage signMessage(@Nullable Session session, @NotNull MimeMessage message, @NotNull Pkcs12Config pkcs12Config) {
		SmimeKey smimeKey = retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config);
		return SmimeUtil.sign(session, message, smimeKey);
	}

	@NotNull
	@Override
	public MimeMessage encryptMessage(@Nullable Session session, @NotNull MimeMessage message, @NotNull X509Certificate certificate) {
		return SmimeUtil.encrypt(session, message, certificate);
	}

	private SmimeKey retrieveSmimeKeyFromPkcs12Keystore(@NotNull Pkcs12Config pkcs12) {
		if (!SIMPLE_SMIMESTORE_CACHE.containsKey(pkcs12)) {
			SIMPLE_SMIMESTORE_CACHE.put(pkcs12, produceSmimeKey(pkcs12));
		}
		return SIMPLE_SMIMESTORE_CACHE.get(pkcs12);
	}

	private SmimeKey produceSmimeKey(final @NotNull Pkcs12Config pkcs12) {
		return new SmimeKeyStore(new ByteArrayInputStream(pkcs12.getPkcs12StoreData()), pkcs12.getStorePassword())
				.getPrivateKey(pkcs12.getKeyAlias(), pkcs12.getKeyPassword());
	}
}