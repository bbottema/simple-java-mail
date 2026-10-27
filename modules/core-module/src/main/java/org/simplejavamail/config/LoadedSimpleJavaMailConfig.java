package org.simplejavamail.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.config.ConfigurationSnapshot;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * An immutable, thread-safe snapshot of resolved Simple Java Mail configuration.
 * <p>
 * A snapshot contains only explicitly configured values. Runtime defaults such as generated cluster identifiers remain builder concerns. Stored input and
 * wildcard maps are detached during construction; snapshot maps returned by typed reads or {@link #asMap()} are unmodifiable.
 * <p>
 * Typed key reads and {@link #asMap()} return actual configured values and may therefore expose credentials. Use {@link #getDiagnostics()} for
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

    /** @see SimpleJavaMailConfig#hasProperty(ConfigKey) */
    @Override
    public boolean hasProperty(@NotNull final ConfigKey<?> key) {
        return !valueNullOrEmpty(values.get(java.util.Objects.requireNonNull(key, "key").property()));
    }

    /** @see SimpleJavaMailConfig#get(ConfigKey) */
    @Override
    @Nullable
    public <T> T get(@NotNull final ConfigKey<T> key) {
        return java.util.Objects.requireNonNull(key, "key").read(values.get(key.property()));
    }

    /** @see SimpleJavaMailConfig#getPropertySource(ConfigKey) */
    @Override
    @Nullable
    public String getPropertySource(@NotNull final ConfigKey<?> key) {
        return propertySources.get(java.util.Objects.requireNonNull(key, "key").property());
    }

	/** @see SimpleJavaMailConfig#getDiagnostics() */
	@Override
	@NotNull
	public ConfigDiagnostics getDiagnostics() {
		return diagnostics;
	}

	/** @see SimpleJavaMailConfig#valueOrProperty(Object, ConfigKey, Object) */
	@Override
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue,
			@NotNull final ConfigKey<T> key,
			@Nullable final T fallbackValue) {
		final ConfigurationChoice<T> choice = valueNullOrEmpty(explicitValue)
				? ConfigurationChoice.inherit() : ConfigurationChoice.set(explicitValue);
		return ConfigurationResolver.resolveValue(this, key, choice, fallbackValue,
				"SimpleJavaMailConfig.valueOrProperty").valueOrNull();
	}

	/** @see SimpleJavaMailConfig#valueOrProperty(Object, ConfigKey) */
	@Override
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue, @NotNull final ConfigKey<T> key) {
		return valueOrProperty(explicitValue, key, null);
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
