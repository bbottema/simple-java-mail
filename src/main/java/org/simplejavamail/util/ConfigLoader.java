package org.simplejavamail.util;

import org.simplejavamail.mailer.config.TransportStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.util.Collections.unmodifiableMap;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Contains list of possible properties names and can produce a map of property values, if provided as file "{@value #DEFAULT_CONFIG_FILENAME}" on the
 * classpath or as environment property.
 * <p>
 * The following properties are allowed:
 * <pre><ul>
 *    <li>simplejavamail.javaxmail.debug</li>
 *    <li>simplejavamail.transportstrategy</li>
 *    <li>simplejavamail.smtp.host</li>
 *    <li>simplejavamail.smtp.port</li>
 *    <li>simplejavamail.smtp.username</li>
 *    <li>simplejavamail.smtp.password</li>
 *    <li>simplejavamail.proxy.host</li>
 *    <li>simplejavamail.proxy.port</li>
 *    <li>simplejavamail.proxy.username</li>
 *    <li>simplejavamail.proxy.password</li>
 *    <li>simplejavamail.proxy.socks5bridge.port</li>
 *    <li>simplejavamail.defaults.subject</li>
 *    <li>simplejavamail.defaults.from.name</li>
 *    <li>simplejavamail.defaults.from.address</li>
 *    <li>simplejavamail.defaults.replyto.name</li>
 *    <li>simplejavamail.defaults.replyto.address</li>
 *    <li>simplejavamail.defaults.bounceto.name</li>
 *    <li>simplejavamail.defaults.bounceto.address</li>
 *    <li>simplejavamail.defaults.to.name</li>
 *    <li>simplejavamail.defaults.to.address</li>
 *    <li>simplejavamail.defaults.cc.name</li>
 *    <li>simplejavamail.defaults.cc.address</li>
 *    <li>simplejavamail.defaults.bcc.name</li>
 *    <li>simplejavamail.defaults.bcc.address</li>
 *    <li>simplejavamail.defaults.poolsize</li>
 *    <li>simplejavamail.defaults.sessiontimeoutmillis</li>
 *    <li>simplejavamail.transport.mode.logging.only</li>
 * </ul></pre>
 */
public final class ConfigLoader {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

	private static final String DEFAULT_CONFIG_FILENAME = "simplejavamail.properties";

	/**
	 * Initially try to load properties from "{@value #DEFAULT_CONFIG_FILENAME}".
	 *
	 * @see #loadProperties(String, boolean)
	 * @see #loadProperties(InputStream, boolean)
	 */
	private static final Map<Property, Object> RESOLVED_PROPERTIES = new HashMap<>();

	static {
		// static initializer block, because loadProperties needs to modify RESOLVED_PROPERTIES while loading
		// this is not possible when we are initializing the same field.
		// RESOLVED_PROPERTIES = loadProperties(DEFAULT_CONFIG_FILENAME); <-- not possible
		loadProperties(DEFAULT_CONFIG_FILENAME, false);
	}

