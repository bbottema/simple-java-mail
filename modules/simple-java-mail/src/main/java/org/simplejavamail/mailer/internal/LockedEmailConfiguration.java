package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetHeaders;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REQUIRE_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_NAME;

/**
 * Applies the factory's message restrictions after ordinary defaults and overrides. Suppression controls those templates, not these restrictions.
 * Recipient locks add required destinations; locks on single-value settings reject conflicting input instead of silently replacing it.
 */
final class LockedEmailConfiguration {

	// Several operational settings also use "defaults.". Only these defaults describe message content or its envelope.
	private static final Set<Property> MESSAGE_DEFAULTS = Set.of(DEFAULT_SUBJECT, DEFAULT_CONTENT_TRANSFER_ENCODING,
			DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING, DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING, DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING,
			DEFAULT_FROM_ADDRESS, DEFAULT_FROM_NAME, DEFAULT_REPLYTO_ADDRESS, DEFAULT_REPLYTO_NAME, DEFAULT_BOUNCETO_ADDRESS, DEFAULT_BOUNCETO_NAME,
			DEFAULT_TO_ADDRESS, DEFAULT_TO_NAME, DEFAULT_CC_ADDRESS, DEFAULT_CC_NAME, DEFAULT_BCC_ADDRESS, DEFAULT_BCC_NAME,
			DEFAULT_REQUIRE_TLS, DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY, DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION);

	private static final List<Property> READABLE_EXACT_HEADER_PROPERTIES = List.of(DEFAULT_SUBJECT,
			DEFAULT_FROM_ADDRESS, DEFAULT_FROM_NAME, DEFAULT_REPLYTO_ADDRESS, DEFAULT_REPLYTO_NAME);

	private final SimpleJavaMailConfig config;
	private final ConfigurationLocks locks;
	private final Email configuredDefaults;
	private final LockedMessageProtection protection;

	LockedEmailConfiguration(final SimpleJavaMailConfig config, final Email configuredDefaults) {
		this.config = config;
		this.locks = config.getLocks();
		this.configuredDefaults = configuredDefaults;
		this.protection = new LockedMessageProtection(config, configuredDefaults);
	}

	void apply(final EmailPopulatingBuilder target, final Email... inputs) {
		if (locks.isEmpty()) {
			return;
		}
		for (Email input : inputs) {
			if (input != null) {
				verifySingleValueSettings(input);
				protection.verify(input);
			}
		}
		applySingleValueSettings(target);
		applyRecipients(target);
		protection.apply(target);
	}

	private void verifySingleValueSettings(final Email email) {
		verifyIfPresent(DEFAULT_SUBJECT, email.getSubject());
		verifyIfPresent(DEFAULT_CONTENT_TRANSFER_ENCODING, email.getContentTransferEncoding());
		verifyIfPresent(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING, email.getPlainTextContentTransferEncoding());
		verifyIfPresent(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING, email.getHTMLTextContentTransferEncoding());
		verifyIfPresent(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING, email.getCalendarTextContentTransferEncoding());
		if (email.isTlsRequiredForOnwardDelivery()) {
			verifyIfPresent(DEFAULT_REQUIRE_TLS, true);
		}
		verifySender(email.getFromRecipient(), DEFAULT_FROM_ADDRESS, DEFAULT_FROM_NAME);
		verifySender(email.getBounceToRecipient(), DEFAULT_BOUNCETO_ADDRESS, DEFAULT_BOUNCETO_NAME);
		verifyDsn(email);
		verifyRawHeaders(email);
		for (Recipient recipient : email.getRecipients()) {
			final Property name = RecipientType.TO.equals(recipient.getType()) ? DEFAULT_TO_NAME
					: RecipientType.CC.equals(recipient.getType()) ? DEFAULT_CC_NAME : DEFAULT_BCC_NAME;
			verifyIfPresent(name, recipient.getName());
		}
		email.getReplyToRecipients().forEach(recipient -> verifyIfPresent(DEFAULT_REPLYTO_NAME, recipient.getName()));
	}

