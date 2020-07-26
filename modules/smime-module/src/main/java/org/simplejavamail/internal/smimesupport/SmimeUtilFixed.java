package org.simplejavamail.internal.smimesupport;

import net.markenwerk.utils.mail.smime.SmimeException;

import javax.mail.internet.ContentType;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimePart;

/**
 * The original SmimeUtil misses the case for enveloped S/MIME content (SIGNED_ENVELOPED).
 */
public final class SmimeUtilFixed {

	/**
	 * Returns the S/MIME state of a MIME part (i.e. MIME message).
	 *
	 * @param mimePart
	 *            The {@link MimePart} to be checked.
	 * @return the {@link SmimeStateFixed} of the {@link MimePart}.
	 */
	public static SmimeStateFixed getStatus(MimePart mimePart) {
		try {
			return getStatus(new ContentType(mimePart.getContentType()));
		} catch (Exception e) {
			throw handledException(e);
		}
	}

	private static SmimeStateFixed getStatus(ContentType contentType) {
		if (isSmimeSignatureContentType(contentType)) {
			return SmimeStateFixed.SIGNED;
		} else if (isSignatureSmimeType(contentType)) {
			return SmimeStateFixed.SIGNED_ENVELOPED;
		} else if (isSmimeEncryptionContenttype(contentType)) {
			return SmimeStateFixed.ENCRYPTED;
		} else {
			return SmimeStateFixed.NEITHER;
		}
	}

	private static boolean isSmimeEncryptionContenttype(ContentType contentType) {
		String baseContentType = contentType.getBaseType();
		return baseContentType.equalsIgnoreCase("application/pkcs7-mime")
				|| baseContentType.equalsIgnoreCase("application/x-pkcs7-mime");
	}

	private static boolean isSignatureSmimeType(ContentType contentType) {
		String baseContentType = contentType.getBaseType();
		return baseContentType.equalsIgnoreCase("application/x-pkcs7-mime")
				&& "signed-data".equals(contentType.getParameter("smime-type"));
	}

	private static boolean isSmimeSignatureContentType(ContentType contentType) {
		String baseContentType = contentType.getBaseType();
		return baseContentType.equalsIgnoreCase("multipart/signed")
				&& isSmimeSignatureProtocoll(contentType.getParameter("protocol"));
	}

	private static boolean isSmimeSignatureProtocoll(String protocol) {
		return protocol.equalsIgnoreCase("application/pkcs7-signature")
				|| protocol.equalsIgnoreCase("application/x-pkcs7-signature");
	}

	private static net.markenwerk.utils.mail.smime.SmimeException handledException(Exception e) {
		if (e instanceof net.markenwerk.utils.mail.smime.SmimeException) {
			return (net.markenwerk.utils.mail.smime.SmimeException) e;
		}
		return new SmimeException(e.getMessage(), e);
	}

	public enum SmimeStateFixed {

		/**
		 * Indicates that the {@link MimePart} or {@link MimeMultipart} is S/MIME
		 * encrypted.
		 */
		ENCRYPTED,

		/**
		 * Indicates that the {@link MimePart} or {@link MimeMultipart} is S/MIME
		 * signed.
		 */
		SIGNED,

		/**
		 * Indicates that the {@link MimePart} or {@link MimeMultipart} is S/MIME
		 * signed using an envelope (content is wrapped, probably as base64).
		 */
		SIGNED_ENVELOPED,

		/**
		 * Indicates that the {@link MimePart} or {@link MimeMultipart} is neither
		 * S/MIME encrypted nor S/MIME signed.
		 */
		NEITHER;
	}
}