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
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.config.EmailProperty;

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

	private static final SimpleJavaMailConfig EMPTY_CONFIG = ConfigLoader.builder().load();

	@Getter(AccessLevel.NONE)
	@NotNull private final SimpleJavaMailConfig config;
	@Getter(AccessLevel.NONE)
	@NotNull private final EmailStartingBuilder emailBuilder;

	// for internal convenience in junit tests
	public static EmailGovernance NO_GOVERNANCE() {
		return new EmailGovernanceImpl(EMPTY_CONFIG, new EmailStartingBuilderImpl(EMPTY_CONFIG), null, null, null, null, null, false);
	}

	/**
	 * Internal factory for completion routes that apply the snapshot captured by an email builder.
	 *
	 * @see org.simplejavamail.api.email.EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()
	 */
	public static EmailGovernance withConfig(@NotNull final SimpleJavaMailConfig config) {
		return new EmailGovernanceImpl(config, new EmailStartingBuilderImpl(config), null, null, null, null, null, false);
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
		this(EMPTY_CONFIG, new EmailStartingBuilderImpl(EMPTY_CONFIG), emailValidator, emailDefaults, emailOverrides, maximumEmailSize, null, false);
	}

	public EmailGovernanceImpl(@Nullable EmailValidator emailValidator, @Nullable Email emailDefaults, @Nullable Email emailOverrides, @Nullable Integer maximumEmailSize,
			@Nullable DkimConfig defaultDkimSigningConfig, boolean defaultDkimSigningConfigured) {
		this(EMPTY_CONFIG, new EmailStartingBuilderImpl(EMPTY_CONFIG), emailValidator, emailDefaults, emailOverrides, maximumEmailSize,
				defaultDkimSigningConfig, defaultDkimSigningConfigured);
	}

	EmailGovernanceImpl(@NotNull final SimpleJavaMailConfig config,
			@NotNull final EmailStartingBuilder emailBuilder,
			@Nullable final EmailValidator emailValidator,
			@Nullable final Email emailDefaults,
			@Nullable final Email emailOverrides,
			@Nullable final Integer maximumEmailSize,
			@Nullable final DkimConfig defaultDkimSigningConfig,
			final boolean defaultDkimSigningConfigured) {
		this.config = requireNonNull(config, "config");
		this.emailBuilder = requireNonNull(emailBuilder, "emailBuilder");
		this.emailValidator = emailValidator;
		this.emailDefaults = emailDefaults != null ? emailDefaults : newDefaultsEmailWithDefaultDefaults(defaultDkimSigningConfigured);
		this.emailOverrides = emailOverrides != null ? emailOverrides : emailBuilder.startingBlank().buildEmail();
		this.maximumEmailSize = maximumEmailSize;
		this.defaultDkimSigningConfig = defaultDkimSigningConfig;
		this.defaultDkimSigningConfigured = defaultDkimSigningConfigured;
	}

	// FIXME default notificationTo is missing
	// The name is a bit cryptic, but succinct (and it's only used internally)
	private Email newDefaultsEmailWithDefaultDefaults(final boolean suppressDkimSigningDefault) {
		final EmailPopulatingBuilder allDefaults = emailBuilder.startingBlank();
		final CliEmailRecipientBuilder recipientDefaults = (CliEmailRecipientBuilder) allDefaults;

		if (hasConfiguredProperty(DEFAULT_FROM_ADDRESS)) {
			allDefaults.from(configuredString(DEFAULT_FROM_NAME), verifyNonnullOrEmpty(configuredString(DEFAULT_FROM_ADDRESS)));
		}
		if (hasConfiguredProperty(DEFAULT_REPLYTO_ADDRESS)) {
			allDefaults.withReplyTo(configuredString(DEFAULT_REPLYTO_NAME), verifyNonnullOrEmpty(configuredString(DEFAULT_REPLYTO_ADDRESS)));
		}
		if (hasConfiguredProperty(DEFAULT_BOUNCETO_ADDRESS)) {
			allDefaults.withBounceTo(configuredString(DEFAULT_BOUNCETO_NAME), verifyNonnullOrEmpty(configuredString(DEFAULT_BOUNCETO_ADDRESS)));
		}
		if (hasConfiguredProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)) {
			allDefaults.withDeliveryStatusNotificationNotifyOptions(verifyNonnullOrEmpty(configuredString(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)));
		}
		if (hasConfiguredProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)) {
			allDefaults.withDeliveryStatusNotificationReturnOption(verifyNonnullOrEmpty(
					this.<DeliveryStatusNotification.ReturnOption>configuredProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)));
		}
		if (hasConfiguredProperty(DEFAULT_TO_ADDRESS)) {
			if (hasConfiguredProperty(DEFAULT_TO_NAME)) {
				recipientDefaults.withRecipients(configuredString(DEFAULT_TO_NAME), true, TO, configuredString(DEFAULT_TO_ADDRESS));
			} else {
				recipientDefaults.withRecipients(null, false, TO, verifyNonnullOrEmpty(configuredString(DEFAULT_TO_ADDRESS)));
			}
		}
		if (hasConfiguredProperty(DEFAULT_CC_ADDRESS)) {
			if (hasConfiguredProperty(DEFAULT_CC_NAME)) {
				recipientDefaults.withRecipients(configuredString(DEFAULT_CC_NAME), true, CC, configuredString(DEFAULT_CC_ADDRESS));
			} else {
				recipientDefaults.withRecipients(null, false, CC, verifyNonnullOrEmpty(configuredString(DEFAULT_CC_ADDRESS)));
			}
		}
		if (hasConfiguredProperty(DEFAULT_BCC_ADDRESS)) {
			if (hasConfiguredProperty(DEFAULT_BCC_NAME)) {
				recipientDefaults.withRecipients(configuredString(DEFAULT_BCC_NAME), true, BCC, configuredString(DEFAULT_BCC_ADDRESS));
			} else {
				recipientDefaults.withRecipients(null, false, BCC, verifyNonnullOrEmpty(configuredString(DEFAULT_BCC_ADDRESS)));
			}
		}
		if (hasConfiguredProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withContentTransferEncoding(verifyNonnullOrEmpty(configuredProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasConfiguredProperty(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withPlainTextContentTransferEncoding(verifyNonnullOrEmpty(configuredProperty(DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasConfiguredProperty(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withHTMLTextContentTransferEncoding(verifyNonnullOrEmpty(configuredProperty(DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasConfiguredProperty(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withCalendarTextContentTransferEncoding(verifyNonnullOrEmpty(configuredProperty(DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasConfiguredProperty(DEFAULT_SUBJECT)) {
			allDefaults.withSubject(configuredProperty(DEFAULT_SUBJECT));
		}

		if (allDefaults.getSmimeSignedEmail() == null && hasConfiguredProperty(SMIME_SIGNING_KEYSTORE)) {
			allDefaults.signWithSmime(SmimeSigningConfig.builder()
					.pkcs12Config(Pkcs12Config.builder()
							.pkcs12Store(verifyNonnullOrEmpty(configuredString(SMIME_SIGNING_KEYSTORE)))
							.storePassword(checkNonEmptyArgument(configuredString(SMIME_SIGNING_KEYSTORE_PASSWORD), "Keystore password property"))
							.keyAlias(checkNonEmptyArgument(configuredString(SMIME_SIGNING_KEY_ALIAS), "Key alias property"))
							.keyPassword(checkNonEmptyArgument(configuredString(SMIME_SIGNING_KEY_PASSWORD), "Key password property"))
							.build())
					.signatureAlgorithm(hasConfiguredProperty(SMIME_SIGNING_ALGORITHM) ? configuredString(SMIME_SIGNING_ALGORITHM) : null)
					.build());
		}
		if (allDefaults.getSmimeEncryptionConfig() == null && hasConfiguredProperty(SMIME_ENCRYPTION_CERTIFICATE)) {
			allDefaults.encryptWithSmime(SmimeEncryptionConfig.builder()
					.x509Certificate(verifyNonnullOrEmpty(configuredString(SMIME_ENCRYPTION_CERTIFICATE)))
					.keyEncapsulationAlgorithm(hasConfiguredProperty(SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM) ? configuredString(SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM) : null)
					.cipherAlgorithm(hasConfiguredProperty(SMIME_ENCRYPTION_CIPHER) ? configuredString(SMIME_ENCRYPTION_CIPHER) : null)
					.build());
		}
		if (!suppressDkimSigningDefault && allDefaults.getDkimConfig() == null && hasConfiguredProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA)) {
			val dkimConfigBuilder = DkimConfig.builder()
					.dkimSelector(verifyNonnullOrEmpty(configuredString(DKIM_SELECTOR)))
					.dkimSigningDomain(verifyNonnullOrEmpty(configuredString(DKIM_SIGNING_DOMAIN)))
					.useLengthParam(hasConfiguredProperty(DKIM_SIGNING_USE_LENGTH_PARAM) ? configuredBoolean(DKIM_SIGNING_USE_LENGTH_PARAM) : null)
					.excludedHeadersFromDkimDefaultSigningList(verifyNonnullOrEmpty(configuredString(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST)))
					.headerCanonicalization(hasConfiguredProperty(DKIM_SIGNING_HEADER_CANONICALIZATION) ? configuredProperty(DKIM_SIGNING_HEADER_CANONICALIZATION) : null)
					.bodyCanonicalization(hasConfiguredProperty(DKIM_SIGNING_BODY_CANONICALIZATION) ? configuredProperty(DKIM_SIGNING_BODY_CANONICALIZATION) : null)
					.signingAlgorithm(hasConfiguredProperty(DKIM_SIGNING_ALGORITHM) ? configuredString(DKIM_SIGNING_ALGORITHM) : null);
			val dkimPrivateKeyFileOrData = verifyNonnullOrEmpty(configuredString(DKIM_PRIVATE_KEY_FILE_OR_DATA));
			dkimConfigBuilder.dkimPrivateKeyData(DkimPrivateKeyPropertyResolver.resolve(dkimPrivateKeyFileOrData));
			allDefaults.signWithDomainKey(dkimConfigBuilder.build());
		}

		return allDefaults.buildEmail();
	}

	@NotNull
	public Email produceEmailApplyingDefaultsAndOverrides(@Nullable Email provided) {
		val builder = (provided == null || provided.getEmailToForward() == null)
				? emailBuilder.startingBlank()
				: emailBuilder.forwarding(provided.getEmailToForward());

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
		if (provided != null) {
			ofNullable(provided.getOpenPgpSigningConfig()).ifPresent(builder::signWithOpenPgp);
			ofNullable(provided.getOpenPgpEncryptionConfig()).ifPresent(builder::encryptWithOpenPgp);
		}
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

	private boolean hasConfiguredProperty(final Property property) {
		return config.hasProperty(property);
	}

	@Nullable
	private String configuredString(final Property property) {
		return config.getStringProperty(property);
	}

	@Nullable
	private Boolean configuredBoolean(final Property property) {
		return config.getBooleanProperty(property);
	}

	@Nullable
	private <T> T configuredProperty(final Property property) {
		return config.getProperty(property);
	}
}
