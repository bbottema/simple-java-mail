package org.simplejavamail.api.email.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.DsnNotifyOptions.copyOf;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Delivery Status Notification (DSN) settings used when sending the email over SMTP.
 *
 * @see EmailPopulatingBuilder#withDeliveryStatusNotification(DeliveryStatusNotification)
 */
@ToString
@Getter
@EqualsAndHashCode
public final class DeliveryStatusNotification implements Serializable {

	private static final long serialVersionUID = 1234567L;
	private static final String ENVELOPE_ID_PARAMETER_PREFIX = "ENVID=";
	private static final int MAX_ENVELOPE_ID_PARAMETER_LENGTH = 100;
	private static final int XTEXT_ESCAPE_LENGTH = 3;

	/**
	 * Which later delivery notifications to request. These are not the immediate SMTP submission result or read receipts.
	 * Requests depend on server support and do not guarantee that a notification will arrive.
	 */
	public enum NotifyOption {
		/** Request a notification when delivery succeeds. This does not mean the recipient has read the email. */
		SUCCESS,
		/** Request a notification when delivery fails. */
		FAILURE,
		/** Request a notification when delivery is delayed; the server may still deliver the email later. */
		DELAY,
		/** Request no delivery notifications. Use alone; omitting a preference instead permits inherited or server-default behavior. */
		NEVER
	}

	/** How much of the original email to include when a delivery-failure notification returns it. */
	public enum ReturnOption {
		/** Request the complete original email, including its content. */
		FULL_MESSAGE("FULL"),
		/** Request only the original headers, without the message content. */
		HEADERS_ONLY("HDRS");

		private final String smtpValue;

		ReturnOption(@NotNull final String smtpValue) {
			this.smtpValue = smtpValue;
		}

		public String getSmtpValue() {
			return smtpValue;
		}
	}

	@Nullable
	private final ReturnOption returnOption;

	@NotNull
	private final Set<NotifyOption> notifyOptions;

	/**
	 * The caller's fixed, unencoded SMTP transaction identifier, or {@code null} for automatic generation at send time.
	 * Generated identifiers are reported on the submission receipt, never stored back into this configuration value.
	 * This is separate from the MIME Message-ID; it can be returned as Original-Envelope-ID in a later DSN.
	 *
	 * @see EmailPopulatingBuilder#fixingEnvelopeId(String)
	 */
	@Nullable
	private final String envelopeId;

	private DeliveryStatusNotification(@Nullable final ReturnOption returnOption, @NotNull final Collection<NotifyOption> notifyOptions,
			@Nullable final String envelopeId) {
		final Set<NotifyOption> normalizedNotifyOptions = copyOf(notifyOptions);
		validate(returnOption, normalizedNotifyOptions, envelopeId);
		this.returnOption = returnOption;
		this.notifyOptions = normalizedNotifyOptions;
		this.envelopeId = envelopeId;
	}

	public static DeliveryStatusNotification of(@NotNull final NotifyOption @NotNull ...notifyOptions) {
		return of(null, notifyOptions);
	}

	public static DeliveryStatusNotification of(@Nullable final ReturnOption returnOption, @NotNull final NotifyOption @NotNull ...notifyOptions) {
		return of(returnOption, asList(notifyOptions));
	}

	public static DeliveryStatusNotification of(@Nullable final ReturnOption returnOption, @NotNull final Collection<NotifyOption> notifyOptions) {
		return new DeliveryStatusNotification(returnOption, notifyOptions, null);
	}

	/**
	 * Parses comma or semicolon separated DSN notify options. Valid values are {@code SUCCESS}, {@code FAILURE}, {@code DELAY}, and
	 * {@code NEVER}. {@code NEVER} cannot be combined with other values.
	 */
	public static Set<NotifyOption> parseNotifyOptions(@NotNull final String notifyOptions) {
		checkNonEmptyArgument(notifyOptions, "notifyOptions");
		final Set<NotifyOption> parsedNotifyOptions = new LinkedHashSet<>();
		for (String token : notifyOptions.split("[,;]")) {
			if (!valueNullOrEmpty(token)) {
				parsedNotifyOptions.add(parseNotifyOption(token));
			}
		}
		validate(null, copyOf(parsedNotifyOptions), null);
		return parsedNotifyOptions;
	}

	/**
	 * Parses a single DSN notify option.
	 */
	public static NotifyOption parseNotifyOption(@NotNull final String notifyOption) {
		return NotifyOption.valueOf(normalizeEnumToken(notifyOption)
				.replace("NOTIFY_", ""));
	}

	/**
	 * Parses a DSN return option. Accepts {@code FULL_MESSAGE}, {@code HEADERS_ONLY}, and their SMTP equivalents {@code FULL} and {@code HDRS}.
	 */
	public static ReturnOption parseReturnOption(@NotNull final String returnOption) {
		final String normalized = normalizeEnumToken(returnOption)
				.replace("RETURN_", "");
		if ("FULL".equals(normalized)) {
			return ReturnOption.FULL_MESSAGE;
		}
		if ("HDRS".equals(normalized) || "HEADERS".equals(normalized)) {
			return ReturnOption.HEADERS_ONLY;
		}
		return ReturnOption.valueOf(normalized);
	}

