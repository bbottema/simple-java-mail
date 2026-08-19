package org.simplejavamail.config;

import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

import org.simplejavamail.config.ConfigLoader.Property;

final class PropertySchema {

	private enum ValueType {
		STRING,
		BOOLEAN,
		INTEGER,
		TRANSPORT_STRATEGY,
		SESSION_DEBUG_OUTPUT,
		CONTENT_TRANSFER_ENCODING,
		DSN_RETURN_OPTION,
		LOAD_BALANCING_STRATEGY,
		DKIM_CANONICALIZATION,
		WILDCARD
	}

	private static final Map<Property, ValueType> TYPES = new EnumMap<>(Property.class);
	private static final EnumSet<Property> SECRETS = EnumSet.of(
			Property.SMTP_PASSWORD,
			Property.PROXY_PASSWORD,
			Property.SMIME_SIGNING_KEYSTORE_PASSWORD,
			Property.SMIME_SIGNING_KEY_PASSWORD,
			Property.DKIM_PRIVATE_KEY_FILE_OR_DATA,
			Property.EXTRA_PROPERTIES);

	static {
		for (Property property : Property.values()) {
			TYPES.put(property, ValueType.STRING);
		}

		set(ValueType.BOOLEAN,
				Property.JAVAXMAIL_DEBUG,
				Property.DISABLE_ALL_CLIENTVALIDATION,
				Property.DEFAULT_TRUST_ALL_HOSTS,
				Property.DEFAULT_VERIFY_SERVER_IDENTITY,
				Property.TRANSPORT_MODE_LOGGING_ONLY,
				Property.OPPORTUNISTIC_TLS,
				Property.DKIM_SIGNING_USE_LENGTH_PARAM,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL);

		set(ValueType.INTEGER,
				Property.SMTP_PORT,
				Property.SMTP_LOCAL_PORT,
				Property.PROXY_PORT,
				Property.PROXY_SOCKS5BRIDGE_PORT,
				Property.DEFAULT_POOL_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS,
				Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS,
				Property.DEFAULT_POOL_KEEP_ALIVE_TIME,
				Property.DEFAULT_SESSION_TIMEOUT_MILLIS);

		set(ValueType.TRANSPORT_STRATEGY, Property.TRANSPORT_STRATEGY);
		set(ValueType.SESSION_DEBUG_OUTPUT, Property.JAVAXMAIL_DEBUG_OUTPUT);
		set(ValueType.CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING);
		set(ValueType.DSN_RETURN_OPTION, Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION);
		set(ValueType.LOAD_BALANCING_STRATEGY, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY);
		set(ValueType.DKIM_CANONICALIZATION,
				Property.DKIM_SIGNING_HEADER_CANONICALIZATION,
				Property.DKIM_SIGNING_BODY_CANONICALIZATION);
		set(ValueType.WILDCARD, Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS, Property.EXTRA_PROPERTIES);
	}

	private PropertySchema() {
	}

	static Object parse(final Property property, final Object rawValue, final String sourceName) {
		final ValueType type = TYPES.get(property);
		if (type == ValueType.WILDCARD) {
			throw new IllegalArgumentException("Wildcard property " + property.key() + " cannot be set as one scalar value");
		}
		try {
			switch (type) {
				case STRING:
					return requireType(rawValue, String.class);
				case BOOLEAN:
					return parseBoolean(rawValue);
				case INTEGER:
					return parseInteger(rawValue);
				case TRANSPORT_STRATEGY:
					return parseEnum(rawValue, TransportStrategy.class);
				case SESSION_DEBUG_OUTPUT:
					return parseEnum(rawValue, SessionDebugOutput.class);
				case CONTENT_TRANSFER_ENCODING:
					return rawValue instanceof ContentTransferEncoding
							? rawValue
							: ContentTransferEncoding.byEncoder(requireType(rawValue, String.class));
				case DSN_RETURN_OPTION:
					return rawValue instanceof DeliveryStatusNotification.ReturnOption
							? rawValue
							: DeliveryStatusNotification.parseReturnOption(requireType(rawValue, String.class));
				case LOAD_BALANCING_STRATEGY:
					return parseEnum(rawValue, LoadBalancingStrategy.class);
				case DKIM_CANONICALIZATION:
					return parseEnum(rawValue, DkimConfig.Canonicalization.class);
				default:
					throw new IllegalStateException("Unhandled property type " + type);
			}
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid value for " + property.key() + " from source " + sourceName
					+ "; expected " + expectedType(type), e);
		}
	}

	static boolean isSecret(final Property property) {
		return SECRETS.contains(property);
	}

	private static void set(final ValueType type, final Property... properties) {
		for (Property property : properties) {
			TYPES.put(property, type);
		}
	}

	private static Object parseBoolean(final Object value) {
		if (value instanceof Boolean) {
			return value;
		}
		final String normalized = requireType(value, String.class).toLowerCase(Locale.ROOT);
		if ("1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)) {
			return Boolean.TRUE;
		}
		if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized)) {
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException("Not a supported boolean");
	}

	private static Object parseInteger(final Object value) {
		if (value instanceof Integer) {
			return value;
		}
		return Integer.valueOf(requireType(value, String.class));
	}

	private static <T extends Enum<T>> T parseEnum(final Object value, final Class<T> enumType) {
		if (enumType.isInstance(value)) {
			return enumType.cast(value);
		}
		return Enum.valueOf(enumType, requireType(value, String.class));
	}

	private static <T> T requireType(final Object value, final Class<T> expectedType) {
		if (!expectedType.isInstance(value)) {
			throw new IllegalArgumentException("Wrong raw value type");
		}
		return expectedType.cast(value);
	}

	private static String expectedType(final ValueType type) {
		switch (type) {
			case BOOLEAN:
				return "a boolean";
			case INTEGER:
				return "an integer";
			case STRING:
				return "text";
			default:
				return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		}
	}
}
