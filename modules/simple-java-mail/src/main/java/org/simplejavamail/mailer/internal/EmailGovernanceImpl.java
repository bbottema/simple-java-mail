package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.ToString;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.config.EmailProperty;

import java.io.File;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
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
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_ALGORITHM;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_BODY_CANONICALIZATION;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_HEADER_CANONICALIZATION;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_USE_LENGTH_PARAM;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CIPHER;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_ALGORITHM;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.getBooleanProperty;
import static org.simplejavamail.config.ConfigLoader.getProperty;
import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.internal.util.MiscUtil.overrideAndOrProvideAndOrDefaultCollection;
import static org.simplejavamail.internal.util.MiscUtil.overrideAndOrProvideAndOrDefaultHeaders;
import static org.simplejavamail.internal.util.MiscUtil.overrideOrProvideOrDefaultProperty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance. That is, this class represents actions
 * taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all emails
 * by default. You <em>can</em> do it manually on each email of course, but then the keystore used for this is not reused.
 * <p>
 * Also, you can supply a custom {@link Email email} instance which will be used for defaults or overrides. For example,
 * you can set a default from address or subject. Any fields that are not set on the email will be taken from the defaults (properties). Any fields that are set on the
 * email will be used instead of the defaults.
 */
@ToString
@Getter
public class EmailGovernanceImpl implements EmailGovernance {

	// for internal convenience in junit tests
	public static EmailGovernance NO_GOVERNANCE() {
		return new EmailGovernanceImpl(null, null, null, null);
	}

	/**
	 * The effective email validator used for email validation. Can be <code>null</code> if no validation should be done.
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 * @see EmailValidator
	 */
	@Nullable private final EmailValidator emailValidator;

	/**
	 * Reference email used for defaults if no fields are not filled in the email but are on this instance.
	 * Can be <code>null</code> if no defaults should be used.
	 * @see MailerGenericBuilder#withEmailDefaults(Email)
	 */
	@Getter(AccessLevel.NONE)
	@NotNull private final Email emailDefaults;

	/**
	 * Reference email used for overrides. Values from this email will trump the incoming email.
	 * Can be <code>null</code> if no overrides should be used.
	 * @see MailerGenericBuilder#withEmailOverrides(Email)
	 */
	@Getter(AccessLevel.NONE)
	@NotNull private final Email emailOverrides;

	/**
	 * Determines at what size Simple Java Mail should reject a MimeMessage. Useful if you know your SMTP server has a limit.
	 * @see MailerGenericBuilder#withMaximumEmailSize(int)
	 */
	@Nullable private final Integer maximumEmailSize;

	/**
	 * @see MailerGenericBuilder#withDefaultDkimSigning(DkimConfig)
	 */
	@Nullable private final DkimConfig defaultDkimSigningConfig;

	/**
	 * @see MailerGenericBuilder#clearDefaultDkimSigning()
	 */
	private final boolean defaultDkimSigningConfigured;

	public EmailGovernanceImpl(@Nullable EmailValidator emailValidator, @Nullable Email emailDefaults, @Nullable Email emailOverrides, @Nullable Integer maximumEmailSize) {
		this(emailValidator, emailDefaults, emailOverrides, maximumEmailSize, null, false);
	}

	public EmailGovernanceImpl(@Nullable EmailValidator emailValidator, @Nullable Email emailDefaults, @Nullable Email emailOverrides, @Nullable Integer maximumEmailSize,
			@Nullable DkimConfig defaultDkimSigningConfig, boolean defaultDkimSigningConfigured) {
		this.emailValidator = emailValidator;
		this.emailDefaults = emailDefaults != null ? emailDefaults : newDefaultsEmailWithDefaultDefaults(defaultDkimSigningConfigured);
		this.emailOverrides = emailOverrides != null ? emailOverrides : EmailBuilder.startingBlank().buildEmail();
		this.maximumEmailSize = maximumEmailSize;
		this.defaultDkimSigningConfig = defaultDkimSigningConfig;
		this.defaultDkimSigningConfigured = defaultDkimSigningConfigured;
	}

