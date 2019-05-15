package org.simplejavamail.internal.smimesupport;

import net.markenwerk.utils.mail.smime.SmimeKey;
import net.markenwerk.utils.mail.smime.SmimeKeyStore;
import net.markenwerk.utils.mail.smime.SmimeState;
import net.markenwerk.utils.mail.smime.SmimeUtil;
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
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeMultipartSigned;
import org.simplejavamail.api.internal.smimesupport.model.SmimeDetails;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.smimesupport.builder.SmimeParseResultBuilder;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.internal.smimesupport.model.SmimeDetailsImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimePart;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static net.markenwerk.utils.mail.smime.SmimeState.ENCRYPTED;
import static net.markenwerk.utils.mail.smime.SmimeState.SIGNED;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_DETERMINING_SMIME_SIGNER;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_EXTRACTING_SUBJECT_FROM_CERTIFICATE;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_READING_PKCS12_KEYSTORE;
import static org.simplejavamail.internal.smimesupport.SmimeException.ERROR_READING_SMIME_CONTENT_TYPE;
import static org.simplejavamail.internal.smimesupport.SmimeException.MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.SMIME_ATTACHMENT_MESSAGE_ID;
import static org.simplejavamail.internal.smimesupport.SmimeRecognitionUtil.isSmimeContentType;


