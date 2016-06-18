package org.simplejavamail.internal.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Contains list of possible properties names and can produce a map of property values, if provided as file "{@value #FILENAME}" on
 * the classpath or as environment property.
 */
public class ConfigLoader {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

	private static final String FILENAME = "simplejavamail.properties";

	private static final Map<String, Object> RESOLVED_PROPERTIES = loadProperties();

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
		DEFAULT_FROM_NAME("simplejavamail.defaults.from.name"),
		DEFAULT_FROM_ADDRESS("simplejavamail.defaults.from.address"),
		DEFAULT_REPLYTO_NAME("simplejavamail.defaults.replyto.name"),
		DEFAULT_REPLYTO_ADDRESS("simplejavamail.defaults.replyto.address"),
		DEFAULT_TO_NAME("simplejavamail.defaults.to.name"),
		DEFAULT_TO_ADDRESS("simplejavamail.defaults.to.address"),
		DEFAULT_CC_NAME("simplejavamail.defaults.cc.name"),
		DEFAULT_CC_ADDRESS("simplejavamail.defaults.cc.address"),
		DEFAULT_BCC_NAME("simplejavamail.defaults.bcc.name"),
		DEFAULT_BCC_ADDRESS("simplejavamail.defaults.bcc.address");

		private final String propertyName;

		Property(String propertyName) {
			this.propertyName = propertyName;
		}
	}

	public static boolean hasProperty(Property property) {
		return RESOLVED_PROPERTIES.containsKey(property.propertyName);
	}

	public static <T> T getProperty(Property property) {
		return (T) RESOLVED_PROPERTIES.get(property.propertyName);
	}

	/**
	 * Loads properties from property file on the classpath, if provided.
	 */
	public static Map<String, Object> loadProperties() {
		InputStream input = null;

		try {
			input = ConfigLoader.class.getClassLoader().getResourceAsStream(FILENAME);
			if (input != null) {
				Properties prop = new Properties();
				prop.load(input);
				return readProperties(prop);
			} else {
				LOGGER.debug("Property file not found on classpath");
			}
		} catch (IOException e) {
			LOGGER.error("error reading properties file from classpath: " + FILENAME, e);
		} finally {
			if (input != null) {
				try {
					input.close();
				} catch (IOException e) {
					LOGGER.error(e.getMessage(), e);
				}
			}
		}
		return new HashMap<>();
	}

	/**
	 * @return All properties in priority of System property > File properties.
	 */
	private static Map<String, Object> readProperties(Properties fileProperties) {
		Map<String, Object> resolvedProps = new HashMap<>();
		for (Property prop : Property.values()) {
			resolvedProps.put(prop.propertyName, parsePropertyValue(System.getProperty(prop.propertyName)));
			if (resolvedProps.get(prop.propertyName) == null) {
				resolvedProps.put(prop.propertyName, parsePropertyValue(fileProperties.getProperty(prop.propertyName)));
			}
		}
		return resolvedProps;
	}

	/**
	 * @return The property value in boolean, integer or as original string value.
	 */
	private static Object parsePropertyValue(String propertyValue) {
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
			return booleanConversionMap.get(propertyValue);
		}
		// read number value
		try {
			return Integer.valueOf(propertyValue);
		} catch (NumberFormatException nfe) {
			// ok, so not a number
		}
		// return value as string
		return propertyValue;
	}
}