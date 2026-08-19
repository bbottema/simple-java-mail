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
 */
public final class SimpleJavaMailConfig {

	private final Map<ConfigLoader.Property, Object> values;
	private final Map<ConfigLoader.Property, String> propertySources;

	SimpleJavaMailConfig(@NotNull final Map<ConfigLoader.Property, Object> values,
			@NotNull final Map<ConfigLoader.Property, String> propertySources) {
		final Map<ConfigLoader.Property, Object> detachedValues = new EnumMap<>(ConfigLoader.Property.class);
		for (Map.Entry<ConfigLoader.Property, Object> entry : values.entrySet()) {
			detachedValues.put(entry.getKey(), detach(entry.getKey(), entry.getValue()));
		}
		this.values = Collections.unmodifiableMap(detachedValues);
		final Map<ConfigLoader.Property, String> detachedSources = new EnumMap<>(ConfigLoader.Property.class);
		detachedSources.putAll(propertySources);
		this.propertySources = Collections.unmodifiableMap(detachedSources);
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
	 * @return An unmodifiable view of all resolved property values.
	 */
	@NotNull
	public Map<ConfigLoader.Property, Object> asMap() {
		return values;
	}

	Map<String, Object> asSourceMap() {
		final Map<String, Object> raw = new LinkedHashMap<>();
		for (Map.Entry<ConfigLoader.Property, Object> entry : values.entrySet()) {
			if (entry.getKey() == ConfigLoader.Property.EXTRA_PROPERTIES) {
				@SuppressWarnings("unchecked")
				final Map<String, String> extras = (Map<String, String>) entry.getValue();
				for (Map.Entry<String, String> extra : extras.entrySet()) {
					raw.put("simplejavamail.extraproperties." + extra.getKey(), extra.getValue());
				}
			} else if (entry.getKey() == ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
				@SuppressWarnings("unchecked")
				final Map<UUID, ConnectionPoolClusterConfig> clusters = (Map<UUID, ConnectionPoolClusterConfig>) entry.getValue();
				for (Map.Entry<UUID, ConnectionPoolClusterConfig> cluster : clusters.entrySet()) {
					final String prefix = "simplejavamail.defaults.connectionpool.clusters." + cluster.getKey() + ".";
					raw.put(prefix + "clusterkey.uuid", cluster.getKey().toString());
					putIfNotNull(raw, prefix + "coresize", cluster.getValue().getCoreSize());
					putIfNotNull(raw, prefix + "maxsize", cluster.getValue().getMaxSize());
					putIfNotNull(raw, prefix + "claimtimeout.millis", cluster.getValue().getClaimTimeoutMillis());
					putIfNotNull(raw, prefix + "expireafter.millis", cluster.getValue().getExpireAfterMillis());
					putIfNotNull(raw, prefix + "loadbalancing.strategy", cluster.getValue().getLoadBalancingStrategy());
				}
			} else {
				raw.put(entry.getKey().key(), entry.getValue());
			}
		}
		return raw;
	}

	private static void putIfNotNull(final Map<String, Object> target, final String key, @Nullable final Object value) {
		if (value != null) {
			target.put(key, value);
		}
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