	private void verifyRawHeaders(final Email email) {
		for (String header : email.getHeaders().keySet()) {
			for (Property property : new Property[]{DEFAULT_SUBJECT, DEFAULT_FROM_ADDRESS, DEFAULT_FROM_NAME,
					DEFAULT_REPLYTO_ADDRESS, DEFAULT_REPLYTO_NAME, DEFAULT_TO_ADDRESS, DEFAULT_TO_NAME,
					DEFAULT_CC_ADDRESS, DEFAULT_CC_NAME, DEFAULT_BCC_ADDRESS, DEFAULT_BCC_NAME}) {
				if (locks.contains(property) && header.equalsIgnoreCase(headerFor(property))) {
					throw locks.conflict(property, "A manually supplied " + headerFor(property) + " header could bypass this factory's locked Email setting. "
							+ "Remove withHeader(\"" + headerFor(property) + "\", ...) and let the Email builder apply the locked value, "
							+ "or remove this lock from the configuration source.");
				}
			}
		}
	}

	private static String headerFor(final Property property) {
		switch (property) {
			case DEFAULT_SUBJECT: return "Subject";
			case DEFAULT_FROM_ADDRESS:
			case DEFAULT_FROM_NAME: return "From";
			case DEFAULT_REPLYTO_ADDRESS:
			case DEFAULT_REPLYTO_NAME: return "Reply-To";
			case DEFAULT_TO_ADDRESS:
			case DEFAULT_TO_NAME: return "To";
			case DEFAULT_CC_ADDRESS:
			case DEFAULT_CC_NAME: return "Cc";
			case DEFAULT_BCC_ADDRESS:
			case DEFAULT_BCC_NAME: return "Bcc";
			default: throw new IllegalArgumentException("Simple Java Mail has no internal header mapping for " + property.key()
					+ ". This is a library error, not a configuration value you can fix; please report it.");
		}
	}

	private void verifySender(@Nullable final Recipient recipient, final Property address, final Property name) {
		if (recipient != null) {
			verifyIfPresent(address, recipient.getAddress());
			verifyIfPresent(name, recipient.getName());
		}
	}

	private void verifyIfPresent(final Property property, @Nullable final Object value) {
		if (value != null) {
			locks.verify(property, value, "The Email, a recipient, or a defaults/overrides template supplies a value that differs from this factory's locked setting. "
					+ "Remove the conflicting field so the lock can supply it, use the locked value, or change this lock in its configuration source.");
		}
	}

	private void verifyDsn(final Email email) {
		final DeliveryStatusNotification dsn = email.getDeliveryStatusNotification();
		if (dsn != null) {
			verifyIfPresent(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION, dsn.getReturnOption());
			verifyNotifyOptions(dsn.getNotifyOptions());
		}
		// A recipient's NOTIFY preference normally overrides the Email fallback. It must agree with a locked fallback.
		email.getRecipients().forEach(recipient -> verifyNotifyOptions(recipient.getDeliveryStatusNotificationNotifyOptions()));
		email.getOverrideReceivers().forEach(recipient -> verifyNotifyOptions(recipient.getDeliveryStatusNotificationNotifyOptions()));
	}

	private void verifyNotifyOptions(final Set<DeliveryStatusNotification.NotifyOption> options) {
		if (locks.contains(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY) && !options.isEmpty()
				&& !options.equals(configuredDefaults.getDeliveryStatusNotification().getNotifyOptions())) {
			throw locks.conflict(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY,
					"The Email or a recipient requests different delivery-notification events from the locked set. "
							+ "Remove the conflicting notification preference to inherit the locked events, set it to the same events, "
							+ "or change this lock in its configuration source.");
		}
	}

