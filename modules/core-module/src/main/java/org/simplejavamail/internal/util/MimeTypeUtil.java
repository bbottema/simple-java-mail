package org.simplejavamail.internal.util;

import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParameterList;
import jakarta.mail.internet.ParseException;
import org.jetbrains.annotations.Nullable;

import java.util.Enumeration;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Shared MIME metadata normalization for renamed sources and outgoing resource headers. */
public final class MimeTypeUtil {

	private MimeTypeUtil() { }

	/**
	 * Retains usable type parameters without allowing source header lines into the serialized value. Malformed parameters
	 * fall back to the usable base type; an unusable base type retains the established binary fallback. This never reads content.
	 */
	public static ContentType parseOrDefault(@Nullable final String supplied) {
		final String unfolded = supplied == null ? "" : MimeUtility.unfold(supplied);
		if (hasLineBreak(unfolded)) {
			return baseTypeOrDefault(supplied);
		}
		final ContentType parsed;
		try {
			parsed = new ContentType(unfolded);
		} catch (final ParseException e) {
			// Keep the usable base type, as for the existing malformed-type recovery.
			return baseTypeOrDefault(supplied);
		}
		final ParameterList parameters = new ParameterList();
		final ParameterList suppliedParameters = parsed.getParameterList();
		if (suppliedParameters != null) {
			final Enumeration<String> names = suppliedParameters.getNames();
			while (names.hasMoreElements()) {
				final String name = names.nextElement();
				final String value = suppliedParameters.get(name);
				if (value != null && !hasLineBreak(value)) {
					parameters.set(name, value, UTF_8.name());
				}
			}
		}
		return new ContentType(parsed.getPrimaryType(), parsed.getSubType(), parameters);
	}

	private static ContentType baseTypeOrDefault(@Nullable final String supplied) {
		try {
			final ContentType result = new ContentType(MiscUtil.parseBaseMimeType(supplied));
			result.setParameterList(new ParameterList());
			return result;
		} catch (final ParseException e) {
			return new ContentType("application", "octet-stream", new ParameterList());
		}
	}

	private static boolean hasLineBreak(final String value) {
		return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
	}
}
