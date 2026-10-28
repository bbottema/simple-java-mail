package org.simplejavamail.internal.smimesupport;

import jakarta.mail.internet.ContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.PLAIN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED;

public final class SmimeRecognitionUtil {

	/**
	 * Used internally to recognize when we should ignore message ID header when starting a new email as a copy.
	 */
	static final String SMIME_ATTACHMENT_MESSAGE_ID = "<generated-for-smime-signed-attachment@simple-java-mail>";

	/**
	 * @return Whether the given attachment is S/MIME signed / encrypted.
	 */
	static boolean isSmimeContentType(@NotNull final ContentType ct) {
		return isSmimeApplicationType(ct.getBaseType()) ||
				isSmimeMultiPartSigned(ct.getBaseType(), ct.getParameter("protocol"));
	}

	static <T> boolean isGeneratedSmimeMessageId(@NotNull String headerKey, @NotNull T headerValue) {
		return headerKey.equals("Message-ID") && headerValue.equals(SMIME_ATTACHMENT_MESSAGE_ID);
	}

	@NotNull
	public static SmimeMode determineSmimeMode(final OriginalSmimeDetails d) {
		boolean encrypted = isSmimeApplicationType(d.getSmimeMime()) && "enveloped-data".equals(d.getSmimeType());
		boolean signed = !encrypted &&
				(isSmimeApplicationType(d.getSmimeMime()) ||
						isSmimeMultiPartSigned(d.getSmimeMime(), d.getSmimeProtocol()) ||
						"signed-data".equals(d.getSmimeType()));

		return encrypted ? ENCRYPTED : signed ? SIGNED : PLAIN;
	}

	private static boolean isSmimeMultiPartSigned(@Nullable String mime, @Nullable String protocol) {
		return "multipart/signed".equalsIgnoreCase(mime) && (protocol == null ||
						protocol.equalsIgnoreCase("application/pkcs7-signature") || protocol.equalsIgnoreCase("application/x-pkcs7-signature"));
	}

	private static boolean isSmimeApplicationType(@Nullable final String mime) {
		return "application/pkcs7-mime".equalsIgnoreCase(mime) || "application/x-pkcs7-mime".equalsIgnoreCase(mime);
	}
}
