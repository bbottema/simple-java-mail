package org.simplejavamail.internal.util;

import org.simplejavamail.api.email.AttachmentResource;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

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
		return isSmimeContentType(attachment.getDataSource().getContentType());
	}

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted.
	 */
	public static boolean isSmimeContentType(@Nonnull final String contentType) {
		return SMIME_MIMETYPES.contains(contentType);
	}

	public static boolean isGeneratedSmimeMessageId(@Nonnull final Map.Entry headerEntry) {
		return headerEntry.getKey().equals("Message-ID") &&
				headerEntry.getValue().equals(SMIME_ATTACHMENT_MESSAGE_ID);
	}
}