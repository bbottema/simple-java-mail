package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.internet.HeaderTokenizer;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.ParseException;
import org.jetbrains.annotations.Nullable;

import java.text.ParsePosition;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps Jakarta Mail's legacy date syntax without accepting calendar rollover or its unknown-zone UTC fallback.
 */
final class ImportedSentDateParser {

	private static final Pattern RECOGNIZED_ZONE = Pattern.compile("(?:[+-]([0-9]{2})([0-9]{2})|UT|GMT|[ECMP][SD]T)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern UTC_ZONE_ALIASES = Pattern.compile("(?<=\\s)(?:UTC|Z)(?=\\s*(?:\\(|$))", Pattern.CASE_INSENSITIVE);

	private ImportedSentDateParser() {
	}

	@Nullable
	static Date parse(@Nullable final String supplied) {
		if (supplied == null) {
			return null;
		}
		// These explicit UTC spellings previously happened to work through Jakarta Mail's missing-zone fallback.
		final String interpretable = UTC_ZONE_ALIASES.matcher(supplied).replaceAll("GMT");
		final ParsePosition position = new ParsePosition(0);
		final Date parsed = new CalendarCheckedMailDateFormat().parse(interpretable, position);
		return parsed != null && hasRecognizedZone(interpretable.substring(0, position.getIndex()))
				&& hasOnlyTrailingComments(interpretable.substring(position.getIndex())) ? parsed : null;
	}

	private static boolean hasRecognizedZone(final String parsedText) {
		final Matcher zone = RECOGNIZED_ZONE.matcher(parsedText);
		return zone.find() && (zone.group(1) == null
				|| Integer.parseInt(zone.group(1)) < 24 && Integer.parseInt(zone.group(2)) < 60);
	}

	private static boolean hasOnlyTrailingComments(final String remainder) {
		try {
			return new HeaderTokenizer(remainder, HeaderTokenizer.RFC822).next().getType() == HeaderTokenizer.Token.EOF;
		} catch (final ParseException malformedComment) {
			return false;
		}
	}

	/**
	 * MailDateFormat chooses its grammar through isLenient(), but computes dates through its calendar. Keep the existing
	 * obsolete-year/zone and whitespace grammar while requiring the supplied calendar fields to describe an actual date.
	 */
	private static final class CalendarCheckedMailDateFormat extends MailDateFormat {
		private static final long serialVersionUID = 1L;

		private CalendarCheckedMailDateFormat() {
			getCalendar().setLenient(false);
		}

		@Override
		public boolean isLenient() {
			return true;
		}
	}
}
