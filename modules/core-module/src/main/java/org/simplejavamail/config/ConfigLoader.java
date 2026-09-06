package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.internal.util.SimpleConversions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Ordered, instance-based configuration resolver. Add sources from lowest to highest priority and call {@link #load()} to create a detached immutable
 * {@link SimpleJavaMailConfig}. Later non-blank values win. The loader owns no process-wide configuration state and can be reused to produce independent
 * snapshots.
 * <p>
 * {@link #builder()} creates an empty loader. Use {@code SimpleJavaMail.fromDefaults()} when the conventional classpath, environment and system-property
 * recipe is wanted.
 */
public final class ConfigLoader {

	/**
	 * By default, the optional file {@value} will be loaded from classpath to load initial defaults.
	 */
	public static final String DEFAULT_CONFIG_FILENAME = "simplejavamail.properties";

	/**
	 * This pattern recognizes extra property lines that should be loaded directly into JavaMail on the Session object.
	 */
	private static final Pattern EXTRA_PROPERTY_PATTERN = compile("^simplejavamail\\.extraproperties\\.(?<actualProperty>.*)");
	private static final Pattern CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN = compile(
			"^simplejavamail\\.defaults\\.connectionpool\\.clusters\\.(?<clusterAlias>[^.]+)\\.(?<clusterProperty>clusterkey\\.uuid|coresize|maxsize|claimtimeout\\.millis|expireafter\\.millis|loadbalancing\\.strategy)$");

	private final List<ConfigSource> sources = new ArrayList<>();

	/**
	 * List of all the properties recognized by Simple Java Mail. Can be used to programmatically get, set or remove default values.
	 *
	 * @see <a href="https://www.simplejavamail.org">simplejavamail.org</a>
	 */
	public enum Property {
		JAVAXMAIL_DEBUG("simplejavamail.javaxmail.debug"),
		JAVAXMAIL_DEBUG_OUTPUT("simplejavamail.javaxmail.debug.out"),
		TRANSPORT_STRATEGY("simplejavamail.transportstrategy"),
		SMTP_HOST("simplejavamail.smtp.host"),
		SMTP_PORT("simplejavamail.smtp.port"),
		SMTP_USERNAME("simplejavamail.smtp.username"),
		SMTP_PASSWORD("simplejavamail.smtp.password"),
		SMTP_CLIENT_HOSTNAME("simplejavamail.smtp.clienthostname"),
		SMTP_LOCAL_ADDRESS("simplejavamail.smtp.localaddress"),
		SMTP_LOCAL_PORT("simplejavamail.smtp.localport"),
		DISABLE_ALL_CLIENTVALIDATION("simplejavamail.disable.all.clientvalidation"),
		CUSTOM_SSLFACTORY_CLASS("simplejavamail.custom.sslfactory.class"),
		PROXY_HOST("simplejavamail.proxy.host"),
		PROXY_PORT("simplejavamail.proxy.port"),
		PROXY_USERNAME("simplejavamail.proxy.username"),
		PROXY_PASSWORD("simplejavamail.proxy.password"),
		PROXY_SOCKS5BRIDGE_PORT("simplejavamail.proxy.socks5bridge.port"),
		DEFAULT_SUBJECT("simplejavamail.defaults.subject"),
		DEFAULT_CONTENT_TRANSFER_ENCODING("simplejavamail.defaults.content.transfer.encoding"),
		DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING("simplejavamail.defaults.body.text.content.transfer.encoding"),
		DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING("simplejavamail.defaults.body.html.content.transfer.encoding"),
		DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING("simplejavamail.defaults.body.calendar.content.transfer.encoding"),
		DEFAULT_FROM_NAME("simplejavamail.defaults.from.name"),
		DEFAULT_FROM_ADDRESS("simplejavamail.defaults.from.address"),
		DEFAULT_REPLYTO_NAME("simplejavamail.defaults.replyto.name"),
		DEFAULT_REPLYTO_ADDRESS("simplejavamail.defaults.replyto.address"),
		DEFAULT_BOUNCETO_NAME("simplejavamail.defaults.bounceto.name"),
		DEFAULT_BOUNCETO_ADDRESS("simplejavamail.defaults.bounceto.address"),
		DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY("simplejavamail.defaults.delivery.status.notification.notify"),
		DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION("simplejavamail.defaults.delivery.status.notification.return.option"),
		DEFAULT_TO_NAME("simplejavamail.defaults.to.name"),
		DEFAULT_TO_ADDRESS("simplejavamail.defaults.to.address"),
		DEFAULT_CC_NAME("simplejavamail.defaults.cc.name"),
		DEFAULT_CC_ADDRESS("simplejavamail.defaults.cc.address"),
		DEFAULT_BCC_NAME("simplejavamail.defaults.bcc.name"),
		DEFAULT_BCC_ADDRESS("simplejavamail.defaults.bcc.address"),
		DEFAULT_POOL_SIZE("simplejavamail.defaults.poolsize"),
		DEFAULT_CONNECTIONPOOL_CLUSTER_KEY("simplejavamail.defaults.connectionpool.clusterkey.uuid"),
		DEFAULT_CONNECTIONPOOL_CORE_SIZE("simplejavamail.defaults.connectionpool.coresize"),
		DEFAULT_CONNECTIONPOOL_MAX_SIZE("simplejavamail.defaults.connectionpool.maxsize"),
		DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS("simplejavamail.defaults.connectionpool.claimtimeout.millis"),
		DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS("simplejavamail.defaults.connectionpool.expireafter.millis"),
		DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY("simplejavamail.defaults.connectionpool.loadbalancing.strategy"),
		DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS("simplejavamail.defaults.connectionpool.clusters.*"),
		DEFAULT_POOL_KEEP_ALIVE_TIME("simplejavamail.defaults.poolsize.keepalivetime"),
		DEFAULT_SESSION_TIMEOUT_MILLIS("simplejavamail.defaults.sessiontimeoutmillis"),
		DEFAULT_TRUST_ALL_HOSTS("simplejavamail.defaults.trustallhosts"),
		DEFAULT_TRUSTED_HOSTS("simplejavamail.defaults.trustedhosts"),
		DEFAULT_VERIFY_SERVER_IDENTITY("simplejavamail.defaults.verifyserveridentity"),
		TRANSPORT_MODE_LOGGING_ONLY("simplejavamail.transport.mode.logging.only"),
		OPPORTUNISTIC_TLS("simplejavamail.opportunistic.tls"),
		SMIME_SIGNING_KEYSTORE("simplejavamail.smime.signing.keystore"),
		SMIME_SIGNING_KEYSTORE_PASSWORD("simplejavamail.smime.signing.keystore_password"),
		SMIME_SIGNING_KEY_ALIAS("simplejavamail.smime.signing.key_alias"),
		SMIME_SIGNING_KEY_PASSWORD("simplejavamail.smime.signing.key_password"),
		SMIME_SIGNING_ALGORITHM("simplejavamail.smime.signing.algorithm"),
		SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM("simplejavamail.smime.encryption.key_encapsulation_algorithm"),
		SMIME_ENCRYPTION_CIPHER("simplejavamail.smime.encryption.cipher"),
		DKIM_PRIVATE_KEY_FILE_OR_DATA("simplejavamail.dkim.signing.private_key_file_or_data"),
		DKIM_SELECTOR("simplejavamail.dkim.signing.selector"),
		DKIM_SIGNING_DOMAIN("simplejavamail.dkim.signing.signing_domain"),
		DKIM_SIGNING_USE_LENGTH_PARAM("simplejavamail.dkim.signing.use_length_param"),
		DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST("simplejavamail.dkim.signing.excluded_headers_from_default_signing_list"),
		DKIM_SIGNING_HEADER_CANONICALIZATION("simplejavamail.dkim.signing.header_canonicalization"),
		DKIM_SIGNING_BODY_CANONICALIZATION("simplejavamail.dkim.signing.body_canonicalization"),
		DKIM_SIGNING_ALGORITHM("simplejavamail.dkim.signing.algorithm"),
		SMIME_ENCRYPTION_CERTIFICATE("simplejavamail.smime.encryption.certificate"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR("simplejavamail.embeddedimages.dynamicresolution.enable.dir"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH("simplejavamail.embeddedimages.dynamicresolution.enable.classpath"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL("simplejavamail.embeddedimages.dynamicresolution.enable.url"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR("simplejavamail.embeddedimages.dynamicresolution.base.dir"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH("simplejavamail.embeddedimages.dynamicresolution.base.classpath"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL("simplejavamail.embeddedimages.dynamicresolution.base.url"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR("simplejavamail.embeddedimages.dynamicresolution.outside.base.dir"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL("simplejavamail.embeddedimages.dynamicresolution.outside.base.url"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH("simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath"),
		EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL("simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful"),
		EXTRA_PROPERTIES("simplejavamail.extraproperties.*");

		private final String key;

		Property(final String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	private ConfigLoader() {
	}

	/**
	 * Starts an independent ordered configuration loader. Every call returns a new loader and owns no process-wide Simple Java Mail configuration state.
	 */
	public static ConfigLoader builder() {
		return new ConfigLoader();
	}

	/**
	 * Adds a custom source at the current priority. Sources are read once per {@link #load()} call and later non-blank values win.
	 */
	public ConfigLoader withSource(final @NotNull ConfigSource source) {
		sources.add(source);
		return this;
	}

	/**
	 * Adds strict caller properties using the diagnostic name {@code properties}.
	 */
	public ConfigLoader withProperties(final @NotNull Properties properties) {
		return withProperties("properties", properties);
	}

	/**
	 * Adds strict caller properties. The source is sampled during each {@link #load()} and the resulting snapshot is detached from it.
	 */
	public ConfigLoader withProperties(final @NotNull String sourceName, final @NotNull Properties properties) {
		return withSource(mapSource(sourceName, properties, true));
	}

	/**
	 * Adds a strict caller map using the diagnostic name {@code map}.
	 */
	public ConfigLoader withMap(final @NotNull Map<String, ?> properties) {
		return withMap("map", properties);
	}

	/**
	 * Adds a strict caller map. The source is sampled during each {@link #load()} and the resulting snapshot is detached from it.
	 */
	public ConfigLoader withMap(final @NotNull String sourceName, final @NotNull Map<String, ?> properties) {
		return withSource(mapSource(sourceName, properties, true));
	}

	/**
	 * Consumes and closes a caller stream immediately, then adds the loaded values as a strict source named {@code input stream}.
	 */
	public ConfigLoader withInputStream(final @NotNull InputStream inputStream) {
		return withInputStream("input stream", inputStream);
	}

	/**
	 * Consumes and closes a caller stream immediately and adds its loaded values as a strict named source.
	 */
	public ConfigLoader withInputStream(final @NotNull String sourceName, final @NotNull InputStream inputStream) {
		final Properties properties = readAndClose(inputStream, sourceName);
		return withProperties(sourceName, properties);
	}

	/**
	 * Adds a required properties file at the current priority. The file is sampled on every {@link #load()} and is identified by its path in diagnostics.
	 *
	 * @param propertiesFile Path to the properties file.
	 * @return This loader.
	 */
	public ConfigLoader withPropertiesFile(final @NotNull Path propertiesFile) {
		return withPropertiesFile("file:" + propertiesFile, propertiesFile);
	}

	/**
	 * Adds a required properties file at the current priority under the supplied diagnostic name. The file is opened, read and closed on every
	 * {@link #load()}.
	 *
	 * @param sourceName Name shown for values from this source in configuration diagnostics.
	 * @param propertiesFile Path to the properties file.
	 * @return This loader.
	 */
	public ConfigLoader withPropertiesFile(final @NotNull String sourceName, final @NotNull Path propertiesFile) {
		return withSource(new ConfigSource() {
			@Override
			public String getName() {
				return sourceName;
			}

			@Override
			public Map<String, ?> getProperties() {
				return toMap(readPropertiesFile(propertiesFile, getName()));
			}
		});
	}

	/**
	 * Adds an optional classpath resource using the same ClassLoader as {@link ConfigLoader}. A missing resource contributes no values.
	 */
	public ConfigLoader withClasspathResource(final @NotNull String resourceName) {
		return withClasspathResource(resourceName, ConfigLoader.class.getClassLoader());
	}

	/**
	 * Adds an optional classpath resource from the given ClassLoader. A missing resource contributes no values.
	 */
	public ConfigLoader withClasspathResource(final @NotNull String resourceName, final @NotNull ClassLoader classLoader) {
		return withSource(new ConfigSource() {
			@Override
			public String getName() {
				return "classpath:" + resourceName;
			}

			@Override
			public Map<String, ?> getProperties() {
				final InputStream inputStream = classLoader.getResourceAsStream(resourceName);
				return inputStream == null ? Collections.<String, Object>emptyMap() : toMap(readAndClose(inputStream, getName()));
			}
		});
	}

	/**
	 * Adds the current process environment. Scalar names use uppercase underscore notation; wildcard names retain their literal dotted form for compatibility.
	 */
	public ConfigLoader withEnvironmentVariables() {
		return withEnvironmentVariables(System.getenv());
	}

	ConfigLoader withEnvironmentVariables(final Map<String, String> environment) {
		return withSource(new ConfigSource() {
			@Override
			public String getName() {
				return "environment variables";
			}

			@Override
			public Map<String, ?> getProperties() {
				final Map<String, Object> normalized = new LinkedHashMap<>();
				for (Property property : Property.values()) {
					if (property == Property.EXTRA_PROPERTIES || property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
						continue;
					}
					final String value = environment.get(property.key().replace('.', '_').toUpperCase(Locale.ROOT));
					if (value != null) {
						normalized.put(property.key(), value);
					}
				}
				for (Map.Entry<String, String> entry : environment.entrySet()) {
					if (isWildcardKey(entry.getKey())) {
						normalized.put(entry.getKey(), entry.getValue());
					}
				}
				return normalized;
			}

			@Override
			public boolean isStrict() {
				return false;
			}
		});
	}

	/**
	 * Adds the current JVM system properties using their canonical dotted names.
	 */
	public ConfigLoader withSystemProperties() {
		return withSystemProperties(System.getProperties());
	}

	ConfigLoader withSystemProperties(final Properties systemProperties) {
		return withSource(mapSource("system properties", systemProperties, false));
	}

	/**
	 * Adds an existing immutable snapshot as a lower or higher-priority source according to its position in this loader.
	 */
	public ConfigLoader withConfig(final @NotNull SimpleJavaMailConfig config) {
		return withMap("existing SimpleJavaMailConfig", config.asSourceMap());
	}

	/**
	 * Resolves the registered sources into a new detached immutable snapshot. Later non-blank values win and only each winning value is parsed.
	 */
	public SimpleJavaMailConfig load() {
		final Map<String, RawValue> winners = new LinkedHashMap<>();
		long sourceOrder = 0;
		for (ConfigSource source : sources) {
			final String sourceName = requireSourceName(source.getName());
			final Map<String, ?> sourceProperties = source.getProperties();
			if (sourceProperties == null) {
				throw new IllegalArgumentException("ConfigSource " + sourceName + " returned null properties");
			}
			for (Map.Entry<String, ?> entry : new LinkedHashMap<>(sourceProperties).entrySet()) {
				final String key = entry.getKey();
				if (!isRecognizedKey(key)) {
					if (source.isStrict()) {
						throw new IllegalStateException("Unknown Simple Java Mail property " + key + " from source " + sourceName);
					}
					continue;
				}
				if (!isBlankValue(entry.getValue())) {
					final String propertySourceName = requireSourceName(source.getPropertySourceName(key));
					winners.put(key, new RawValue(entry.getValue(), sourceName, propertySourceName, sourceOrder));
				}
			}
			sourceOrder++;
		}
		return resolve(winners);
	}

	private static SimpleJavaMailConfig resolve(final Map<String, RawValue> winners) {
		final Map<Property, Object> resolved = new EnumMap<>(Property.class);
		final Map<Property, String> origins = new EnumMap<>(Property.class);
		final Map<String, Object> resolvedSourceProperties = new LinkedHashMap<>();
		final List<ConfigPropertyDiagnostic> diagnostics = new ArrayList<>();

		resolveScalarProperties(winners, resolved, origins, resolvedSourceProperties, diagnostics);
		resolveWildcardProperties(winners, resolved, origins, resolvedSourceProperties, diagnostics);

		return new SimpleJavaMailConfig(resolved, origins, resolvedSourceProperties, new ConfigDiagnostics(diagnostics));
	}

	private static void resolveScalarProperties(final Map<String, RawValue> winners,
			final Map<Property, Object> resolved,
			final Map<Property, String> origins,
			final Map<String, Object> resolvedSourceProperties,
			final List<ConfigPropertyDiagnostic> diagnostics) {
		for (Property property : Property.values()) {
			if (property == Property.EXTRA_PROPERTIES || property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
				continue;
			}
			final RawValue winner = winners.get(property.key());
			if (winner != null) {
				final Object parsedValue = PropertySchema.parse(property, winner.value, winner.propertySourceName);
				resolved.put(property, parsedValue);
				origins.put(property, winner.propertySourceName);
				resolvedSourceProperties.put(property.key(), parsedValue);
				diagnostics.add(PropertySchema.diagnostic(property, property.key(), parsedValue, winner.propertySourceName));
			}
		}
	}

	private static void resolveWildcardProperties(final Map<String, RawValue> winners,
			final Map<Property, Object> resolved,
			final Map<Property, String> origins,
			final Map<String, Object> resolvedSourceProperties,
			final List<ConfigPropertyDiagnostic> diagnostics) {
		final Map<String, String> extraProperties = new LinkedHashMap<>();
		RawValue latestExtra = null;
		final Map<String, Object> clusterProperties = new LinkedHashMap<>();
		RawValue latestCluster = null;
		for (Map.Entry<String, RawValue> entry : winners.entrySet()) {
			final Matcher extraPropertyMatcher = EXTRA_PROPERTY_PATTERN.matcher(entry.getKey());
			if (extraPropertyMatcher.matches()) {
				if (!(entry.getValue().value instanceof String)) {
					throw invalidWildcardValue(entry.getKey(), entry.getValue(), "text");
				}
				extraProperties.put(extraPropertyMatcher.group("actualProperty"), (String) entry.getValue().value);
				diagnostics.add(PropertySchema.diagnostic(
						Property.EXTRA_PROPERTIES,
						entry.getKey(),
						entry.getValue().value,
						entry.getValue().propertySourceName));
				resolvedSourceProperties.put(entry.getKey(), entry.getValue().value);
				latestExtra = later(latestExtra, entry.getValue());
			} else if (CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(entry.getKey()).matches()) {
				final Object parsedValue = parseConnectionPoolClusterProperty(entry.getKey(), entry.getValue().value);
				clusterProperties.put(entry.getKey(), parsedValue);
				resolvedSourceProperties.put(entry.getKey(), parsedValue);
				diagnostics.add(PropertySchema.diagnostic(
						Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS,
						entry.getKey(),
						parsedValue,
						entry.getValue().propertySourceName));
				latestCluster = later(latestCluster, entry.getValue());
			}
		}

		if (!extraProperties.isEmpty()) {
			resolved.put(Property.EXTRA_PROPERTIES, extraProperties);
			origins.put(Property.EXTRA_PROPERTIES, latestExtra.configSourceName);
		}
		if (!clusterProperties.isEmpty()) {
			resolved.put(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS, parseConnectionPoolClusterProperties(clusterProperties));
			origins.put(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS, latestCluster.configSourceName);
		}
	}

	private static RawValue later(@Nullable final RawValue first, final RawValue second) {
		return first == null || second.sourceOrder >= first.sourceOrder ? second : first;
	}

	private static IllegalArgumentException invalidWildcardValue(final String key, final RawValue rawValue, final String expectedType) {
		return new IllegalArgumentException("Invalid value for " + key + " from source " + rawValue.propertySourceName + "; expected " + expectedType);
	}

	private static ConfigSource mapSource(final String sourceName, final Map<?, ?> properties, final boolean strict) {
		return new ConfigSource() {
			@Override
			public String getName() {
				return sourceName;
			}

			@Override
			public Map<String, ?> getProperties() {
				final Map<String, Object> copy = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : properties.entrySet()) {
					if (entry.getKey() instanceof String) {
						copy.put((String) entry.getKey(), entry.getValue());
					} else if (strict) {
						throw new IllegalStateException("Simple Java Mail property keys must be text in source " + sourceName);
					}
				}
				return copy;
			}

			@Override
			public boolean isStrict() {
				return strict;
			}
		};
	}

	private static Properties readAndClose(final InputStream inputStream, final String sourceName) {
		if (inputStream == null) {
			throw new IllegalArgumentException("InputStream was null for source " + sourceName);
		}
		final Properties properties = new Properties();
		try (InputStream stream = inputStream) {
			properties.load(stream);
			return properties;
		} catch (IOException e) {
			throw new IllegalStateException("Error reading configuration from source " + sourceName, e);
		}
	}

	private static Map<String, Object> toMap(final Properties properties) {
		final Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : properties.entrySet()) {
			result.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return result;
	}

	private static String requireSourceName(final String sourceName) {
		if (sourceName == null || sourceName.trim().isEmpty()) {
			throw new IllegalArgumentException("ConfigSource name was blank");
		}
		return sourceName;
	}

	private static boolean isRecognizedKey(@Nullable final String key) {
		if (key == null) {
			return false;
		}
		for (Property property : Property.values()) {
			if (property != Property.EXTRA_PROPERTIES
					&& property != Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS
					&& property.key().equals(key)) {
				return true;
			}
		}
		return isWildcardKey(key);
	}

	private static boolean isWildcardKey(final String key) {
		return EXTRA_PROPERTY_PATTERN.matcher(key).matches() || CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(key).matches();
	}

	private static boolean isBlankValue(@Nullable final Object value) {
		return value == null || value instanceof String && ((String) value).trim().isEmpty();
	}

	private static final class RawValue {
		private final Object value;
		private final String configSourceName;
		private final String propertySourceName;
		private final long sourceOrder;

		private RawValue(final Object value, final String configSourceName, final String propertySourceName, final long sourceOrder) {
			this.value = value;
			this.configSourceName = configSourceName;
			this.propertySourceName = propertySourceName;
			this.sourceOrder = sourceOrder;
		}
	}

	private static Map<UUID, ConnectionPoolClusterConfig> parseConnectionPoolClusterProperties(@NotNull final Map<String, Object> connectionPoolClusterProperties) {
		final Map<String, ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder> buildersByAlias = new HashMap<>();
		final Map<String, UUID> clusterKeysByAlias = new HashMap<>();

		for (Map.Entry<String, Object> propertyKey : connectionPoolClusterProperties.entrySet()) {
			final String propertyName = propertyKey.getKey();
			final Matcher matcher = CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(propertyName);
			if (!matcher.matches()) {
				continue;
			}
			final String clusterAlias = matcher.group("clusterAlias");
			final String clusterProperty = matcher.group("clusterProperty");
			final Object propertyValue = propertyKey.getValue();
			final ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder builder = buildersByAlias.computeIfAbsent(clusterAlias, ignored -> ConnectionPoolClusterConfig.builder());

			switch (clusterProperty) {
				case "clusterkey.uuid":
					clusterKeysByAlias.put(clusterAlias, (UUID) propertyValue);
					break;
				case "coresize":
					builder.coreSize((Integer) propertyValue);
					break;
				case "maxsize":
					builder.maxSize((Integer) propertyValue);
					break;
				case "claimtimeout.millis":
					builder.claimTimeoutMillis((Integer) propertyValue);
					break;
				case "expireafter.millis":
					builder.expireAfterMillis((Integer) propertyValue);
					break;
				case "loadbalancing.strategy":
					builder.loadBalancingStrategy((LoadBalancingStrategy) propertyValue);
					break;
				default:
					throw new IllegalStateException("Unhandled connection pool cluster property " + clusterProperty);
			}
		}

		final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs = new HashMap<>();
		for (Map.Entry<String, ConnectionPoolClusterConfig.ConnectionPoolClusterConfigBuilder> configBuilder : buildersByAlias.entrySet()) {
			final String clusterAlias = configBuilder.getKey();
			final UUID clusterKey = clusterKeysByAlias.containsKey(clusterAlias)
					? clusterKeysByAlias.get(clusterAlias)
					: parseUuid("cluster alias " + clusterAlias, clusterAlias);
			connectionPoolClusterConfigs.put(clusterKey, configBuilder.getValue().build());
		}
		return connectionPoolClusterConfigs;
	}

	private static Properties readPropertiesFile(final Path propertiesFile, final String sourceName) {
		if (propertiesFile == null) {
			throw new IllegalArgumentException("Path was null for source " + sourceName);
		}
		try {
			return readAndClose(Files.newInputStream(propertiesFile), sourceName);
		} catch (IOException e) {
			throw new IllegalStateException("Error opening configuration source " + sourceName, e);
		}
	}

	private static Object parseConnectionPoolClusterProperty(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		final Matcher matcher = CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(propertyName);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("Unknown connection pool cluster property " + propertyName);
		}
		switch (matcher.group("clusterProperty")) {
			case "clusterkey.uuid":
				return parseUuid(propertyName, propertyValue);
			case "coresize":
			case "maxsize":
			case "claimtimeout.millis":
			case "expireafter.millis":
				return parseInteger(propertyName, propertyValue);
			case "loadbalancing.strategy":
				return parseLoadBalancingStrategy(propertyName, propertyValue);
			default:
				throw new IllegalStateException("Unhandled connection pool cluster property " + propertyName);
		}
	}

	private static UUID parseUuid(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		try {
			return UUID.fromString(SimpleConversions.convertToString(propertyValue));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Connection pool cluster property " + propertyName + " should be a UUID", e);
		}
	}

	@Nullable
	private static Integer parseInteger(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		if (valueNullOrEmpty(propertyValue)) {
			return null;
		} else if (propertyValue instanceof Integer) {
			return (Integer) propertyValue;
		}
		try {
			return Integer.valueOf(SimpleConversions.convertToString(propertyValue));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Connection pool cluster property " + propertyName + " should be an integer", e);
		}
	}

	@Nullable
	private static LoadBalancingStrategy parseLoadBalancingStrategy(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		if (valueNullOrEmpty(propertyValue)) {
			return null;
		} else if (propertyValue instanceof LoadBalancingStrategy) {
			return (LoadBalancingStrategy) propertyValue;
		}
		try {
			return LoadBalancingStrategy.valueOf(SimpleConversions.convertToString(propertyValue));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Connection pool cluster property " + propertyName + " should be a LoadBalancingStrategy", e);
		}
	}

}
