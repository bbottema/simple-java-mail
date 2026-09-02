package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;

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
 */
public final class SimpleJavaMailConfig {

	private final Map<ConfigLoader.Property, Object> values;
	private final Map<ConfigLoader.Property, String> propertySources;
	// Retains concrete wildcard aliases because their aggregate values are keyed by the resolved cluster UUID instead.
	private final Map<String, Object> resolvedSourceProperties;
	private final ConfigDiagnostics diagnostics;

	SimpleJavaMailConfig(@NotNull final Map<ConfigLoader.Property, Object> values,
			@NotNull final Map<ConfigLoader.Property, String> propertySources,
			@NotNull final Map<String, Object> resolvedSourceProperties,
			@NotNull final ConfigDiagnostics diagnostics) {
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
	}

	/**
	 * @return Whether this snapshot contains a non-blank value for the property.
	 */
	public boolean hasProperty(@NotNull final ConfigLoader.Property property) {
		return !valueNullOrEmpty(values.get(property));
	}

	/**
	 * Returns the property using its schema-declared type.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T getProperty(@NotNull final ConfigLoader.Property property) {
		return (T) values.get(property);
	}

	/**
	 * Returns a property whose schema type is {@link String}.
	 */
	@Nullable
	public String getStringProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/**
	 * Returns a property whose schema type is {@link Integer}.
	 */
	@Nullable
	public Integer getIntegerProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/**
	 * Returns a property whose schema type is {@link Boolean}.
	 */
	@Nullable
	public Boolean getBooleanProperty(@NotNull final ConfigLoader.Property property) {
		return getProperty(property);
	}

	/**
	 * @return The diagnostic source name for the winning property, or {@code null} when the property is absent.
	 */
	@Nullable
	public String getPropertySource(@NotNull final ConfigLoader.Property property) {
		return propertySources.get(property);
	}

	/**
	 * @return An immutable, grouped and safe-to-log description of every configured value and its winning source.
	 * @see org.simplejavamail.api.mailer.Mailer#getOperationalConfig()
	 */
	@NotNull
	public ConfigDiagnostics getDiagnostics() {
		return diagnostics;
	}

	/**
	 * Returns the explicit value when non-blank, otherwise the configured value, otherwise the supplied fallback.
	 */
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue,
			@NotNull final ConfigLoader.Property property,
			@Nullable final T fallbackValue) {
		if (!valueNullOrEmpty(explicitValue)) {
			return explicitValue;
		}
		return hasProperty(property) ? this.<T>getProperty(property) : fallbackValue;
	}

	/**
	 * See {@link #valueOrProperty(Object, ConfigLoader.Property, Object)}.
	 */
	@Nullable
	public <T> T valueOrProperty(@Nullable final T explicitValue, @NotNull final ConfigLoader.Property property) {
		return valueOrProperty(explicitValue, property, null);
	}

	/**
	 * @return An unmodifiable view of all resolved property values. This map contains actual values and is not safe to include in diagnostic logs.
	 */
	@NotNull
	public Map<ConfigLoader.Property, Object> asMap() {
		return values;
	}

	Map<String, Object> asSourceMap() {
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
