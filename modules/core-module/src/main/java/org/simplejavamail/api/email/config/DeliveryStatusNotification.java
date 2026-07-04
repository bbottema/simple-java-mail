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
import static java.util.Collections.unmodifiableSet;
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

	public enum NotifyOption {
		SUCCESS, FAILURE, DELAY, NEVER
	}

	public enum ReturnOption {
		FULL_MESSAGE("FULL"),
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

	private DeliveryStatusNotification(@Nullable final ReturnOption returnOption, @NotNull final Collection<NotifyOption> notifyOptions) {
		final Set<NotifyOption> normalizedNotifyOptions = new LinkedHashSet<>(notifyOptions);
		validate(returnOption, normalizedNotifyOptions);
		this.returnOption = returnOption;
		this.notifyOptions = unmodifiableSet(normalizedNotifyOptions);
	}

	public static DeliveryStatusNotification of(@NotNull final NotifyOption @NotNull ...notifyOptions) {
		return of(null, notifyOptions);
	}

	public static DeliveryStatusNotification of(@Nullable final ReturnOption returnOption, @NotNull final NotifyOption @NotNull ...notifyOptions) {
		return of(returnOption, asList(notifyOptions));
	}

	public static DeliveryStatusNotification of(@Nullable final ReturnOption returnOption, @NotNull final Collection<NotifyOption> notifyOptions) {
		return new DeliveryStatusNotification(returnOption, notifyOptions);
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
		validate(null, parsedNotifyOptions);
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

	private static void validate(@Nullable final ReturnOption returnOption, @NotNull final Set<NotifyOption> notifyOptions) {
		assumeTrue(returnOption != null || !notifyOptions.isEmpty(),
				"At least one delivery status notification option is required");
		assumeTrue(!notifyOptions.contains(null), "Delivery status notification options cannot contain null values");
		assumeTrue(!notifyOptions.contains(NotifyOption.NEVER) || notifyOptions.size() == 1,
				"NEVER cannot be combined with other delivery status notification options");
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

		public DeliveryStatusNotificationBuilder returnOption(@Nullable final ReturnOption returnOption) {
			this.returnOption = returnOption;
			return this;
		}

		public DeliveryStatusNotificationBuilder returnOption(@NotNull final String returnOption) {
			return returnOption(parseReturnOption(returnOption));
		}

		public DeliveryStatusNotificationBuilder notifyOptions(@NotNull final NotifyOption @NotNull ...notifyOptions) {
			this.notifyOptions.clear();
			this.notifyOptions.addAll(asList(notifyOptions));
			return this;
		}

		public DeliveryStatusNotificationBuilder notifyOptions(@NotNull final String notifyOptions) {
			this.notifyOptions.clear();
			this.notifyOptions.addAll(parseNotifyOptions(notifyOptions));
			return this;
		}

		public DeliveryStatusNotification build() {
			return new DeliveryStatusNotification(returnOption, notifyOptions);
		}
	}
}
