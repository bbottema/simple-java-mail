package org.simplejavamail.springsupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.ConfigSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts one Spring Environment to a Simple Java Mail configuration source without taking property precedence away from Spring.
 */
final class SpringEnvironmentConfigSource implements ConfigSource {

	private static final String EXTRA_PROPERTIES_PREFIX = "simplejavamail.extraproperties.";
	private static final String CONNECTIONPOOL_CLUSTERS_PREFIX = "simplejavamail.defaults.connectionpool.clusters.";

	private static final Map<String, String[]> COMPATIBILITY_ALIASES = compatibilityAliases();

	private final ConfigurableEnvironment environment;

	SpringEnvironmentConfigSource(@NotNull final ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	@Override
	public String getName() {
		return "Spring Environment";
	}

	@Override
	public Map<String, ?> getProperties() {
		final Map<String, Object> resolvedProperties = new LinkedHashMap<>();

		addScalarPropertiesTo(resolvedProperties);
		addWildcardPropertiesTo(resolvedProperties);

		return resolvedProperties;
	}

	@Override
	public String getPropertySourceName(final String propertyName) {
		final String underlyingSourceName = isWildcardPropertyName(propertyName)
				? findUnderlyingPropertySourceName(propertyName)
				: findScalarPropertySourceName(propertyName);
		return underlyingSourceName != null ? underlyingSourceName : getName();
	}

	private void addScalarPropertiesTo(final Map<String, Object> resolvedProperties) {
		for (Property property : Property.values()) {
			if (isWildcardProperty(property)) {
				continue;
			}
			final String resolvedValue = resolveScalarProperty(property.key());
			if (resolvedValue != null) {
				resolvedProperties.put(property.key(), resolvedValue);
			}
		}
	}

	private static boolean isWildcardProperty(final Property property) {
		return property == Property.EXTRA_PROPERTIES || property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
	}

	@Nullable
	private String resolveScalarProperty(final String canonicalKey) {
		for (String lookupName : lookupNamesFor(canonicalKey)) {
			final String value = environment.getProperty(lookupName);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private void addWildcardPropertiesTo(final Map<String, Object> resolvedProperties) {
		for (PropertySource<?> propertySource : environment.getPropertySources()) {
			if (!isBootConfigurationPropertiesAggregator(propertySource) && propertySource instanceof EnumerablePropertySource) {
				addEnumerableWildcardPropertiesTo((EnumerablePropertySource<?>) propertySource, resolvedProperties);
			}
		}
	}

	private void addEnumerableWildcardPropertiesTo(final EnumerablePropertySource<?> propertySource,
			final Map<String, Object> resolvedProperties) {
		for (String propertyName : propertySource.getPropertyNames()) {
			if (isWildcardPropertyName(propertyName)) {
				final String resolvedValue = environment.getProperty(propertyName);
				if (resolvedValue != null) {
					resolvedProperties.put(propertyName, resolvedValue);
				}
			}
		}
	}

	private static boolean isWildcardPropertyName(final String propertyName) {
		return propertyName.startsWith(EXTRA_PROPERTIES_PREFIX) || propertyName.startsWith(CONNECTIONPOOL_CLUSTERS_PREFIX);
	}

	private static String[] aliasesFor(final String canonicalKey) {
		final String[] explicitAliases = COMPATIBILITY_ALIASES.get(canonicalKey);
		if (explicitAliases != null) {
			return explicitAliases;
		}
		if (canonicalKey.indexOf('_') >= 0) {
			return new String[] { canonicalKey.replace('_', '-') };
		}
		return new String[0];
	}

	private static String[] lookupNamesFor(final String canonicalKey) {
		final String[] aliases = aliasesFor(canonicalKey);
		final String[] lookupNames = new String[aliases.length + 1];
		lookupNames[0] = canonicalKey;
		System.arraycopy(aliases, 0, lookupNames, 1, aliases.length);
		return lookupNames;
	}

	@Nullable
	private String findScalarPropertySourceName(final String canonicalKey) {
		for (String lookupName : lookupNamesFor(canonicalKey)) {
			final String sourceName = findUnderlyingPropertySourceName(lookupName);
			if (sourceName != null) {
				return sourceName;
			}
		}
		return null;
	}

	/**
	 * Spring Boot places a synthetic aggregate first in the Environment. It resolves relaxed names but hides the real source, so provenance is located in
	 * the underlying Spring sources and only applies relaxed-name matching when that aggregate is present.
	 */
	@Nullable
	private String findUnderlyingPropertySourceName(final String propertyName) {
		final boolean bootRelaxedNamesAreAvailable = hasBootConfigurationPropertiesAggregator();
		for (PropertySource<?> propertySource : environment.getPropertySources()) {
			if (isBootConfigurationPropertiesAggregator(propertySource)) {
				continue;
			}
			if (propertySource.getProperty(propertyName) != null) {
				return propertySource.getName();
			}
			if (bootRelaxedNamesAreAvailable
					&& propertySource instanceof EnumerablePropertySource
					&& containsRelaxedPropertyName((EnumerablePropertySource<?>) propertySource, propertyName)) {
				return propertySource.getName();
			}
		}
		return null;
	}

	private boolean hasBootConfigurationPropertiesAggregator() {
		for (PropertySource<?> propertySource : environment.getPropertySources()) {
			if (isBootConfigurationPropertiesAggregator(propertySource)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsRelaxedPropertyName(final EnumerablePropertySource<?> propertySource,
			final String requestedPropertyName) {
		final String normalizedRequestedName = normalizePropertyName(requestedPropertyName);
		for (String candidatePropertyName : propertySource.getPropertyNames()) {
			if (normalizedRequestedName.equals(normalizePropertyName(candidatePropertyName))
					&& propertySource.getProperty(candidatePropertyName) != null) {
				return true;
			}
		}
		return false;
	}

	private static String normalizePropertyName(final String propertyName) {
		final StringBuilder normalizedName = new StringBuilder(propertyName.length());
		for (int characterIndex = 0; characterIndex < propertyName.length(); characterIndex++) {
			final char character = propertyName.charAt(characterIndex);
			if (Character.isLetterOrDigit(character)) {
				normalizedName.append(Character.toLowerCase(character));
			}
		}
		return normalizedName.toString();
	}

	private static boolean isBootConfigurationPropertiesAggregator(final PropertySource<?> propertySource) {
		return "configurationProperties".equals(propertySource.getName())
				&& "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource"
				.equals(propertySource.getClass().getName());
	}

	private static Map<String, String[]> compatibilityAliases() {
		final Map<String, String[]> aliases = new LinkedHashMap<>();
		aliases.put(Property.JAVAXMAIL_DEBUG_OUTPUT.key(), new String[] { "simplejavamail.javaxmail.debug-out" });
		aliases.put(Property.CUSTOM_SSLFACTORY_CLASS.key(), new String[] { "simplejavamail.custom.sslfactory.clazz" });
		aliases.put(Property.DEFAULT_POOL_KEEP_ALIVE_TIME.key(), new String[] { "simplejavamail.defaults.poolsize-more.keepalivetime" });
		aliases.put(Property.SMIME_SIGNING_KEYSTORE_PASSWORD.key(), new String[] { "simplejavamail.smime.signing.keystore-password" });
		aliases.put(Property.SMIME_SIGNING_KEY_ALIAS.key(), new String[] { "simplejavamail.smime.signing.key-alias" });
		aliases.put(Property.SMIME_SIGNING_KEY_PASSWORD.key(), new String[] { "simplejavamail.smime.signing.key-password" });
		aliases.put(Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), new String[] { "simplejavamail.dkim.signing.private-key-file-or-data" });
		aliases.put(Property.DKIM_SIGNING_DOMAIN.key(), new String[] { "simplejavamail.dkim.signing.signing-domain" });
		aliases.put(Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST.key(),
				new String[] { "simplejavamail.dkim.signing.excluded-headers-from-default-signing-list" });
		return Collections.unmodifiableMap(aliases);
	}
}
