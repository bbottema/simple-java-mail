package org.simplejavamail.internal.util;

import javax.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;

import static java.lang.Integer.toHexString;

public final class MiscUtil {

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
		return value == null || (value instanceof String && ((String) value).isEmpty());
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
	public static String encodeText(String name) {
		try {
			return MimeUtility.encodeText(name);
		} catch (UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
}
