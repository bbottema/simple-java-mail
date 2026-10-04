package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

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
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;

/** Restricts individual signing/encryption fields without freezing unrelated choices in the same configuration object. */
final class LockedMessageProtection {

	private final SimpleJavaMailConfig config;
	private final ConfigurationLocks locks;
	private final Email configuredDefaults;

	LockedMessageProtection(final SimpleJavaMailConfig config, final Email configuredDefaults) {
		this.config = config;
		this.locks = config.getLocks();
		this.configuredDefaults = configuredDefaults;
	}

	void verify(final Email email) {
		if (hasLocks("dkim.") && email.getDkimConfig() != null) {
			resolveDkim(email.getDkimConfig());
		}
		if (hasLocks("smime.signing.") && email.getSmimeSigningConfig() != null) {
			resolveSigning(email.getSmimeSigningConfig());
		}
		if (hasLocks("smime.encryption.") && email.getSmimeEncryptionConfig() != null) {
			resolveEncryption(email.getSmimeEncryptionConfig());
		}
		if (locks.contains(SMIME_ENCRYPTION_CERTIFICATE)) {
			Stream.concat(email.getRecipients().stream(), email.getOverrideReceivers().stream()).forEach(recipient ->
					lockedValue(SMIME_ENCRYPTION_CERTIFICATE, recipient.getSmimeCertificate(),
							configuredDefaults.getSmimeEncryptionConfig().getX509Certificate()));
		}
	}

	void apply(final EmailPopulatingBuilder target) {
		if (hasLocks("dkim.")) {
			target.signWithDomainKey(resolveDkim(requiredConfiguration(target.getDkimConfig(), configuredDefaults.getDkimConfig(), "dkim.")));
		}
		if (hasLocks("smime.signing.")) {
			target.signWithSmime(resolveSigning(requiredConfiguration(target.getSmimeSigningConfig(),
					configuredDefaults.getSmimeSigningConfig(), "smime.signing.")));
		}
		if (hasLocks("smime.encryption.")) {
			target.encryptWithSmime(resolveEncryption(requiredConfiguration(target.getSmimeEncryptionConfig(),
					configuredDefaults.getSmimeEncryptionConfig(), "smime.encryption.")));
		}
	}

	private boolean hasLocks(final String tail) {
		return locks.getValues().keySet().stream().anyMatch(key -> key.startsWith("simplejavamail." + tail));
	}

	private <T> T requiredConfiguration(@Nullable final T supplied, @Nullable final T fallback, final String tail) {
		if (supplied != null || fallback != null) {
			return supplied != null ? supplied : fallback;
		}
		final String key = locks.getValues().keySet().stream().filter(candidate -> candidate.startsWith("simplejavamail." + tail)).findFirst().get();
		throw locks.conflict(key, "This factory locks a signing or encryption option, "
				+ "but the Email and its configured defaults contain no corresponding protection configuration. "
				+ "An algorithm or password alone does not provide the missing key or certificate. "
				+ "Supply the matching signing/encryption configuration on the Email or in its defaults, or remove this incomplete lock from the configuration source.");
	}

	private DkimConfig resolveDkim(final DkimConfig supplied) {
		final DkimConfig defaults = configuredDefaults.getDkimConfig();
		return DkimConfig.builder()
				.dkimPrivateKeyData(lockedValue(DKIM_PRIVATE_KEY_FILE_OR_DATA, supplied.getDkimPrivateKeyData(),
						defaults == null ? null : defaults.getDkimPrivateKeyData()))
				.dkimSigningDomain(configuredValue(DKIM_SIGNING_DOMAIN, supplied.getDkimSigningDomain()))
				.dkimSelector(configuredValue(DKIM_SELECTOR, supplied.getDkimSelector()))
				.useLengthParam(configuredValue(DKIM_SIGNING_USE_LENGTH_PARAM, supplied.getUseLengthParam()))
				.excludedHeadersFromDkimDefaultSigningList(lockedValue(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST,
						supplied.getExcludedHeadersFromDkimDefaultSigningList(), configuredExcludedHeaders()))
				.headerCanonicalization(configuredValue(DKIM_SIGNING_HEADER_CANONICALIZATION, supplied.getHeaderCanonicalization()))
				.bodyCanonicalization(configuredValue(DKIM_SIGNING_BODY_CANONICALIZATION, supplied.getBodyCanonicalization()))
				.signingAlgorithm(configuredValue(DKIM_SIGNING_ALGORITHM, supplied.getSigningAlgorithm()))
				.build();
	}

	@Nullable
	private Set<String> configuredExcludedHeaders() {
		final String configured = config.getStringProperty(DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST);
		return configured == null ? null : Set.of(configured);
	}

	private SmimeSigningConfig resolveSigning(final SmimeSigningConfig supplied) {
		final Pkcs12Config key = supplied.getPkcs12Config();
		final SmimeSigningConfig defaults = configuredDefaults.getSmimeSigningConfig();
		final Pkcs12Config resolvedKey = Pkcs12Config.builder()
				.pkcs12Store(lockedValue(SMIME_SIGNING_KEYSTORE, key.getPkcs12StoreData(),
						defaults == null ? null : defaults.getPkcs12Config().getPkcs12StoreData()))
				.storePassword(lockedValue(SMIME_SIGNING_KEYSTORE_PASSWORD, key.getStorePassword(), configuredPassword(SMIME_SIGNING_KEYSTORE_PASSWORD)))
				.keyAlias(configuredValue(SMIME_SIGNING_KEY_ALIAS, key.getKeyAlias()))
				.keyPassword(lockedValue(SMIME_SIGNING_KEY_PASSWORD, key.getKeyPassword(), configuredPassword(SMIME_SIGNING_KEY_PASSWORD)))
				.build();
		return new SmimeSigningConfig(resolvedKey, configuredValue(SMIME_SIGNING_ALGORITHM, supplied.getSignatureAlgorithm()));
	}

	@Nullable
	private char[] configuredPassword(final Property property) {
		final String value = config.getStringProperty(property);
		return value == null ? null : value.toCharArray();
	}

	private SmimeEncryptionConfig resolveEncryption(final SmimeEncryptionConfig supplied) {
		final SmimeEncryptionConfig defaults = configuredDefaults.getSmimeEncryptionConfig();
		return new SmimeEncryptionConfig(lockedValue(SMIME_ENCRYPTION_CERTIFICATE, supplied.getX509Certificate(),
				defaults == null ? null : defaults.getX509Certificate()),
				configuredValue(SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM, supplied.getKeyEncapsulationAlgorithm()),
				configuredValue(SMIME_ENCRYPTION_CIPHER, supplied.getCipherAlgorithm()));
	}

	@Nullable
	private <T> T configuredValue(final Property property, @Nullable final T supplied) {
		return lockedValue(property, supplied, config.getProperty(property));
	}

	@Nullable
	private <T> T lockedValue(final Property property, @Nullable final T supplied, @Nullable final T configured) {
		if (!locks.contains(property)) {
			return supplied;
		}
		if (supplied != null && !Objects.deepEquals(supplied, configured)) {
			throw locks.conflict(property, "The Email or a recipient supplies a signing/encryption value that differs from this locked field. "
					+ "Remove the conflicting protection customization, use the locked value, or change this lock in its configuration source.");
		}
		return configured;
	}
}