	public static DeliveryStatusNotificationBuilder builder() {
		return new DeliveryStatusNotificationBuilder();
	}

	/** Returns an independent builder retaining all options, including the caller-supplied envelope identifier. */
	@NotNull
	public DeliveryStatusNotificationBuilder toBuilder() {
		return builder().returnOption(returnOption).notifyOptions(notifyOptions.toArray(new NotifyOption[0])).envelopeId(envelopeId);
	}

	private static void validate(@Nullable final ReturnOption returnOption, @NotNull final Set<NotifyOption> notifyOptions,
			@Nullable final String envelopeId) {
		assumeTrue(returnOption != null || !notifyOptions.isEmpty() || envelopeId != null,
				"At least one delivery status notification option is required");
		validateEnvelopeId(envelopeId);
	}

	private static void validateEnvelopeId(@Nullable final String envelopeId) {
		if (envelopeId == null) {
			return;
		}
		assumeTrue(!envelopeId.isEmpty(), "The DSN envelope identifier cannot be empty. Supply an identifier or leave the envelope identifier unset.");
		validatePrintableAsciiEnvelopeId(envelopeId);
		assumeTrue(calculateEncodedEnvelopeIdParameterLength(envelopeId) <= MAX_ENVELOPE_ID_PARAMETER_LENGTH,
				"The DSN envelope identifier exceeds 94 characters after SMTP encoding (100 including ENVID=). "
						+ "Shorten it; spaces, + and = each use three characters.");
	}

	private static void validatePrintableAsciiEnvelopeId(@NotNull final String envelopeId) {
		for (int index = 0; index < envelopeId.length(); index++) {
			final char character = envelopeId.charAt(index);
			assumeTrue(character >= ' ' && character <= '~',
					"The DSN envelope identifier must contain printable ASCII only. Remove control characters or use an ASCII identifier.");
		}
	}

	/** RFC 3461 xtext expands space, plus and equals to three-character escapes on the SMTP command line. */
	private static int calculateEncodedEnvelopeIdParameterLength(@NotNull final String envelopeId) {
		int parameterLength = ENVELOPE_ID_PARAMETER_PREFIX.length();
		for (int index = 0; index < envelopeId.length(); index++) {
			parameterLength += requiresXtextEscape(envelopeId.charAt(index)) ? XTEXT_ESCAPE_LENGTH : 1;
		}
		return parameterLength;
	}

	private static boolean requiresXtextEscape(final char character) {
		return character == ' ' || character == '+' || character == '=';
	}

	private static String normalizeEnumToken(@NotNull final String token) {
		return checkNonEmptyArgument(token, "token")
				.trim()
				.replace('-', '_')
				.replace(' ', '_')
				.toUpperCase(Locale.ROOT);
	}

	@ToString
	public static class DeliveryStatusNotificationBuilder {
		@Nullable
		private ReturnOption returnOption;
		@NotNull
		private final Set<NotifyOption> notifyOptions = new LinkedHashSet<>();
		@Nullable
		private String envelopeId;

		/**
		 * Fixes the unencoded transaction identifier; {@code null} resumes automatic generation. Other DSN options are retained.
		 * The value is validated when {@link #build()} creates the immutable request.
		 *
		 * @see EmailPopulatingBuilder#fixingEnvelopeId(String)
		 */
		public DeliveryStatusNotificationBuilder envelopeId(@Nullable final String envelopeId) {
			this.envelopeId = envelopeId;
			return this;
		}

		/** Chooses how much of the original email a failure notification may return; {@code null} leaves the choice to the server. */
		public DeliveryStatusNotificationBuilder returnOption(@Nullable final ReturnOption returnOption) {
			this.returnOption = returnOption;
			return this;
		}

		/** Parses external text; Java callers should use {@link #returnOption(ReturnOption)} for discoverable choices. */
		public DeliveryStatusNotificationBuilder returnOption(@NotNull final String returnOption) {
			return returnOption(parseReturnOption(returnOption));
		}

		/** Replaces the requested notification events. {@link NotifyOption#NEVER} must stand alone; validation occurs at {@link #build()}. */
		public DeliveryStatusNotificationBuilder notifyOptions(@NotNull final NotifyOption @NotNull ...notifyOptions) {
			this.notifyOptions.clear();
			this.notifyOptions.addAll(asList(notifyOptions));
			return this;
		}

		/** Parses external text; Java callers should use {@link #notifyOptions(NotifyOption...)} for discoverable choices. */
		public DeliveryStatusNotificationBuilder notifyOptions(@NotNull final String notifyOptions) {
			this.notifyOptions.clear();
			this.notifyOptions.addAll(parseNotifyOptions(notifyOptions));
			return this;
		}

		public DeliveryStatusNotification build() {
			return new DeliveryStatusNotification(returnOption, notifyOptions, envelopeId);
		}
	}
}
