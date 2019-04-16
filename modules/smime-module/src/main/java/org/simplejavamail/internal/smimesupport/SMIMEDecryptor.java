package org.simplejavamail.internal.smimesupport;

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
import org.simplejavamail.api.email.OriginalSMimeDetails;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimePart;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.smimesupport.SMimeException.ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT;
import static org.simplejavamail.internal.smimesupport.SMimeException.ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT;

/**
 * This class only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is ued through reflection
public class SMIMEDecryptor implements SMIMEModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(SMIMEDecryptor.class);
	private static final List<String> SMIME_MIMETYPE = asList("application/pkcs7-mime", "application/x-pkcs7-mime");

	/**
	 * @see SMIMEModule#decryptAttachments(List)
	 */
	@Nonnull
	@Override
	public List<AttachmentResource> decryptAttachments(@Nonnull final List<AttachmentResource> attachments) {
		LOGGER.debug("decrypting any S/MIME signed attachments...");
		final List<AttachmentResource> decryptedAttachments = new ArrayList<>(attachments);
		for (int i = 0; i < decryptedAttachments.size(); i++) {
			final AttachmentResource attachment = decryptedAttachments.get(i);
			if (isSMimeAttachment(attachment)) {
				decryptedAttachments.set(i, decryptAttachment(attachment));
			}
		}
		return decryptedAttachments;
	}

	/**
	 * @see SMIMEModule#isSMimeAttachment(AttachmentResource)
	 */
	@Override
	public boolean isSMimeAttachment(@Nonnull final AttachmentResource attachment) {
		return SMIME_MIMETYPE.contains(attachment.getDataSource().getContentType());
	}

	private AttachmentResource decryptAttachment(final AttachmentResource attachment) {
		try {
			final InternetHeaders internetHeaders = new InternetHeaders();
			internetHeaders.addHeader("Content-Type", attachment.getDataSource().getContentType());
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(internetHeaders, attachment.readAllBytes());
			if (SmimeUtil.checkSignature(mimeBodyPart)) {
				final SmimeState status = SmimeUtil.getStatus(mimeBodyPart); // FIXME report bug that it inspect the mimetype wrong

				final MimeBodyPart signedContentBody = SmimeUtil.getSignedContent(mimeBodyPart);
				final Object signedContent = signedContentBody.getContent();
				if (signedContent instanceof MimeMultipart) {
					final ByteArrayOutputStream os = new ByteArrayOutputStream();
					((MimeMultipart) signedContent).writeTo(os);
					return new AttachmentResource("signed-email.eml", new ByteArrayDataSource(os.toByteArray(), "message/rfc822"));
				} else {
					LOGGER.warn("S/MIME signed content type not recognized, please raise an issue for " + signedContent.getClass());
				}
			} else {
				LOGGER.warn("Content is S/MIME signed, but signature is not valid, skipping decryption...");
			}
			return attachment;
		} catch (MessagingException | IOException e) {
			throw new SMimeException(format(ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT, attachment), e);
		}
	}

	/**
	 * @see SMIMEModule#getSMimeDetails(AttachmentResource)
	 */
	@Nonnull
	@Override
	@SuppressWarnings("deprecation")
	public OriginalSMimeDetails getSMimeDetails(@Nonnull final AttachmentResource attachment) {
		return new OriginalSMimeDetails(attachment.getDataSource().getContentType(), null, null, getSignedByAddress(attachment));
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
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(internetHeaders, smimeAttachment.readAllBytes());
			if (SmimeUtil.checkSignature(mimeBodyPart)) {
				return getSignedByAddress(mimeBodyPart);
			} else {
				LOGGER.warn("Content is S/MIME signed, but signature is not valid, returning null...");
			}
			return null;
		} catch (MessagingException | IOException e) {
			throw new SMimeException(format(ERROR_EXTRACTING_SIGNEDBY_FROM_SMIME_SIGNED_ATTACHMENT, smimeAttachment), e);
		}
	}

	@Nullable
	public String getSignedByAddress(@Nonnull MimePart mimePart) {
		return getSignedByAddress(determineSMIMESigned(mimePart));
	}

	@Nonnull
	private static SMIMESigned determineSMIMESigned(MimePart mimePart) {
		try {
			if (mimePart.isMimeType("multipart/signed")) {
				return new SMIMESigned((MimeMultipart) mimePart.getContent());
			} else if (mimePart.isMimeType("application/pkcs7-mime") || mimePart.isMimeType("application/x-pkcs7-mime")) {
				return new SMIMESigned(mimePart);
			} else {
				return null; // FIXME fix proper MailException type
			}
		} catch (MessagingException | CMSException | SMIMEException | IOException e) {
			return null; // FIXME fix proper MailException type
		}
	}

	/**
	 * @deprecated Should be removed once the pull-request has been merged and released
	 * @see "https://github.com/markenwerk/java-utils-mail-smime/issues/5"
	 */
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
			throw new RuntimeException(e);
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
}
