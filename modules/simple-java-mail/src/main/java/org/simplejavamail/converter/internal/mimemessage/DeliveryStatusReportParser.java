package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.HeaderTokenizer;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimePart;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import lombok.Value;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.RecipientDeliveryReport;
import org.simplejavamail.api.email.DeliveryDiagnostic;
import org.simplejavamail.api.email.EnhancedStatusCode;
import org.simplejavamail.api.email.ReportedMailServer;
import org.simplejavamail.api.email.ReportedRecipient;
import org.simplejavamail.internal.email.EnhancedStatusCodeImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Interprets DSN header blocks after MIME decoding; Jakarta Mail still owns MIME and header parsing. */
@UtilityClass
class DeliveryStatusReportParser {

	private static final Pattern STATUS_CODE = Pattern.compile("[245]\\.[0-9]+\\.[0-9]+");
	private static final Pattern NUMERIC_TIMEZONE = Pattern.compile("[+-]([0-9]{2})[0-9]{2}$");

	/** Only the effective message's own report container is eligible, never a report attached to ordinary mail. */
	static boolean isReportContainer(final MimePart part) {
		try {
			final ContentType type = new ContentType(MimeMessageParser.retrieveContentType(part));
			final String reportType = type.getParameter("report-type");
			return type.match("multipart/report") && ("delivery-status".equalsIgnoreCase(reportType)
					|| "global-delivery-status".equalsIgnoreCase(reportType));
		} catch (final ParseException malformedType) {
			return false;
		}
	}

	static boolean isStatusPart(final MimePart part) {
		return MimeMessageParser.isMimeType(part, "message/delivery-status")
				|| MimeMessageParser.isMimeType(part, "message/global-delivery-status");
	}

	static DataSource decodedStatusSource(final MimePart part, final DataSource source) {
		// Jakarta Mail normally ignores transfer encoding on message/*; RFC 6533 explicitly permits it for global-delivery-status.
		// Read that part's raw stream through MimeUtility, without changing JVM properties or fetching it a second time.
		if (!MimeMessageParser.isMimeType(part, "message/global-delivery-status") || !(source instanceof MimePartDataSource)) {
			return source;
		}
		return new DataSource() {
			@Override
			public InputStream getInputStream() throws IOException {
				try {
					final InputStream raw = part instanceof MimeBodyPart ? ((MimeBodyPart) part).getRawInputStream()
							: ((MimeMessage) part).getRawInputStream();
					try {
						final String encoding = part.getEncoding();
						return encoding == null ? raw : MimeUtility.decode(raw, encoding);
					} catch (final MessagingException | RuntimeException decodingFailure) {
						try {
							raw.close();
						} catch (final IOException closeFailure) {
							decodingFailure.addSuppressed(closeFailure);
						}
						throw decodingFailure;
					}
				} catch (final MessagingException readFailure) {
					throw new IOException("Unable to decode the internationalized delivery-status part", readFailure);
				}
			}

			@Override public OutputStream getOutputStream() throws IOException { throw new IOException("Parsed delivery-status data is read-only"); }
			@Override public String getContentType() { return source.getContentType(); }
			@Override public String getName() { return source.getName(); }
		};
	}

	static DeliveryStatusReport unavailableReport(final String warning) {
		return DeliveryStatusReportImpl.builder()
				.recipients(List.of())
				.warnings(List.of(warning))
				.build();
	}

	/**
	 * A status body contains one per-message header block, a blank line, then one block per recipient. For example:
	 * <pre>Reporting-MTA: dns; relay.example.test
	 * Original-Envelope-Id: order-123
	 *
	 * Final-Recipient: rfc822; recipient@example.test
	 * Action: failed
	 * Status: 5.1.1</pre>
	 */
	static DeliveryStatusReport parse(final DataSource source, @Nullable final String containerWarning) {
		final Set<String> warnings = new LinkedHashSet<>();
		if (containerWarning != null) {
			warnings.add(containerWarning);
		}
		final List<RecipientDeliveryReport> recipients = new ArrayList<>();
		String envelopeId = null;
		ReportedMailServer reportingServer = null;
		try (PushbackInputStream input = new PushbackInputStream(source.getInputStream())) {
			final InternetHeaders messageFields = readBlock(input, warnings);
			envelopeId = singleValue(messageFields, "Original-Envelope-Id", false, warnings, UnaryOperator.identity());
			reportingServer = reportedServer(typedValue(messageFields, "Reporting-MTA", true, warnings));
			while (hasMoreContent(input)) {
				final InternetHeaders recipientFields = readBlock(input, warnings);
				if (recipientFields.getAllHeaders().hasMoreElements()) {
					recipients.add(parseRecipient(recipientFields, warnings));
				} else {
					warnings.add("The delivery report contains an empty recipient block.");
				}
			}
		} catch (final IOException | MessagingException readFailure) {
			if (Thread.currentThread().isInterrupted()) {
				throw new MimeMessageParseException("Reading the notification's delivery-status data was interrupted", readFailure);
			}
			// This optional interpretation must not lose the Email or completed blocks. Never turn an interrupted block into complete facts.
			warnings.add("Reading the delivery report failed; recovered facts may be incomplete. Only completed header blocks were interpreted.");
		}
		if (recipients.isEmpty()) {
			warnings.add("The delivery report contains no recipient facts.");
		}
		return DeliveryStatusReportImpl.builder()
				.envelopeId(envelopeId)
				.reportingServer(reportingServer)
				.recipients(recipients)
				.warnings(new ArrayList<>(warnings))
				.build();
	}

