package org.simplejavamail.internal.smimesupport;

import net.markenwerk.utils.mail.smime.SmimeUtil;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.simplejavamail.internal.smimesupport.SMimeException.ERROR_DECRYPTING_SMIME_SIGNED_ATTACHMENT;

/**
 * This class only serves to hide the S/MIME implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is ued through reflection
public class SMIMEDecryptor implements SMIMEModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(SMIMEDecryptor.class);
	private static final List<String> SMIME_MIMETYPE = asList("application/pkcs7-mime", "application/x-pkcs7-mime");

	@Nonnull
	@Override
	public List<AttachmentResource> decryptAttachments(@Nonnull final List<AttachmentResource> attachments) {
		LOGGER.debug("decrypting any S/MIME signed attachments...");
		final List<AttachmentResource> decryptedAttachments = new ArrayList<>(attachments);
		for (int i = 0; i < decryptedAttachments.size(); i++) {
			final AttachmentResource attachment = decryptedAttachments.get(i);
			if (SMIME_MIMETYPE.contains(attachment.getDataSource().getContentType())) {
				decryptedAttachments.set(i, decryptAttachment(attachment));
			}
		}
		return decryptedAttachments;
	}

	private AttachmentResource decryptAttachment(final AttachmentResource attachment) {
		try {
			final InternetHeaders internetHeaders = new InternetHeaders();
			internetHeaders.addHeader("Content-Type", attachment.getDataSource().getContentType());
			final MimeBodyPart mimeBodyPart = new MimeBodyPart(internetHeaders, attachment.readAllBytes());
			if (SmimeUtil.checkSignature(mimeBodyPart)) {
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
}
