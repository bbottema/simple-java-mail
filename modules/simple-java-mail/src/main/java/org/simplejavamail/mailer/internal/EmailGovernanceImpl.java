package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.internal.config.EmailProperty;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
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

	// FIXME get rid of all the default properties in Email and move them to EmailDefaults
	public EmailGovernanceImpl(@Nullable EmailValidator emailValidator, @Nullable Email emailDefaults, @Nullable Email emailOverrides, @Nullable Integer maximumEmailSize) {
		this.emailValidator = emailValidator;
		this.emailDefaults = completeDefaultsEmail(emailDefaults != null ? emailDefaults : EmailBuilder.startingBlank().buildEmail());
		// FIXME ignoringDefaults should go away as defaults are moved out of the Amil constructor
		this.emailOverrides = emailOverrides != null ? emailOverrides : EmailBuilder.ignoringDefaults().startingBlank().buildEmail();
		this.maximumEmailSize = maximumEmailSize;
	}

	private Email completeDefaultsEmail(@NotNull Email emailDefaults) {
		final EmailPopulatingBuilder allDefaults = EmailBuilder.copying(emailDefaults);

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
					.dkimPrivateKeyPath(verifyNonnullOrEmpty(getStringProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA)))
					.dkimSelector(verifyNonnullOrEmpty(getStringProperty(DKIM_SELECTOR)))
					.dkimSigningDomain(verifyNonnullOrEmpty(getStringProperty(DKIM_SIGNING_DOMAIN)))
					.excludedHeadersFromDkimDefaultSigningList(verifyNonnullOrEmpty(getStringProperty(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST)))
					.build());
		}

		return allDefaults.buildEmail();
	}

	@Override
	@Nullable
	public <T> T resolveEmailProperty(@Nullable Email email, @NotNull EmailProperty emailProperty) {
		return overrideOrProvideOrDefaultProperty(email, emailDefaults, emailOverrides, emailProperty);
	}

	@Override
	@NotNull
	public <T> List<T> resolveEmailCollectionProperty(@Nullable Email email, @NotNull EmailProperty emailProperty) {
		return overrideAndOrProvideAndOrDefaultCollection(email, emailDefaults, emailOverrides, emailProperty);
	}

	@Override
	@NotNull
	public Map<String, Collection<String>> resolveEmailHeadersProperty(@Nullable Email email) {
		return overrideAndOrProvideAndOrDefaultHeaders(email, emailDefaults, emailOverrides);
	}
}