	private static InternetHeaders readBlock(final PushbackInputStream input, final Set<String> warnings) throws MessagingException {
		// RFC 6533 permits UTF-8 in status fields. Do not depend on the sending Session's mail.mime.allowutf8 setting.
		final InternetHeaders headers = new InternetHeaders(input, true);
		final boolean malformed = java.util.Collections.list(headers.getAllHeaderLines()).stream().anyMatch(line -> line.indexOf(':') <= 0);
		if (malformed) {
			warnings.add("The delivery report contains a malformed field line; only recognized usable fields were interpreted.");
		}
		return headers;
	}

	private static boolean hasMoreContent(final PushbackInputStream input) throws IOException {
		final int next = input.read();
		if (next == -1) {
			return false;
		}
		input.unread(next);
		return true;
	}

	private static RecipientDeliveryReport parseRecipient(final InternetHeaders fields, final Set<String> warnings) {
		final TypedValue original = typedValue(fields, "Original-Recipient", false, warnings);
		final TypedValue recipient = typedValue(fields, "Final-Recipient", true, warnings);
		final TypedValue diagnostic = typedValue(fields, "Diagnostic-Code", false, warnings);
		return RecipientDeliveryReportImpl.builder()
				.originalRecipient(reportedRecipient(original))
				.finalRecipient(reportedRecipient(recipient))
				.action(parseAction(fields, warnings))
				.statusCode(parseStatus(fields, warnings))
				.diagnostic(reportedDiagnostic(diagnostic))
				.lastAttemptAt(parseTime(fields, "Last-Attempt-Date", warnings))
				.retryUntil(parseTime(fields, "Will-Retry-Until", warnings))
				.build();
	}

	@Nullable
	private static ReportedMailServer reportedServer(final TypedValue supplied) {
		return supplied.value == null ? null : ReportedMailServerImpl.builder()
				.type(supplied.type)
				.name(supplied.value)
				.build();
	}

	@Nullable
	private static ReportedRecipient reportedRecipient(final TypedValue supplied) {
		return supplied.value == null ? null : ReportedRecipientImpl.builder()
				.type(supplied.type)
				.address(supplied.value)
				.build();
	}

	@Nullable
	private static DeliveryDiagnostic reportedDiagnostic(final TypedValue supplied) {
		return supplied.value == null ? null : DeliveryDiagnosticImpl.builder()
				.type(supplied.type)
				.text(supplied.value)
				.build();
	}

	@Nullable
	private static String singleValue(final InternetHeaders fields, final String name, final boolean required, final Set<String> warnings) {
		return singleValue(fields, name, required, warnings, String::trim);
	}

	@Nullable
	private static String singleValue(final InternetHeaders fields, final String name, final boolean required,
			final Set<String> warnings, final UnaryOperator<String> normalizeValue) {
		final String[] supplied = fields.getHeader(name);
		if (supplied == null) {
			if (required) {
				warnings.add("The delivery report is missing " + name + ".");
			}
			return null;
		}
		final Set<String> values = Arrays.stream(supplied).map(MimeUtility::unfold).map(normalizeValue).collect(Collectors.toSet());
		if (values.size() != 1 || values.contains("")) {
			warnings.add("The delivery report has empty or conflicting " + name + " fields; that fact was left unavailable.");
			return null;
		}
		if (supplied.length > 1) {
			warnings.add("The delivery report repeats " + name + "; identical values were interpreted once.");
		}
		return values.iterator().next();
	}

	private static TypedValue typedValue(final InternetHeaders fields, final String name, final boolean required, final Set<String> warnings) {
		final String supplied = singleValue(fields, name, required, warnings);
		if (supplied != null) {
			try {
				final HeaderTokenizer tokens = new HeaderTokenizer(supplied, HeaderTokenizer.RFC822);
				final HeaderTokenizer.Token type = tokens.next();
				if (type.getType() == HeaderTokenizer.Token.ATOM && tokens.next().getType() == ';') {
					final String remainder = tokens.getRemainder();
					if (remainder != null && !remainder.isBlank()) {
						return new TypedValue(type.getValue(), remainder.trim());
					}
				}
			} catch (final ParseException malformedField) {
				// HeaderTokenizer owns comments/quoting; malformed source text is unavailable, not an application configuration error.
			}
			warnings.add("The delivery report has an invalid " + name + " type/value pair; that fact was left unavailable.");
		}
		return new TypedValue(null, null);
	}

