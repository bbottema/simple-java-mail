package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.config.ConfigurationPropertyNames;
import org.simplejavamail.internal.config.ConfigurationSnapshot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;

/** Encodes JVM declarations for the child's existing environment source without changing loader precedence or raw-value validation. */
final class DaemonStartupConfiguration {
	private DaemonStartupConfiguration() { }

	static void copySystemProperties(final Map<String, String> environment, final Properties properties) {
		final Map<String, Object> declarations = captureDeclarations(properties);
		final boolean hasTypedValues = declarations.values().stream().anyMatch(value -> !(value instanceof String));
		final Map<String, Object> resolved = hasTypedValues ? resolveTypedValues(environment, declarations) : Map.of();
		final Map<String, String> transferred = new LinkedHashMap<>();
		declarations.forEach((name, value) -> {
			if (value instanceof String text) {
				transferred.put(environmentName(name), text);
			} else if (resolved.containsKey(name)) {
				// A lower-source lock can suppress an ordinary declaration. Do not revive it merely to cross the process boundary.
				transferred.put(environmentName(name), encodeResolvedValue(resolved.get(name)));
			}
		});
		environment.putAll(transferred);
	}

	private static Map<String, Object> captureDeclarations(final Properties properties) {
		final Map<String, Object> declarations = new LinkedHashMap<>();
		properties.stringPropertyNames().forEach(name -> declarations.put(name, properties.getProperty(name)));
		properties.forEach((key, value) -> {
			if (key instanceof String name) declarations.put(name, value);
		});
		declarations.entrySet().removeIf(entry -> !ConfigurationPropertyNames.isRecognizedKey(entry.getKey())
				|| entry.getValue() == null || entry.getValue() instanceof String text && text.trim().isEmpty());
		return declarations;
	}

	private static Map<String, Object> resolveTypedValues(final Map<String, String> environment,
			final Map<String, Object> declarations) {
		final Map<String, Object> sourceValues = new LinkedHashMap<>();
		declarations.forEach((name, value) -> sourceValues.put(name, safeClusterValue(name, value)));
		return ConfigurationSnapshot.requireInternalConfig(ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.withMap("environment variables", environmentDeclarations(environment))
				.withMap("system properties", sourceValues)
				.load()).asSourceMap();
	}

	private static Map<String, String> environmentDeclarations(final Map<String, String> environment) {
		final Map<String, String> declarations = new LinkedHashMap<>();
		for (Property property : Property.values()) {
			if (ConfigurationPropertyNames.isRecognizedKey(property.key())) {
				copyEnvironmentDeclaration(environment, declarations, property.key());
				copyEnvironmentDeclaration(environment, declarations, ConfigurationLocks.lockedName(property.key()));
			}
		}
		environment.forEach((name, value) -> {
			if (ConfigurationPropertyNames.isWildcardKey(ConfigurationLocks.ordinaryName(name))) declarations.put(name, value);
		});
		return declarations;
	}

	private static void copyEnvironmentDeclaration(final Map<String, String> environment,
			final Map<String, String> declarations, final String name) {
		final String value = environment.get(environmentName(name));
		if (value != null) declarations.put(name, value);
	}

	private static String environmentName(final String name) {
		return ConfigurationPropertyNames.isWildcardKey(ConfigurationLocks.ordinaryName(name))
				? name : name.replace('.', '_').toUpperCase(Locale.ROOT);
	}

	private static String encodeResolvedValue(final Object value) {
		if (value instanceof Enum<?> choice) return choice.name();
		if (value instanceof String text) return text;
		if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Double
				|| value instanceof Duration || value instanceof UUID) return value.toString();
		throw new IllegalArgumentException("A resolved JVM configuration value cannot be transferred to the daemon environment");
	}

	private static Object safeClusterValue(final String name, final Object value) {
		String ordinaryName = ConfigurationLocks.ordinaryName(name);
		final Matcher namedMailer = ConfigurationPropertyNames.NAMED_MAILER_PROPERTY_PATTERN.matcher(ordinaryName);
		if (namedMailer.matches()) ordinaryName = "simplejavamail." + namedMailer.group("tail");
		if (!ConfigurationPropertyNames.CONNECTIONPOOL_CLUSTER_PROPERTY_PATTERN.matcher(ordinaryName).matches()
				|| value instanceof String || value instanceof UUID || value instanceof LoadBalancingStrategy || isStandardNumber(value)) {
			return value;
		}
		// Legacy cluster parsing converts arbitrary objects to text. Keep its winning-value/lock rules without invoking application rendering.
		return UntransferableValue.INSTANCE;
	}

	private static boolean isStandardNumber(final Object value) {
		final Class<?> type = value.getClass();
		return type == Byte.class || type == Short.class || type == Integer.class || type == Long.class || type == Float.class
				|| type == Double.class || type == BigInteger.class || type == BigDecimal.class;
	}

	private enum UntransferableValue { INSTANCE }
}