	public enum Property {
		JAVAXMAIL_DEBUG("simplejavamail.javaxmail.debug"),
		TRANSPORT_STRATEGY("simplejavamail.transportstrategy"),
		SMTP_HOST("simplejavamail.smtp.host"),
		SMTP_PORT("simplejavamail.smtp.port"),
		SMTP_USERNAME("simplejavamail.smtp.username"),
		SMTP_PASSWORD("simplejavamail.smtp.password"),
		PROXY_HOST("simplejavamail.proxy.host"),
		PROXY_PORT("simplejavamail.proxy.port"),
		PROXY_USERNAME("simplejavamail.proxy.username"),
		PROXY_PASSWORD("simplejavamail.proxy.password"),
		PROXY_SOCKS5BRIDGE_PORT("simplejavamail.proxy.socks5bridge.port"),
		DEFAULT_SUBJECT("simplejavamail.defaults.subject"),
		DEFAULT_FROM_NAME("simplejavamail.defaults.from.name"),
		DEFAULT_FROM_ADDRESS("simplejavamail.defaults.from.address"),
		DEFAULT_REPLYTO_NAME("simplejavamail.defaults.replyto.name"),
		DEFAULT_REPLYTO_ADDRESS("simplejavamail.defaults.replyto.address"),
		DEFAULT_BOUNCETO_NAME("simplejavamail.defaults.bounceto.name"),
		DEFAULT_BOUNCETO_ADDRESS("simplejavamail.defaults.bounceto.address"),
		DEFAULT_TO_NAME("simplejavamail.defaults.to.name"),
		DEFAULT_TO_ADDRESS("simplejavamail.defaults.to.address"),
		DEFAULT_CC_NAME("simplejavamail.defaults.cc.name"),
		DEFAULT_CC_ADDRESS("simplejavamail.defaults.cc.address"),
		DEFAULT_BCC_NAME("simplejavamail.defaults.bcc.name"),
		DEFAULT_BCC_ADDRESS("simplejavamail.defaults.bcc.address"),
		DEFAULT_POOL_SIZE("simplejavamail.defaults.poolsize"),
		DEFAULT_SESSION_TIMEOUT_MILLIS("simplejavamail.defaults.sessiontimeoutmillis"),
		TRANSPORT_MODE_LOGGING_ONLY("simplejavamail.transport.mode.logging.only");

		private final String key;

		Property(final String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	/**
	 * @return The value if not null or else the value from config file if provided or else <code>null</code>.
	 */
	public static <T> T valueOrProperty(final T value, final Property property) {
		return valueOrProperty(value, property, null);
	}

	/**
	 * Returns the given value if not null and not empty, otherwise tries to resolve the given property and if still not found resot to the default value if
	 * provided.
	 * <p>
	 * Null or blank values are never allowed, so they are always ignored.
	 *
	 * @return The value if not null or else the value from config file if provided or else <code>defaultValue</code>.
	 */
	public static <T> T valueOrProperty(final T value, final Property property, final T defaultValue) {
		if (!valueNullOrEmpty(value)) {
			LOGGER.trace("using provided argument value {} for property {}", value, property);
			return value;
		} else if (hasProperty(property)) {
			final T propertyValue = getProperty(property);
			LOGGER.trace("using value {} from config file for property {}", propertyValue, property);
			return propertyValue;
		} else {
			LOGGER.trace("no value provided as argument or in config file for property {}, using default value {}", property, defaultValue);
			return defaultValue;
		}
	}

	public static synchronized boolean hasProperty(final Property property) {
		return !valueNullOrEmpty(RESOLVED_PROPERTIES.get(property));
	}

	public static synchronized <T> T getProperty(final Property property) {
		//noinspection unchecked
		return (T) RESOLVED_PROPERTIES.get(property);
	}

	/**
	 * Loads properties from property file on the classpath, if provided. Calling this method only has effect on new Email and Mailer instances after
	 * this.
	 *
	 * @param filename      Any file that is on the classpath that holds a list of key=value pairs.
	 * @param addProperties Flag to indicate if the new properties should be added or replacing the old properties.
	 * @return The updated properties map that is used internally.
	 */
	public static Map<Property, Object> loadProperties(final String filename, final boolean addProperties) {
		final InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream(filename);
		if (input != null) {
			return loadProperties(input, addProperties);
		}
		LOGGER.debug("Property file not found on classpath, skipping config file");
		return new HashMap<>();
	}

	/**
	 * Loads properties from another properties source, in case you want to provide your own list.
	 *
	 * @param properties    Your own list of properties
	 * @param addProperties Flag to indicate if the new properties should be added or replacing the old properties.
	 * @return The updated properties map that is used internally.
	 */
	public static Map<Property, Object> loadProperties(final Properties properties, final boolean addProperties) {
		if (!addProperties) {
			RESOLVED_PROPERTIES.clear();
		}
		RESOLVED_PROPERTIES.putAll(readProperties(properties));
		return unmodifiableMap(RESOLVED_PROPERTIES);
	}

	/**
	 * Loads properties from property {@link File}, if provided. Calling this method only has effect on new Email and Mailer instances after this.
	 *
	 * @param filename      Any file reference that holds a properties list.
	 * @param addProperties Flag to indicate if the new properties should be added or replacing the old properties.
	 * @return The updated properties map that is used internally.
	 */
	public static Map<Property, Object> loadProperties(final File filename, final boolean addProperties) {
		try {
			return loadProperties(new FileInputStream(filename), addProperties);
		} catch (final FileNotFoundException e) {
			throw new IllegalStateException("error reading properties file from File", e);
		}
	}

	/**
	 * Loads properties from {@link InputStream}. Calling this method only has effect on new Email and Mailer instances after this.
	 *
	 * @param inputStream   Source of property key=value pairs separated by newline \n characters.
	 * @param addProperties Flag to indicate if the new properties should be added or replacing the old properties.
	 * @return The updated properties map that is used internally.
	 */
	public static synchronized Map<Property, Object> loadProperties(final InputStream inputStream, final boolean addProperties) {
		final Properties prop = new Properties();

		try {
			prop.load(checkArgumentNotEmpty(inputStream, "InputStream was null"));
		} catch (final IOException e) {
			throw new IllegalStateException("error reading properties file from inputstream", e);
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (final IOException e) {
					LOGGER.error(e.getMessage(), e);
				}
			}
		}

		if (!addProperties) {
			RESOLVED_PROPERTIES.clear();
		}
		RESOLVED_PROPERTIES.putAll(readProperties(prop));
		return unmodifiableMap(RESOLVED_PROPERTIES);
	}