	private static DeliveryAction parseAction(final InternetHeaders fields, final Set<String> warnings) {
		final String supplied = singleValue(fields, "Action", true, warnings);
		if (supplied != null) {
			try {
				final HeaderTokenizer tokens = new HeaderTokenizer(supplied, HeaderTokenizer.RFC822);
				final HeaderTokenizer.Token token = tokens.next();
				if (token.getType() == HeaderTokenizer.Token.ATOM && tokens.next().getType() == HeaderTokenizer.Token.EOF) {
					final DeliveryAction action = DeliveryAction.valueOf(token.getValue().toUpperCase(Locale.ROOT));
					if (action != DeliveryAction.UNKNOWN) {
						return action;
					}
				}
			} catch (final ParseException | IllegalArgumentException unknownAction) {
				// Comments are not part of the action; malformed or competing tokens do not establish a source fact.
			}
			warnings.add("The delivery report has an unrecognized Action; it was left UNKNOWN.");
		}
		return DeliveryAction.UNKNOWN;
	}

	@Nullable
	private static EnhancedStatusCode parseStatus(final InternetHeaders fields, final Set<String> warnings) {
		final String supplied = singleValue(fields, "Status", true, warnings);
		if (supplied == null) {
			return null;
		}
		try {
			// RFC 3464 permits a trailing comment, but no whitespace/comments inside the numeric code.
			final HeaderTokenizer tokens = new HeaderTokenizer(supplied, HeaderTokenizer.MIME);
			final HeaderTokenizer.Token code = tokens.next();
			if (code.getType() == HeaderTokenizer.Token.ATOM && STATUS_CODE.matcher(code.getValue()).matches()
					&& tokens.next().getType() == HeaderTokenizer.Token.EOF) {
				final String[] components = code.getValue().split("\\.");
				final EnhancedStatusCode status = EnhancedStatusCodeImpl.builder()
						.statusClass(Integer.parseInt(components[0]))
						.subject(Integer.parseInt(components[1]))
						.detail(Integer.parseInt(components[2]))
						.build();
				if (!code.getValue().equals(status.toString())) {
					warnings.add("The delivery report uses a zero-padded Status code; its numeric meaning was recovered.");
				}
				return status;
			}
		} catch (final ParseException | IllegalArgumentException malformedStatus) {
			// Keep other recipient facts when comments are malformed or numeric components overflow/exceed their supported range.
		}
		warnings.add("The delivery report has an invalid Status code; that fact was left unavailable.");
		return null;
	}

	@Nullable
	private static Instant parseTime(final InternetHeaders fields, final String name, final Set<String> warnings) {
		final String supplied = singleValue(fields, name, false, warnings);
		if (supplied == null) {
			return null;
		}
		final MailDateFormat format = new MailDateFormat();
		format.setLenient(false);
		final ParsePosition position = new ParsePosition(0);
		final Date parsed = format.parse(supplied, position);
		if (parsed != null && hasValidTimezoneHours(supplied.substring(0, position.getIndex()))
				&& hasOnlyTrailingComments(supplied.substring(position.getIndex()))) {
			return parsed.toInstant();
		}
		try {
			// Some generators write ISO dates instead of mail dates. An explicit offset is unambiguous; calendar rollover is not.
			final Instant recovered = OffsetDateTime.parse(supplied).toInstant();
			warnings.add("The delivery report uses an ISO date for " + name + "; its timestamp was recovered.");
			return recovered;
		} catch (final DateTimeParseException malformedDate) {
			// Keep an impossible date or missing timezone unavailable rather than guessing another day or the local timezone.
		}
		warnings.add("The delivery report has an invalid " + name + " date; that fact was left unavailable.");
		return null;
	}

	private static boolean hasValidTimezoneHours(final String parsedText) {
		// MailDateFormat's strict grammar checks timezone minutes, but accepts any two-digit hour.
		final Matcher zone = NUMERIC_TIMEZONE.matcher(parsedText);
		return !zone.find() || Integer.parseInt(zone.group(1)) < 24;
	}

	private static boolean hasOnlyTrailingComments(final String remainder) {
		try {
			return new HeaderTokenizer(remainder, HeaderTokenizer.RFC822).next().getType() == HeaderTokenizer.Token.EOF;
		} catch (final ParseException malformedComment) {
			return false;
		}
	}

	@Value
	private static class TypedValue {
		@Nullable String type;
		@Nullable String value;
	}
}
