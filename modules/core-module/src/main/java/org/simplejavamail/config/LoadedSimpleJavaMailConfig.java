package org.simplejavamail.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.config.ConfigurationSnapshot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * An immutable, thread-safe snapshot of resolved Simple Java Mail configuration.
 * <p>
 * A snapshot contains only explicitly configured values. Runtime defaults such as generated cluster identifiers remain builder concerns. All input and
 * wildcard maps are detached during construction and all returned maps are unmodifiable.
 * <p>
 * The typed property getters and {@link #asMap()} return actual configured values and may therefore expose credentials. Use {@link #getDiagnostics()} for
 * structured output intended for diagnostic logs.
 * <p>
 * Properties under {@code simplejavamail.locked.} supply their ordinary counterpart's effective value and remain restrictions on later customization
 * in every Mailer created from this snapshot's factory. Diagnostics retain the locked name and its source. An independently configured factory remains
 * possible; these local restrictions are not a security sandbox.
 */
final class LoadedSimpleJavaMailConfig implements ConfigurationSnapshot {

	private final Map<ConfigLoader.Property, Object> values;
	private final Map<ConfigLoader.Property, String> propertySources;
	// Retains concrete wildcard aliases because their aggregate values are keyed by the resolved cluster UUID instead.
	private final Map<String, Object> resolvedSourceProperties;
	private final ConfigDiagnostics diagnostics;
	private final ConfigurationLocks locks;

	LoadedSimpleJavaMailConfig(@NotNull final Map<ConfigLoader.Property, Object> values,
			@NotNull final Map<ConfigLoader.Property, String> propertySources,
			@NotNull final Map<String, Object> resolvedSourceProperties,
			@NotNull final ConfigDiagnostics diagnostics, @NotNull final ConfigurationLocks locks) {
		final Map<ConfigLoader.Property, Object> detachedValues = new EnumMap<>(ConfigLoader.Property.class);
		for (Map.Entry<ConfigLoader.Property, Object> entry : values.entrySet()) {
			detachedValues.put(entry.getKey(), detach(entry.getKey(), entry.getValue()));
		}
		this.values = Collections.unmodifiableMap(detachedValues);
		final Map<ConfigLoader.Property, String> detachedSources = new EnumMap<>(ConfigLoader.Property.class);
		detachedSources.putAll(propertySources);
		this.propertySources = Collections.unmodifiableMap(detachedSources);
		this.resolvedSourceProperties = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedSourceProperties));
		this.diagnostics = diagnostics;
		this.locks = locks;
	}

	/** @see ConfigurationSnapshot#getLocks() */
	@ApiStatus.Internal
	@Override
	@NotNull
	public ConfigurationLocks getLocks() {
		return locks;
	}

	/** @see SimpleJavaMailConfig#hasProperty(ConfigLoader.Property) */
	@Override
	public boolean hasProperty(@NotNull final ConfigLoader.Property property) {
		return !valueNullOrEmpty(values.get(property));
	}

	/** @see SimpleJavaMailConfig#getProperty(ConfigLoader.Property) */
	@Override
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T getProperty(@NotNull final ConfigLoader.Property property) {
		return (T) values.get(property);
	}

	/** @see SimpleJavaMailConfig#getStringProperty(ConfigLoader.Property) */
	@Override
	@Nullable
	public String getStringProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/** @see SimpleJavaMailConfig#getIntegerProperty(ConfigLoader.Property) */
	@Override
	@Nullable
	public Integer getIntegerProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/** @see SimpleJavaMailConfig#getBooleanProperty(ConfigLoader.Property) */
	@Override
	@Nullable
	public Boolean getBooleanProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/** @see SimpleJavaMailConfig#getPropertySource(ConfigLoader.Property) */
	@Override
	@Nullable
	public String getPropertySource(@NotNull final ConfigLoader.Property property) {
		return propertySources.get(property);
	}

	/** @see SimpleJavaMailConfig#getDiagnostics() */
	@Override
	@NotNull
	public ConfigDiagnostics getDiagnostics() {
		return diagnostics;
	}

	/** @see SimpleJavaMailConfig#valueOrProperty(Object, ConfigLoader.Property, Object) */
	@Override
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue,
			@NotNull final ConfigLoader.Property property,
			@Nullable final T fallbackValue) {
		if (!valueNullOrEmpty(explicitValue)) {
			return explicitValue;
		}
		return hasProperty(property) ? this.<T>getProperty(property) : fallbackValue;
	}

	/** @see SimpleJavaMailConfig#valueOrProperty(Object, ConfigLoader.Property) */
	@Override
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue, @NotNull final ConfigLoader.Property property) {
		return valueOrProperty(explicitValue, property, null);
	}

	/** @see SimpleJavaMailConfig#asMap() */
	@Override
	@NotNull
	public Map<ConfigLoader.Property, Object> asMap() {
		return values;
	}

	/** @see ConfigurationSnapshot#asSourceMap() */
	@Override
	@NotNull
	public Map<String, Object> asSourceMap() {
		return new LinkedHashMap<>(resolvedSourceProperties);
	}

	private static Object detach(final ConfigLoader.Property property, final Object value) {
		if (property == ConfigLoader.Property.EXTRA_PROPERTIES) {
			@SuppressWarnings("unchecked")
			final Map<String, String> extras = (Map<String, String>) value;
			return Collections.unmodifiableMap(new LinkedHashMap<>(extras));
		}
		if (property == ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
			@SuppressWarnings("unchecked")
			final Map<UUID, ConnectionPoolClusterConfig> clusters = (Map<UUID, ConnectionPoolClusterConfig>) value;
			return Collections.unmodifiableMap(new LinkedHashMap<>(clusters));
		}
		return value;
	}

	/** @see SimpleJavaMailConfig#toString() */
	@Override
	public String toString() {
		final StringBuilder result = new StringBuilder("SimpleJavaMailConfig{");
		boolean first = true;
		for (Map.Entry<ConfigLoader.Property, Object> entry : values.entrySet()) {
			if (!first) {
				result.append(", ");
			}
			first = false;
			result.append(entry.getKey().key()).append('=');
			result.append(PropertySchema.isSecret(entry.getKey()) ? "<redacted>" : entry.getValue());
		}
		return result.append('}').toString();
	}
}
