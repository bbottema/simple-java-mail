package org.simplejavamail.config;

import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.simplejavamail.config.ConfigLoader.Property;

import static org.simplejavamail.config.ConfigDiagnosticGroup.DELIVERY_STATUS_NOTIFICATIONS;
import static org.simplejavamail.config.ConfigDiagnosticGroup.DIAGNOSTICS_AND_VALIDATION;
import static org.simplejavamail.config.ConfigDiagnosticGroup.EMAIL_DEFAULTS;
import static org.simplejavamail.config.ConfigDiagnosticGroup.EMBEDDED_IMAGE_RESOLUTION;
import static org.simplejavamail.config.ConfigDiagnosticGroup.EXECUTION_AND_POOLING;
import static org.simplejavamail.config.ConfigDiagnosticGroup.JAKARTA_MAIL_PROPERTIES;
import static org.simplejavamail.config.ConfigDiagnosticGroup.MESSAGE_SECURITY;
import static org.simplejavamail.config.ConfigDiagnosticGroup.PROXY;
import static org.simplejavamail.config.ConfigDiagnosticGroup.SMTP_CONNECTION;
import static org.simplejavamail.config.ConfigDiagnosticGroup.TRANSPORT_SECURITY;
import static org.simplejavamail.config.ConfigPropertyDiagnostic.REDACTED_VALUE;

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

	private enum SensitivityPolicy {
		VISIBLE,
		REDACT,
		REDACT_WHEN_NAME_LOOKS_SENSITIVE
	}

	private static final Map<Property, ValueType> TYPES = new EnumMap<>(Property.class);
	private static final Map<Property, DiagnosticMetadata> DIAGNOSTIC_METADATA = new EnumMap<>(Property.class);
	private static final String[] SENSITIVE_EXTRA_PROPERTY_MARKERS = {
			"password",
			"passwd",
			"passphrase",
			"pwd",
			"secret",
			"token",
			"bearer",
			"credential",
			"authorization",
			"privatekey",
			"accesskey",
			"apikey",
			"keystore",
			"certificate",
			"cert"
	};

	static {
		registerPropertyTypes();
		registerDiagnosticMetadata();
		verifyEveryPropertyHasDiagnosticMetadata();
	}

	private PropertySchema() {
	}

	private static void registerPropertyTypes() {
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

	private static void registerDiagnosticMetadata() {
		setDiagnostics(DIAGNOSTICS_AND_VALIDATION, SensitivityPolicy.VISIBLE,
				Property.JAVAXMAIL_DEBUG,
				Property.JAVAXMAIL_DEBUG_OUTPUT,
				Property.DISABLE_ALL_CLIENTVALIDATION);
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.VISIBLE,
				Property.TRANSPORT_STRATEGY,
				Property.SMTP_HOST,
				Property.SMTP_PORT,
				Property.SMTP_USERNAME,
				Property.SMTP_CLIENT_HOSTNAME,
				Property.SMTP_LOCAL_ADDRESS,
				Property.SMTP_LOCAL_PORT,
				Property.DEFAULT_SESSION_TIMEOUT_MILLIS,
				Property.TRANSPORT_MODE_LOGGING_ONLY);
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.REDACT, Property.SMTP_PASSWORD);
		setDiagnostics(TRANSPORT_SECURITY, SensitivityPolicy.VISIBLE,
				Property.CUSTOM_SSLFACTORY_CLASS,
				Property.DEFAULT_TRUST_ALL_HOSTS,
				Property.DEFAULT_TRUSTED_HOSTS,
				Property.DEFAULT_VERIFY_SERVER_IDENTITY,
				Property.OPPORTUNISTIC_TLS);
		setDiagnostics(PROXY, SensitivityPolicy.VISIBLE,
				Property.PROXY_HOST,
				Property.PROXY_PORT,
				Property.PROXY_USERNAME,
				Property.PROXY_SOCKS5BRIDGE_PORT);
		setDiagnostics(PROXY, SensitivityPolicy.REDACT, Property.PROXY_PASSWORD);
		setDiagnostics(EMAIL_DEFAULTS, SensitivityPolicy.VISIBLE,
				Property.DEFAULT_SUBJECT,
				Property.DEFAULT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING,
				Property.DEFAULT_FROM_NAME,
				Property.DEFAULT_FROM_ADDRESS,
				Property.DEFAULT_REPLYTO_NAME,
				Property.DEFAULT_REPLYTO_ADDRESS,
				Property.DEFAULT_BOUNCETO_NAME,
				Property.DEFAULT_BOUNCETO_ADDRESS,
				Property.DEFAULT_TO_NAME,
				Property.DEFAULT_TO_ADDRESS,
				Property.DEFAULT_CC_NAME,
				Property.DEFAULT_CC_ADDRESS,
				Property.DEFAULT_BCC_NAME,
				Property.DEFAULT_BCC_ADDRESS);
		setDiagnostics(DELIVERY_STATUS_NOTIFICATIONS, SensitivityPolicy.VISIBLE,
				Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY,
				Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION);
		setDiagnostics(EXECUTION_AND_POOLING, SensitivityPolicy.VISIBLE,
				Property.DEFAULT_POOL_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY,
				Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS,
				Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS,
				Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS,
				Property.DEFAULT_POOL_KEEP_ALIVE_TIME);
		setDiagnostics(MESSAGE_SECURITY, SensitivityPolicy.VISIBLE,
				Property.SMIME_SIGNING_KEY_ALIAS,
				Property.SMIME_SIGNING_ALGORITHM,
				Property.SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM,
				Property.SMIME_ENCRYPTION_CIPHER,
				Property.DKIM_SELECTOR,
				Property.DKIM_SIGNING_DOMAIN,
				Property.DKIM_SIGNING_USE_LENGTH_PARAM,
				Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST,
				Property.DKIM_SIGNING_HEADER_CANONICALIZATION,
				Property.DKIM_SIGNING_BODY_CANONICALIZATION,
				Property.DKIM_SIGNING_ALGORITHM);
		setDiagnostics(MESSAGE_SECURITY, SensitivityPolicy.REDACT,
				Property.SMIME_SIGNING_KEYSTORE,
				Property.SMIME_SIGNING_KEYSTORE_PASSWORD,
				Property.SMIME_SIGNING_KEY_PASSWORD,
				Property.SMIME_ENCRYPTION_CERTIFICATE,
				Property.DKIM_PRIVATE_KEY_FILE_OR_DATA);
		setDiagnostics(EMBEDDED_IMAGE_RESOLUTION, SensitivityPolicy.VISIBLE,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH,
				Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL);
		setDiagnostics(JAKARTA_MAIL_PROPERTIES, SensitivityPolicy.REDACT_WHEN_NAME_LOOKS_SENSITIVE, Property.EXTRA_PROPERTIES);
	}

	private static void verifyEveryPropertyHasDiagnosticMetadata() {
		if (DIAGNOSTIC_METADATA.size() != Property.values().length) {
			throw new IllegalStateException("Every Simple Java Mail property must declare diagnostic metadata");
		}
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
		return diagnosticMetadata(property).sensitivityPolicy != SensitivityPolicy.VISIBLE;
	}

	static ConfigDiagnosticGroup diagnosticGroup(final Property property) {
		return diagnosticMetadata(property).group;
	}

	static ConfigPropertyDiagnostic diagnostic(final Property property,
			final String propertyName,
			final Object value,
			final String sourceName) {
		final DiagnosticMetadata metadata = diagnosticMetadata(property);
		final boolean redacted = shouldRedact(metadata.sensitivityPolicy, propertyName);
		return new ConfigPropertyDiagnostic(
				metadata.group,
				propertyName,
				redacted ? REDACTED_VALUE : String.valueOf(value),
				sourceName,
				redacted);
	}

	private static void set(final ValueType type, final Property... properties) {
		for (Property property : properties) {
			TYPES.put(property, type);
		}
	}

	private static void setDiagnostics(final ConfigDiagnosticGroup group,
			final SensitivityPolicy sensitivityPolicy,
			final Property... properties) {
		for (Property property : properties) {
			if (DIAGNOSTIC_METADATA.put(property, new DiagnosticMetadata(group, sensitivityPolicy)) != null) {
				throw new IllegalStateException("Diagnostic metadata already configured for " + property.key());
			}
		}
	}

	private static DiagnosticMetadata diagnosticMetadata(final Property property) {
		final DiagnosticMetadata metadata = DIAGNOSTIC_METADATA.get(property);
		if (metadata == null) {
			throw new IllegalStateException("No diagnostic metadata configured for " + property.key());
		}
		return metadata;
	}

	private static boolean shouldRedact(final SensitivityPolicy sensitivityPolicy, final String propertyName) {
		switch (sensitivityPolicy) {
			case REDACT:
				return true;
			case REDACT_WHEN_NAME_LOOKS_SENSITIVE:
				return isSensitiveExtraProperty(propertyName);
			case VISIBLE:
				return false;
			default:
				throw new IllegalStateException("Unhandled sensitivity policy " + sensitivityPolicy);
		}
	}

	private static boolean isSensitiveExtraProperty(final String propertyName) {
		final String normalizedName = normalizePropertyName(propertyName);
		for (String marker : SENSITIVE_EXTRA_PROPERTY_MARKERS) {
			if (normalizedName.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	private static String normalizePropertyName(final String propertyName) {
		final String lowerCaseName = propertyName.toLowerCase(Locale.ROOT);
		final StringBuilder normalizedName = new StringBuilder(lowerCaseName.length());
		for (int characterIndex = 0; characterIndex < lowerCaseName.length(); characterIndex++) {
			final char character = lowerCaseName.charAt(characterIndex);
			if (Character.isLetterOrDigit(character)) {
				normalizedName.append(character);
			}
		}
		return normalizedName.toString();
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

	private static final class DiagnosticMetadata {
		private final ConfigDiagnosticGroup group;
		private final SensitivityPolicy sensitivityPolicy;

		private DiagnosticMetadata(final ConfigDiagnosticGroup group, final SensitivityPolicy sensitivityPolicy) {
			this.group = group;
			this.sensitivityPolicy = sensitivityPolicy;
		}
	}
}