	// FIXME default notificationTo is missing
	// The name is a bit cryptic, but succinct (and it's only used internally)
	private Email newDefaultsEmailWithDefaultDefaults(final boolean suppressDkimSigningDefault) {
		final EmailPopulatingBuilder allDefaults = EmailBuilder.startingBlank();

		if (hasProperty(DEFAULT_FROM_ADDRESS)) {
			allDefaults.from(getStringProperty(DEFAULT_FROM_NAME), verifyNonnullOrEmpty(getStringProperty(DEFAULT_FROM_ADDRESS)));
		}
		if (hasProperty(DEFAULT_REPLYTO_ADDRESS)) {
			allDefaults.withReplyTo(getStringProperty(DEFAULT_REPLYTO_NAME), verifyNonnullOrEmpty(getStringProperty(DEFAULT_REPLYTO_ADDRESS)));
		}
		if (hasProperty(DEFAULT_BOUNCETO_ADDRESS)) {
			allDefaults.withBounceTo(getStringProperty(DEFAULT_BOUNCETO_NAME), verifyNonnullOrEmpty(getStringProperty(DEFAULT_BOUNCETO_ADDRESS)));
		}
		if (hasProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)) {
			allDefaults.withDeliveryStatusNotificationNotifyOptions(verifyNonnullOrEmpty(getStringProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)));
		}
		if (hasProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)) {
			allDefaults.withDeliveryStatusNotificationReturnOption(verifyNonnullOrEmpty(getStringProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)));
		}
		if (hasProperty(DEFAULT_TO_ADDRESS)) {
			if (hasProperty(DEFAULT_TO_NAME)) {
				allDefaults.withRecipients(getStringProperty(DEFAULT_TO_NAME), true, TO, getStringProperty(DEFAULT_TO_ADDRESS));
			} else {
				allDefaults.withRecipients(null, false, TO, verifyNonnullOrEmpty(getStringProperty(DEFAULT_TO_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_CC_ADDRESS)) {
			if (hasProperty(DEFAULT_CC_NAME)) {
				allDefaults.withRecipients(getStringProperty(DEFAULT_CC_NAME), true, CC, getStringProperty(DEFAULT_CC_ADDRESS));
			} else {
				allDefaults.withRecipients(null, false, CC, verifyNonnullOrEmpty(getStringProperty(DEFAULT_CC_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_BCC_ADDRESS)) {
			if (hasProperty(DEFAULT_BCC_NAME)) {
				allDefaults.withRecipients(getStringProperty(DEFAULT_BCC_NAME), true, BCC, getStringProperty(DEFAULT_BCC_ADDRESS));
			} else {
				allDefaults.withRecipients(null, false, BCC, verifyNonnullOrEmpty(getStringProperty(DEFAULT_BCC_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withContentTransferEncoding(verifyNonnullOrEmpty(getProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasProperty(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withPlainTextContentTransferEncoding(verifyNonnullOrEmpty(getProperty(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasProperty(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withHTMLTextContentTransferEncoding(verifyNonnullOrEmpty(getProperty(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasProperty(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withCalendarTextContentTransferEncoding(verifyNonnullOrEmpty(getProperty(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasProperty(DEFAULT_SUBJECT)) {
			allDefaults.withSubject(getProperty(DEFAULT_SUBJECT));
		}

		if (allDefaults.getSmimeSignedEmail() == null && hasProperty(SMIME_SIGNING_KEYSTORE)) {
			allDefaults.signWithSmime(SmimeSigningConfig.builder()
					.pkcs12Config(Pkcs12Config.builder()
							.pkcs12Store(verifyNonnullOrEmpty(getStringProperty(SMIME_SIGNING_KEYSTORE)))
							.storePassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEYSTORE_PASSWORD), "Keystore password property"))
							.keyAlias(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_ALIAS), "Key alias property"))
							.keyPassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_PASSWORD), "Key password property"))
							.build())
					.signatureAlgorithm(hasProperty(SMIME_SIGNING_ALGORITHM) ? getStringProperty(SMIME_SIGNING_ALGORITHM) : null)
					.build());
		}
		if (allDefaults.getSmimeEncryptionConfig() == null && hasProperty(SMIME_ENCRYPTION_CERTIFICATE)) {
			allDefaults.encryptWithSmime(SmimeEncryptionConfig.builder()
					.x509Certificate(verifyNonnullOrEmpty(getStringProperty(SMIME_ENCRYPTION_CERTIFICATE)))
					.keyEncapsulationAlgorithm(hasProperty(SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM) ? getStringProperty(SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM) : null)
					.cipherAlgorithm(hasProperty(SMIME_ENCRYPTION_CIPHER) ? getStringProperty(SMIME_ENCRYPTION_CIPHER) : null)
					.build());
		}
		if (!suppressDkimSigningDefault && allDefaults.getDkimConfig() == null && hasProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA)) {
			val dkimConfigBuilder = DkimConfig.builder()
					.dkimSelector(verifyNonnullOrEmpty(getStringProperty(DKIM_SELECTOR)))
					.dkimSigningDomain(verifyNonnullOrEmpty(getStringProperty(DKIM_SIGNING_DOMAIN)))
					.useLengthParam(hasProperty(DKIM_SIGNING_USE_LENGTH_PARAM) ? getBooleanProperty(DKIM_SIGNING_USE_LENGTH_PARAM) : null)
					.excludedHeadersFromDkimDefaultSigningList(verifyNonnullOrEmpty(getStringProperty(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST)))
					.headerCanonicalization(hasProperty(DKIM_SIGNING_HEADER_CANONICALIZATION) ? getProperty(DKIM_SIGNING_HEADER_CANONICALIZATION) : null)
					.bodyCanonicalization(hasProperty(DKIM_SIGNING_BODY_CANONICALIZATION) ? getProperty(DKIM_SIGNING_BODY_CANONICALIZATION) : null)
					.signingAlgorithm(hasProperty(DKIM_SIGNING_ALGORITHM) ? getStringProperty(DKIM_SIGNING_ALGORITHM) : null);
			val dkimPrivateKeyFileOrData = verifyNonnullOrEmpty(getStringProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA));
			if (new File(dkimPrivateKeyFileOrData).exists()) {
				dkimConfigBuilder.dkimPrivateKeyPath(dkimPrivateKeyFileOrData);
			} else {
				dkimConfigBuilder.dkimPrivateKeyData(dkimPrivateKeyFileOrData);
			}
			allDefaults.signWithDomainKey(dkimConfigBuilder.build());
		}

		return allDefaults.buildEmail();
	}

	@NotNull
	public Email produceEmailApplyingDefaultsAndOverrides(@Nullable Email provided) {
		val builder = (provided == null || provided.getEmailToForward() == null)
				? EmailBuilder.startingBlank()
				: EmailBuilder.forwarding(provided.getEmailToForward());

		final Recipient fromRecipient = resolveEmailProperty(provided, EmailProperty.FROM_RECIPIENT);
		final List<Recipient> replyToRecipients = resolveEmailCollectionProperty(provided, EmailProperty.REPLYTO_RECIPIENT);

		ofNullable(fromRecipient).ifPresent(builder::from);
		builder.withReplyTo(replyToRecipients);
		builder.withRecipients(resolveEmailCollectionProperty(provided, EmailProperty.TO_RECIPIENTS));
		builder.withRecipients(resolveEmailCollectionProperty(provided, EmailProperty.CC_RECIPIENTS));
		builder.withRecipients(resolveEmailCollectionProperty(provided, EmailProperty.BCC_RECIPIENTS));
		builder.withSubject(resolveEmailProperty(provided, EmailProperty.SUBJECT));
		builder.withPlainText(this.<String>resolveEmailProperty(provided, EmailProperty.BODY_TEXT));
		builder.withHTMLText(this.<String>resolveEmailProperty(provided, EmailProperty.BODY_HTML));
		val calendarText = this.<String>resolveEmailProperty(provided, EmailProperty.CALENDAR_TEXT);
		if (calendarText != null) {
			val calendarMethod = this.<CalendarMethod>resolveEmailProperty(provided, EmailProperty.CALENDAR_METHOD);
			builder.withCalendarText(requireNonNull(calendarMethod, "calendarMethod"), calendarText);
		}
		builder.withHeaders(resolveEmailHeadersProperty(provided));
		builder.withAttachments(resolveEmailCollectionProperty(provided, EmailProperty.ATTACHMENTS));
		builder.withEmbeddedImages(resolveEmailCollectionProperty(provided, EmailProperty.EMBEDDED_IMAGES));

		val useReturnReceiptTo = resolveEmailProperty(provided, EmailProperty.USE_RETURN_RECEIPT_TO);
		if (TRUE.equals(useReturnReceiptTo)) {
			Recipient returnReceiptToRecipient = resolveEmailProperty(provided, EmailProperty.RETURN_RECEIPT_TO);
			if (returnReceiptToRecipient != null) {
				builder.withReturnReceiptTo(returnReceiptToRecipient);
			} else if (!replyToRecipients.isEmpty()) {
				builder.withReturnReceiptTo(replyToRecipients.get(0));
			} else if (fromRecipient != null) {
				builder.withReturnReceiptTo(fromRecipient);
			} else {
				builder.withReturnReceiptTo();
			}
		}

		val useDispositionNotificationTo = resolveEmailProperty(provided, EmailProperty.USE_DISPOSITION_NOTIFICATION_TO);
		if (TRUE.equals(useDispositionNotificationTo)) {
			Recipient dispositionNotificationToRecipient = resolveEmailProperty(provided, EmailProperty.DISPOSITION_NOTIFICATION_TO);
			if (dispositionNotificationToRecipient != null) {
				builder.withDispositionNotificationTo(dispositionNotificationToRecipient);
			} else if (!replyToRecipients.isEmpty()) {
				builder.withDispositionNotificationTo(replyToRecipients.get(0));
			} else if (fromRecipient != null) {
				builder.withDispositionNotificationTo(fromRecipient);
			} else {
				builder.withDispositionNotificationTo();
			}
		}

		val overrideReceivers = this.<Recipient>resolveEmailCollectionProperty(provided, EmailProperty.OVERRIDE_RECEIVERS);
		if (!overrideReceivers.isEmpty()) {
			builder.withOverrideReceivers(overrideReceivers);
		}
		ofNullable(this.<ContentTransferEncoding>resolveEmailProperty(provided, EmailProperty.CONTENT_TRANSFER_ENCODING)).ifPresent(builder::withContentTransferEncoding);
		ofNullable(this.<ContentTransferEncoding>resolveEmailProperty(provided, EmailProperty.PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)).ifPresent(builder::withPlainTextContentTransferEncoding);
		ofNullable(this.<ContentTransferEncoding>resolveEmailProperty(provided, EmailProperty.HTML_TEXT_CONTENT_TRANSFER_ENCODING)).ifPresent(builder::withHTMLTextContentTransferEncoding);
		ofNullable(this.<ContentTransferEncoding>resolveEmailProperty(provided, EmailProperty.CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)).ifPresent(builder::withCalendarTextContentTransferEncoding);
		ofNullable(this.<SmimeSigningConfig>resolveEmailProperty(provided, EmailProperty.SMIME_SIGNING_CONFIG)).ifPresent(builder::signWithSmime);
		ofNullable(this.<SmimeEncryptionConfig>resolveEmailProperty(provided, EmailProperty.SMIME_ENCRYPTION_CONFIG)).ifPresent(builder::encryptWithSmime);
		ofNullable(resolveDkimConfig(provided)).ifPresent(builder::signWithDomainKey);
		builder.withBounceTo(this.<Recipient>resolveEmailProperty(provided, EmailProperty.BOUNCETO_RECIPIENT));
		ofNullable(this.<DeliveryStatusNotification>resolveEmailProperty(provided, EmailProperty.DELIVERY_STATUS_NOTIFICATION)).ifPresent(builder::withDeliveryStatusNotification);
		ofNullable(this.<Date>resolveEmailProperty(provided, EmailProperty.SENT_DATE)).ifPresent(builder::fixingSentDate);
		builder.fixingMessageId(resolveEmailProperty(provided, EmailProperty.ID));

		val email = builder.buildEmail();

		// we need to update the user's email instance with the generated ID when sending
		if (provided != null) {
			//noinspection deprecation
			((InternalEmail) email).setUserProvidedEmail(provided);
		}

		//noinspection deprecation
		((InternalEmail) email).markAsDefaultsAndOverridesApplied();
		return email;
	}

	@Nullable
	private <T> T resolveEmailProperty(@Nullable Email email, @NotNull EmailProperty emailProperty) {
		return overrideOrProvideOrDefaultProperty(email, emailDefaults, emailOverrides, emailProperty);
	}

	@Nullable
	private DkimConfig resolveDkimConfig(@Nullable Email email) {
		if (overrideAllowedForDkim(email) && emailOverrides.getDkimConfig() != null) {
			return emailOverrides.getDkimConfig();
		}
		if (email != null && email.getDkimConfig() != null) {
			return email.getDkimConfig();
		}
		if (defaultAllowedForDkim(email)) {
			return defaultDkimSigningConfigured ? defaultDkimSigningConfig : emailDefaults.getDkimConfig();
		}
		return null;
	}

	private static boolean defaultAllowedForDkim(@Nullable Email email) {
		return email == null || !email.isIgnoreDefaults() &&
				(email.getPropertiesNotToApplyDefaultValueFor() == null ||
						!email.getPropertiesNotToApplyDefaultValueFor().contains(EmailProperty.DKIM_SIGNING_CONFIG));
	}

	private static boolean overrideAllowedForDkim(@Nullable Email email) {
		return email == null || !email.isIgnoreOverrides() &&
				(email.getPropertiesNotToApplyOverrideValueFor() == null ||
						!email.getPropertiesNotToApplyOverrideValueFor().contains(EmailProperty.DKIM_SIGNING_CONFIG));
	}

	@NotNull
	private <T> List<T> resolveEmailCollectionProperty(@Nullable Email email, @NotNull EmailProperty emailProperty) {
		return overrideAndOrProvideAndOrDefaultCollection(email, emailDefaults, emailOverrides, emailProperty);
	}

	@NotNull
	private Map<String, Collection<String>> resolveEmailHeadersProperty(@Nullable Email email) {
		return overrideAndOrProvideAndOrDefaultHeaders(email, emailDefaults, emailOverrides);
	}
}
