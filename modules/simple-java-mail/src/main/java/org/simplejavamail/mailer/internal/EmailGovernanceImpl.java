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
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.config.EmailProperty;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static java.lang.Boolean.TRUE;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BCC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_BOUNCETO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CC_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TO_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
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

	public EmailGovernanceImpl(@Nullable EmailValidator emailValidator, @Nullable Email emailDefaults, @Nullable Email emailOverrides, @Nullable Integer maximumEmailSize) {
		this.emailValidator = emailValidator;
		this.emailDefaults = emailDefaults != null ? emailDefaults : newDefaultsEmailWithDefaultDefaults();
		this.emailOverrides = emailOverrides != null ? emailOverrides : EmailBuilder.startingBlank().buildEmail();
		this.maximumEmailSize = maximumEmailSize;
	}

	// The name is a bit cryptic, but succinct, and it's only used internally
	private Email newDefaultsEmailWithDefaultDefaults() {
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
		if (hasProperty(DEFAULT_TO_ADDRESS)) {
			if (hasProperty(DEFAULT_TO_NAME)) {
				allDefaults.to(getStringProperty(DEFAULT_TO_NAME), getStringProperty(DEFAULT_TO_ADDRESS));
			} else {
				allDefaults.to(verifyNonnullOrEmpty(getStringProperty(DEFAULT_TO_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_CC_ADDRESS)) {
			if (hasProperty(DEFAULT_CC_NAME)) {
				allDefaults.cc(getStringProperty(DEFAULT_CC_NAME), getStringProperty(DEFAULT_CC_ADDRESS));
			} else {
				allDefaults.cc(verifyNonnullOrEmpty(getStringProperty(DEFAULT_CC_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_BCC_ADDRESS)) {
			if (hasProperty(DEFAULT_BCC_NAME)) {
				allDefaults.bcc(getStringProperty(DEFAULT_BCC_NAME), getStringProperty(DEFAULT_BCC_ADDRESS));
			} else {
				allDefaults.bcc(verifyNonnullOrEmpty(getStringProperty(DEFAULT_BCC_ADDRESS)));
			}
		}
		if (hasProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)) {
			allDefaults.withContentTransferEncoding(verifyNonnullOrEmpty(getProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)));
		}
		if (hasProperty(DEFAULT_SUBJECT)) {
			allDefaults.withSubject(getProperty(DEFAULT_SUBJECT));
		}

		if (allDefaults.getPkcs12ConfigForSmimeSigning() == null && hasProperty(SMIME_SIGNING_KEYSTORE)) {
			allDefaults.signWithSmime(Pkcs12Config.builder()
					.pkcs12Store(verifyNonnullOrEmpty(getStringProperty(SMIME_SIGNING_KEYSTORE)))
					.storePassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEYSTORE_PASSWORD), "Keystore password property"))
					.keyAlias(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_ALIAS), "Key alias property"))
					.keyPassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_PASSWORD), "Key password property"))
					.build());
		}
		if (allDefaults.getX509CertificateForSmimeEncryption() == null && hasProperty(SMIME_ENCRYPTION_CERTIFICATE)) {
			allDefaults.encryptWithSmime(verifyNonnullOrEmpty(getStringProperty(SMIME_ENCRYPTION_CERTIFICATE)));
		}
		if (allDefaults.getDkimConfig() == null && hasProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA)) {
			allDefaults.signWithDomainKey(DkimConfig.builder()
					// FIXME try load as data as well if file doesn't exist
					.dkimPrivateKeyPath(verifyNonnullOrEmpty(getStringProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA)))
					.dkimSelector(verifyNonnullOrEmpty(getStringProperty(DKIM_SELECTOR)))
					.dkimSigningDomain(verifyNonnullOrEmpty(getStringProperty(DKIM_SIGNING_DOMAIN)))
					.excludedHeadersFromDkimDefaultSigningList(verifyNonnullOrEmpty(getStringProperty(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST)))
					.build());
		}

		return allDefaults.buildEmail();
	}

	@NotNull
	// FIXME junit test this using Email.equals()
	public EmailWithDefaultsAndOverridesApplied produceEmailApplyingDefaultsAndOverrides(@Nullable Email provided) {
		val builder = (provided == null || provided.getEmailToForward() == null)
				? EmailBuilder.startingBlank()
				: EmailBuilder.forwarding(provided.getEmailToForward());

		// replace above entire builder chain with seperate calls, so calls are not chained
		ofNullable(this.<Recipient>resolveEmailProperty(provided, EmailProperty.FROM_RECIPIENT)).ifPresent(builder::from);
		builder.to(resolveEmailCollectionProperty(provided, EmailProperty.TO_RECIPIENTS));
		builder.cc(resolveEmailCollectionProperty(provided, EmailProperty.CC_RECIPIENTS));
		builder.bcc(resolveEmailCollectionProperty(provided, EmailProperty.BCC_RECIPIENTS));
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
		val useDispositionNotificationTo = resolveEmailProperty(provided, EmailProperty.USE_DISPOSITION_NOTIFICATION_TO);
		if (TRUE.equals(useDispositionNotificationTo)) {
			final Recipient dispositionNotificationToRecipient = resolveEmailProperty(provided, EmailProperty.DISPOSITION_NOTIFICATION_TO);
			if (dispositionNotificationToRecipient != null) {
				builder.withDispositionNotificationTo(dispositionNotificationToRecipient);
			} else {
				builder.withDispositionNotificationTo();
			}
		}
		val useReturnReceiptTo = resolveEmailProperty(provided, EmailProperty.USE_RETURN_RECEIPT_TO);
		if (TRUE.equals(useReturnReceiptTo)) {
			final Recipient returnReceiptToRecipient = resolveEmailProperty(provided, EmailProperty.RETURN_RECEIPT_TO);
			if (returnReceiptToRecipient != null) {
				builder.withReturnReceiptTo(returnReceiptToRecipient);
			} else {
				builder.withReturnReceiptTo();
			}
		}
		builder.withReplyTo(this.<Recipient>resolveEmailProperty(provided, EmailProperty.REPLYTO_RECIPIENT));
		ofNullable(this.<ContentTransferEncoding>resolveEmailProperty(provided, EmailProperty.CONTENT_TRANSFER_ENCODING)).ifPresent(builder::withContentTransferEncoding);
		ofNullable(this.<Pkcs12Config>resolveEmailProperty(provided, EmailProperty.SMIME_SIGNING_CONFIG)).ifPresent(builder::signWithSmime);
		ofNullable(this.<X509Certificate>resolveEmailProperty(provided, EmailProperty.SMIME_ENCRYPTION_CONFIG)).ifPresent(builder::encryptWithSmime);
		ofNullable(this.<DkimConfig>resolveEmailProperty(provided, EmailProperty.DKIM_SIGNING_CONFIG)).ifPresent(builder::signWithDomainKey);
		builder.withBounceTo(this.<Recipient>resolveEmailProperty(provided, EmailProperty.BOUNCETO_RECIPIENT));
		ofNullable(this.<Date>resolveEmailProperty(provided, EmailProperty.SENT_DATE)).ifPresent(builder::fixingSentDate);
		builder.fixingMessageId(resolveEmailProperty(provided, EmailProperty.ID));

		val email = builder.buildEmail();
		if (provided != null) {
			//noinspection deprecation
			((InternalEmail) email).setUserProvidedEmail(provided);
		}

		return new EmailWithDefaultsAndOverridesApplied(email);
	}

	@Nullable
	private <T> T resolveEmailProperty(@Nullable Email email, @NotNull EmailProperty emailProperty) {
		return overrideOrProvideOrDefaultProperty(email, emailDefaults, emailOverrides, emailProperty);
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