	/**
	 * @return All properties in priority of System property > File properties.
	 */
	private static Map<Property, Object> readProperties(final Properties fileProperties) {
		final Properties filePropertiesLeft = new Properties();
		filePropertiesLeft.putAll(fileProperties);
		final Map<Property, Object> resolvedProps = new HashMap<>();
		for (final Property prop : Property.values()) {
			if (System.getProperty(prop.key) != null) {
				System.out.println(prop.key + ": " + System.getProperty(prop.key));
			}
			final Object asSystemProperty = parsePropertyValue(System.getProperty(prop.key));
			if (asSystemProperty != null) {
				resolvedProps.put(prop, asSystemProperty);
				filePropertiesLeft.remove(prop.key);
			} else {
				final Object asEnvProperty = parsePropertyValue(System.getenv().get(prop.key));
				if (asEnvProperty != null) {
					resolvedProps.put(prop, asEnvProperty);
					filePropertiesLeft.remove(prop.key);
				} else {
					final Object rawValue = filePropertiesLeft.remove(prop.key);
					if (rawValue != null) {
						if (rawValue instanceof String) {
							resolvedProps.put(prop, parsePropertyValue((String) rawValue));
						} else {
							resolvedProps.put(prop, rawValue);
						}
					}
				}
			}
		}

		if (!filePropertiesLeft.isEmpty()) {
			throw new IllegalArgumentException("unknown properties provided " + filePropertiesLeft);
		}

		return resolvedProps;
	}

	/**
	 * @return The property value in boolean, integer or as original string value.
	 */
	static Object parsePropertyValue(final String propertyValue) {
		if (propertyValue == null) {
			return null;
		}
		// read boolean value
		final Map<String, Boolean> booleanConversionMap = new HashMap<>();
		booleanConversionMap.put("0", false);
		booleanConversionMap.put("1", true);
		booleanConversionMap.put("false", false);
		booleanConversionMap.put("true", true);
		booleanConversionMap.put("no", false);
		booleanConversionMap.put("yes", true);
		if (booleanConversionMap.containsKey(propertyValue)) {
			return booleanConversionMap.get(propertyValue.toLowerCase());
		}
		// read number value
		try {
			return Integer.valueOf(propertyValue);
		} catch (final NumberFormatException nfe) {
			// ok, so not a number
		}
		// read TransportStrategy value
		try {
			return TransportStrategy.valueOf(propertyValue);
		} catch (final IllegalArgumentException nfe) {
			// ok, so not a TransportStrategy either
		}
		// return value as is (which should be string)
		return propertyValue;
	}
}