	private void applySingleValueSettings(final EmailPopulatingBuilder target) {
		if (locks.contains(DEFAULT_SUBJECT)) {
			target.withSubject(config.getStringProperty(DEFAULT_SUBJECT));
		}
		if (locks.contains(DEFAULT_CONTENT_TRANSFER_ENCODING)) {
			target.withContentTransferEncoding(config.getProperty(DEFAULT_CONTENT_TRANSFER_ENCODING));
		}
		if (locks.contains(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)) {
			target.withPlainTextContentTransferEncoding(config.getProperty(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING));
		}
		if (locks.contains(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)) {
			target.withHTMLTextContentTransferEncoding(config.getProperty(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING));
		}
		if (locks.contains(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)) {
			target.withCalendarTextContentTransferEncoding(config.getProperty(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING));
		}
		if (locks.contains(DEFAULT_REQUIRE_TLS) && Boolean.TRUE.equals(config.getBooleanProperty(DEFAULT_REQUIRE_TLS))) {
			target.withTlsRequiredForOnwardDelivery();
		}
		if (locks.contains(DEFAULT_FROM_ADDRESS) || locks.contains(DEFAULT_FROM_NAME)) {
			target.from(lockedSender(target.getFromRecipient(), DEFAULT_FROM_ADDRESS, DEFAULT_FROM_NAME));
		}
		if (locks.contains(DEFAULT_BOUNCETO_ADDRESS) || locks.contains(DEFAULT_BOUNCETO_NAME)) {
			target.withBounceTo(lockedSender(target.getBounceToRecipient(), DEFAULT_BOUNCETO_ADDRESS, DEFAULT_BOUNCETO_NAME));
		}
		if (locks.contains(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)) {
			target.withDeliveryStatusNotificationNotifyOptions(configuredDefaults.getDeliveryStatusNotification().getNotifyOptions()
					.toArray(new DeliveryStatusNotification.NotifyOption[0]));
		}
		if (locks.contains(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)) {
			target.withDeliveryStatusNotificationReturnOption(config.getProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION));
		}
	}

	private Recipient lockedSender(@Nullable final Recipient supplied, final Property address, final Property name) {
		final String resolvedAddress = locks.contains(address) ? config.getStringProperty(address) : supplied == null ? null : supplied.getAddress();
		final String resolvedName = locks.contains(name) ? config.getStringProperty(name) : supplied == null ? null : supplied.getName();
		if (resolvedAddress == null) {
			throw locks.conflict(name, "This lock supplies a display name, but no corresponding email address was provided for " + address.key() + ". "
					+ "A name alone cannot identify a mailbox. Supply the address on the Email or in the configuration, or remove the display-name lock.");
		}
		return new Recipient(resolvedName, resolvedAddress, null, null);
	}

	private void applyRecipients(final EmailPopulatingBuilder target) {
		final List<Recipient> recipients = new ArrayList<>(target.getRecipients());
		includeRequired(recipients, configuredDefaults.getToRecipients(), DEFAULT_TO_ADDRESS);
		includeRequired(recipients, configuredDefaults.getCcRecipients(), DEFAULT_CC_ADDRESS);
		includeRequired(recipients, configuredDefaults.getBccRecipients(), DEFAULT_BCC_ADDRESS);
		applyNames(recipients, RecipientType.TO, DEFAULT_TO_NAME);
		applyNames(recipients, RecipientType.CC, DEFAULT_CC_NAME);
		applyNames(recipients, RecipientType.BCC, DEFAULT_BCC_NAME);
		target.clearRecipients().withRecipients(recipients);

		final List<Recipient> replyTo = new ArrayList<>(target.getReplyToRecipients());
		includeRequired(replyTo, configuredDefaults.getReplyToRecipients(), DEFAULT_REPLYTO_ADDRESS);
		applyNames(replyTo, null, DEFAULT_REPLYTO_NAME);
		target.clearReplyTo().withReplyTo(replyTo);

		if (!target.getOverrideReceivers().isEmpty()) {
			final List<Recipient> envelope = new ArrayList<>(target.getOverrideReceivers());
			includeRequired(envelope, configuredDefaults.getToRecipients(), DEFAULT_TO_ADDRESS);
			includeRequired(envelope, configuredDefaults.getCcRecipients(), DEFAULT_CC_ADDRESS);
			includeRequired(envelope, configuredDefaults.getBccRecipients(), DEFAULT_BCC_ADDRESS);
			target.clearOverrideReceivers().withOverrideReceivers(envelope);
		}
	}

	/** Keep existing occurrences. Add only missing required occurrences, so preparation remains idempotent. */
	private void includeRequired(final List<Recipient> target, final List<Recipient> required, final Property property) {
		if (!locks.contains(property)) {
			return;
		}
		final List<Recipient> unmatched = new ArrayList<>(target);
		for (Recipient recipient : required) {
			final Recipient existing = unmatched.stream().filter(candidate -> candidate.getAddress().equals(recipient.getAddress())).findFirst().orElse(null);
			if (existing == null) {
				target.add(recipient);
			} else {
				unmatched.remove(existing);
			}
		}
	}

	private void applyNames(final List<Recipient> recipients, @Nullable final RecipientType type, final Property property) {
		if (!locks.contains(property)) {
			return;
		}
		for (int index = 0; index < recipients.size(); index++) {
			final Recipient recipient = recipients.get(index);
			if (Objects.equals(type, recipient.getType())) {
				verifyIfPresent(property, recipient.getName());
				recipients.set(index, new Recipient(config.getStringProperty(property), recipient.getAddress(), type,
						recipient.getSmimeCertificate(), recipient.getDeliveryStatusNotificationNotifyOptions()));
			}
		}
	}

	/** Exact EML can gain envelope destinations, but its content and existing signatures are never rewritten to satisfy a lock. */
	void verifyExactCompatibility(final Email email) {
		verifyReadableExactHeaders(email);
		for (String key : locks.getValues().keySet()) {
			if (MESSAGE_DEFAULTS.stream().anyMatch(property -> property.key().equals(key))
					|| key.startsWith("simplejavamail.smime.") || key.startsWith("simplejavamail.dkim.")) {
				if (!isEnvelopeOnlyLock(key) && !matchesExactHeader(key, email)) {
					throw locks.conflict(key, "This factory requires a locked message setting that Simple Java Mail cannot apply or confirm for this exact EML "
							+ "without changing the preserved message. Exact EML keeps its original bytes, including any signatures. "
							+ "Use a composed Email so this setting can be applied, or remove this lock from the configuration used for exact messages.");
				}
			}
		}
	}

	/** The parsed Email getters expose one value; duplicate original headers cannot establish which value a recipient will see. */
	private void verifyReadableExactHeaders(final Email email) {
		final List<Property> headerLocks = READABLE_EXACT_HEADER_PROPERTIES.stream().filter(locks::contains).collect(toList());
		if (headerLocks.isEmpty()) {
			return;
		}
		final InternetHeaders originalHeaders;
		try {
			originalHeaders = InternalEmail.requireInternalEmail(email).readExactHeaders();
		} catch (MessagingException failure) {
			throw new IllegalArgumentException("This exact EML's original headers could not be read to check settings fixed by simplejavamail.locked.* properties. "
					+ "Supply exact EML with readable headers, use a composed Email, or remove the corresponding message locks from its configuration source.", failure);
		}
		for (Property property : headerLocks) {
			final String header = headerFor(property);
			final String[] occurrences = originalHeaders.getHeader(header);
			if (occurrences != null && occurrences.length > 1) {
				throw locks.conflict(property, "This exact EML contains more than one " + header + " header. "
						+ "Simple Java Mail cannot choose which value a mail client will use, and it will not rewrite preserved bytes to satisfy this lock. "
						+ "Supply exact EML with one " + header + " header that matches the locked setting, use a composed Email, "
						+ "or remove this lock from the configuration source.");
			}
		}
	}

	private boolean matchesExactHeader(final String key, final Email email) {
		if (key.equals(DEFAULT_SUBJECT.key())) {
			return Objects.equals(config.getStringProperty(DEFAULT_SUBJECT), email.getSubject());
		}
		if (key.equals(DEFAULT_FROM_ADDRESS.key())) {
			return email.getFromRecipient() != null && Objects.equals(config.getStringProperty(DEFAULT_FROM_ADDRESS), email.getFromRecipient().getAddress());
		}
		if (key.equals(DEFAULT_FROM_NAME.key())) {
			return email.getFromRecipient() != null && Objects.equals(config.getStringProperty(DEFAULT_FROM_NAME), email.getFromRecipient().getName());
		}
		if (key.equals(DEFAULT_REPLYTO_ADDRESS.key())) {
			return configuredDefaults.getReplyToRecipients().stream().allMatch(required -> email.getReplyToRecipients().stream()
					.anyMatch(actual -> actual.getAddress().equals(required.getAddress())));
		}
		if (key.equals(DEFAULT_REPLYTO_NAME.key())) {
			return !email.getReplyToRecipients().isEmpty() && email.getReplyToRecipients().stream()
					.allMatch(actual -> Objects.equals(config.getStringProperty(DEFAULT_REPLYTO_NAME), actual.getName()));
		}
		return false;
	}

	private boolean isEnvelopeOnlyLock(final String key) {
		return Arrays.asList(DEFAULT_TO_ADDRESS, DEFAULT_CC_ADDRESS, DEFAULT_BCC_ADDRESS, DEFAULT_BOUNCETO_ADDRESS, DEFAULT_BOUNCETO_NAME,
				DEFAULT_REQUIRE_TLS, DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY, DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)
				.stream().anyMatch(property -> property.key().equals(key));
	}
}
