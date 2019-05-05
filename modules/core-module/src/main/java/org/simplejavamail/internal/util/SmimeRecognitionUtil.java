package org.simplejavamail.internal.util;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.ContentType;
import javax.mail.internet.ParseException;

import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.simplejavamail.api.email.OriginalSmimeDetails.*;

// FIXME move this to the smime module?
public final class SmimeRecognitionUtil {

	private static final List<String> SMIME_MIMETYPES = asList("application/pkcs7-mime", "application/x-pkcs7-mime");

	/**
	 * Used internally to recognize when we should ignore message ID header when starting a new email as a copy.
	 */
	public static final String SMIME_ATTACHMENT_MESSAGE_ID = "<generated-for-smime-signed-attachment@simple-java-mail>";

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted.
	 */
	public static boolean isSmimeAttachment(@Nonnull final AttachmentResource attachment) {
		try {
			return isSmimeContentType(new ContentType(attachment.getDataSource().getContentType()));
		} catch (ParseException e) {
			return false;
		}
	}

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted.
	 */
	public static boolean isSmimeContentType(@Nonnull final ContentType ct) {
		return SMIME_MIMETYPES.contains(ct.getBaseType()) ||
				isSmimeMultiPartSigned(ct.getBaseType(), ct.getParameter("protocol"));
	}

	public static boolean isGeneratedSmimeMessageId(@Nonnull final Map.Entry headerEntry) {
		return headerEntry.getKey().equals("Message-ID") &&
				headerEntry.getValue().equals(SMIME_ATTACHMENT_MESSAGE_ID);
	}

	// FIXME use this instead of SmimeUtil's detection
	public static SmimeMode determineSmimeMode(final OriginalSmimeDetails d) {
		if (isSmimeMultiPartSigned(d.getSmimeMime(), d.getSmimeProtocol()) || "signed-data".equals(d.getSmimeType())) {
			return SmimeMode.SIGNED;
		}
		if (SMIME_MIMETYPES.contains(d.getSmimeMime()) && "enveloped-data".equals(d.getSmimeType())) {
			return SmimeMode.ENCRYPTED;
		}
		return SmimeMode.PLAIN;
	}

	private static boolean isSmimeMultiPartSigned(@Nullable String mime, @Nullable String protocol) {
		return "multipart/signed".equals(mime) &&
				("application/pkcs7-signature".equals(protocol) ||
						"application/x-pkcs7-signature".equals(protocol));
	}
}