/**
 * This class only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class SMIMESupport implements SMIMEModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(SMIMESupport.class);
	private static final List<String> SMIME_MIMETYPES = asList("application/pkcs7-mime", "application/x-pkcs7-mime", "multipart/signed");

	public SmimeParseResultBuilder decryptAttachments(@Nonnull final List<AttachmentResource> attachments, @Nonnull final OutlookMessage outlookMessage,
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
			smimeBuilder.getOriginalSmimeDetails().completeWithSmimeSignatureValid(smimeBuilder.getSmimeSignedEmail() != null);
		}

		return smimeBuilder;
	}

	public SmimeParseResultBuilder decryptAttachments(@Nonnull final List<AttachmentResource> attachments, @Nonnull final MimeMessage mimeMessage, @Nullable final Pkcs12Config pkcs12Config) {
		final SmimeParseResultBuilder smimeBuilder = new SmimeParseResultBuilder();

		initSmimeMetadata(smimeBuilder, mimeMessage);
		decryptAttachments(smimeBuilder, attachments, pkcs12Config);
		finalizeSmimeMetadata(smimeBuilder, mimeMessage);

		return smimeBuilder;
	}

	private void initSmimeMetadata(final SmimeParseResultBuilder smimeBuilder, @Nonnull final MimeMessage mimeMessage) {
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

	private void finalizeSmimeMetadata(final SmimeParseResultBuilder smimeBuilder, @Nonnull final MimeMessage mimeMessage) {
		final OriginalSmimeDetailsImpl originalSmimeDetails = smimeBuilder.getOriginalSmimeDetails();

		if (originalSmimeDetails.getSmimeMode() != SmimeMode.PLAIN) {
			LOGGER.debug("checking who signed this message...");
			originalSmimeDetails.completeWithSmimeSignedBy(getSignedByAddress(mimeMessage));
			if (originalSmimeDetails.getSmimeMode() == SmimeMode.SIGNED) {
				originalSmimeDetails.completeWithSmimeSignatureValid(checkSignature(mimeMessage, originalSmimeDetails));
			}
		}
	}

	private boolean checkSignature(@Nonnull final MimeMessage mimeMessage, @Nullable final OriginalSmimeDetails messageSmimeDetails) {
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

	private void decryptAttachments(@Nonnull final SmimeParseResultBuilder smimeBuilder, @Nonnull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config) {
		LOGGER.debug("checking for S/MIME signed / encrypted attachments...");
		List<AttachmentResource> decryptedAttachments = decryptAttachments(attachments, pkcs12Config, smimeBuilder.getOriginalSmimeDetails());
		smimeBuilder.addDecryptedAttachments(decryptedAttachments);

		if (attachments.size() == 1) {
			final AttachmentResource onlyAttachment = attachments.get(0);
			final AttachmentResource onlyAttachmentDecrypted = smimeBuilder.getDecryptedAttachments().get(0);
			if (isSmimeAttachment(onlyAttachment) && isMimeMessageAttachment(onlyAttachmentDecrypted)) {
				smimeBuilder.getOriginalSmimeDetails().completeWith(determineSmimeDetails(onlyAttachment));
				smimeBuilder.setSmimeSignedEmailToProcess(onlyAttachmentDecrypted);
			}
		}
	}

	private boolean isMimeMessageAttachment(final AttachmentResource attachment) {
		return attachment.getDataSource().getContentType().equals("message/rfc822");
	}

	@Nonnull
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
	@Nonnull
	@Override
	public List<AttachmentResource> decryptAttachments(
			@Nonnull final List<AttachmentResource> attachments,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nonnull final OriginalSmimeDetails messageSmimeDetails) {
		final List<AttachmentResource> decryptedAttachments;
		decryptedAttachments = new ArrayList<>(attachments);

		for (int i = 0; i < decryptedAttachments.size(); i++) {
			final AttachmentResource attachment = decryptedAttachments.get(i);
			if (isSmimeAttachment(attachment)) {
				try {
					LOGGER.debug("decrypting S/MIME signed attachment '{}'...", attachment.getName());
					decryptedAttachments.set(i, decryptAndUnsignAttachment(attachment, pkcs12Config, messageSmimeDetails));
				} catch (Exception e) {
					throw new SmimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
				}
			}
		}
		return decryptedAttachments;
	}

	/**
	 * @see SMIMEModule#isSmimeAttachment(AttachmentResource)
	 */
	@Override
	public boolean isSmimeAttachment(@Nonnull final AttachmentResource attachment) {
		return SMIME_MIMETYPES.contains(attachment.getDataSource().getContentType());
	}

	private AttachmentResource decryptAndUnsignAttachment(
			@Nonnull final AttachmentResource attachment,
			@Nullable final Pkcs12Config pkcs12Config,
			@Nonnull final OriginalSmimeDetails messageSmimeDetails) {
		try {
			final InternetHeaders internetHeaders = new InternetHeaders();
			internetHeaders.addHeader("Content-Type", restoreSmimeContentType(attachment, messageSmimeDetails));
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(internetHeaders, attachment.readAllBytes());

			AttachmentResource liberatedContent = null;

			switch (determineStatus(mimeBodyPart, messageSmimeDetails)) {
				case SIGNED:
					if (SmimeUtil.checkSignature(mimeBodyPart)) {
						MimeBodyPart liberatedBodyPart = SmimeUtil.getSignedContent(mimeBodyPart);
						liberatedContent = handleLiberatedContent(liberatedBodyPart.getContent());
					} else {
						LOGGER.warn("Content is S/MIME signed, but signature is not valid; skipping S/MIME interpeter...");
					}
					break;
				case ENCRYPTED:
					assumeTrue(pkcs12Config != null, "Message was encrypted, but no Pkcs12Config was given to decrypt it with");
					SmimeKey smimeKey = retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config);
					MimeBodyPart liberatedBodyPart = SmimeUtil.decrypt(mimeBodyPart, smimeKey);
					liberatedContent = handleLiberatedContent(liberatedBodyPart.getContent());
					break;
			}

			return liberatedContent != null
					? decryptAndUnsignAttachment(liberatedContent, pkcs12Config, messageSmimeDetails)
					: attachment;
		} catch (MessagingException | IOException e) {
			throw new SmimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
		}
	}

	private String restoreSmimeContentType(@Nonnull final AttachmentResource attachment, final OriginalSmimeDetails originalSmimeDetails) {
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

	private SmimeState determineStatus(@Nonnull final MimePart mimeBodyPart, @Nonnull final OriginalSmimeDetails messageSmimeDetails) {
		SmimeState status = SmimeUtil.getStatus(mimeBodyPart);
		boolean trustStatus = status != ENCRYPTED || messageSmimeDetails.getSmimeMode() == SmimeMode.PLAIN;
		return trustStatus ? status : "signed-data".equals(messageSmimeDetails.getSmimeType()) ? SIGNED : ENCRYPTED;
	}

	/**
	 * @see SMIMEModule#getSmimeDetails(AttachmentResource)
	 */
	@Nonnull
	@Override
	public SmimeDetails getSmimeDetails(@Nonnull final AttachmentResource attachment) {
		final String contentType = attachment.getDataSource().getContentType();
		final String signedByAddress = getSignedByAddress(attachment);
		return new SmimeDetailsImpl(contentType, signedByAddress);
	}

	/**
	 * @see SMIMEModule#getSignedByAddress(AttachmentResource)
	 */
	@Override
	@Nullable
	public String getSignedByAddress(@Nonnull AttachmentResource smimeAttachment) {
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
	@SuppressWarnings("deprecation")
	public String getSignedByAddress(@Nonnull MimePart mimePart) {
		try {
			return getSignedByAddress(determineSMIMESigned(mimePart));
		} catch (SmimeException e) {
			// not the right scenario to find signed-by, skip attempt
			return null;
		}
	}

	public boolean verifyValidSignature(@Nonnull MimeMessage mimeMessage, @Nonnull OriginalSmimeDetails messageSmimeDetails) {
		return determineStatus(mimeMessage, messageSmimeDetails) != SIGNED || SmimeUtil.checkSignature(mimeMessage);
	}

	@Nonnull
	private static SMIMESigned determineSMIMESigned(MimePart mimePart) {
		try {
			if (mimePart.isMimeType("multipart/signed")) {
				return new SMIMESigned((MimeMultipart) mimePart.getContent());
			} else if (mimePart.isMimeType("application/pkcs7-mime") || mimePart.isMimeType("application/x-pkcs7-mime")) {
				return new SMIMESigned(mimePart);
			} else {
				throw new SmimeException(format(MIMEPART_ASSUMED_SIGNED_ACTUALLY_NOT_SIGNED, mimePart.toString()));
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

	@Nonnull
	@Override
	public MimeMessage signMessage(@Nonnull Session session, @Nonnull MimeMessage message, @Nonnull Pkcs12Config pkcs12Config) {
		SmimeKey smimeKey = retrieveSmimeKeyFromPkcs12Keystore(pkcs12Config);
		return SmimeUtil.sign(session, message, smimeKey);
	}

	@Nonnull
	@Override
	public MimeMessage encryptMessage(@Nonnull Session session, @Nonnull MimeMessage message, @Nonnull X509Certificate certificate) {
		return SmimeUtil.encrypt(session, message, certificate);
	}

	private SmimeKey retrieveSmimeKeyFromPkcs12Keystore(@Nonnull Pkcs12Config pkcs12Config) {
		try {
			try (InputStream pkcs12StoreStream = pkcs12Config.getPkcs12StoreStream()) {
				return new SmimeKeyStore(pkcs12StoreStream, pkcs12Config.getStorePassword())
						.getPrivateKey(pkcs12Config.getKeyAlias(), pkcs12Config.getKeyPassword());
			}
		} catch (IOException e) {
			throw new SmimeException(ERROR_READING_PKCS12_KEYSTORE, e);
		}
	}
}