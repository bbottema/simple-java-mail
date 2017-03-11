package org.simplejavamail.internal.util;

import javax.annotation.Nullable;
import javax.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.regex.Pattern;

import static java.lang.Integer.toHexString;

public final class MiscUtil {

	private static final Pattern MATCH_INSIDE_CIDBRACKETS = Pattern.compile("<?([^>]*)>?");

	public static <T> T checkNotNull(final T value, final String msg) {
		if (value == null) {
			throw new NullPointerException(msg);
		}
		return value;
	}

	public static <T> T checkArgumentNotEmpty(final T value, final String msg) {
		if (valueNullOrEmpty(value)) {
			throw new IllegalArgumentException(msg);
		}
		return value;
	}

	public static <T> boolean valueNullOrEmpty(final T value) {
		return value == null ||
				(value instanceof String && ((String) value).isEmpty()) ||
				(value instanceof Collection && ((Collection<?>) value).isEmpty()) ||
				(value instanceof byte[] && ((byte[]) value).length == 0);
	}

	public static String buildLogString(final byte[] bytes, final boolean isReceived) {
		final StringBuilder debugMsg = new StringBuilder();
		debugMsg.append(isReceived ? "Received: " : "Sent: ");
		for (final byte aByte : bytes) {
			debugMsg.append(toHexString(toInt(aByte))).append(" ");
		}
		return debugMsg.toString();
	}

	public static int toInt(final byte b) {
		return b & 0xFF;
	}


	/**
	 * To make sure email clients can interpret text properly, we need to encode some values according to RFC-2047.
	 */
	public static String encodeText(@Nullable final String name) {
		if (name == null) {
			return null;
		}
		try {
			return MimeUtility.encodeText(name);
		} catch (final UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}

	public static String extractCID(final String cid) {
		return (cid != null) ?  MATCH_INSIDE_CIDBRACKETS.matcher(cid).replaceAll("$1") : null;
	}
}
