package org.simplejavamail.config;

import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;

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
import static org.simplejavamail.internal.util.PropertyDiagnosticText.isSensitiveName;
import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

final class PropertySchema {

	private enum ValueType {
		STRING,
		BOOLEAN,
		INTEGER,
		POSITIVE_INTEGER,
		LONG,
		DOUBLE,
		DURATION,
		TRANSPORT_STRATEGY,
		SESSION_DEBUG_OUTPUT,
		CONTENT_TRANSFER_ENCODING,
		DSN_RETURN_OPTION,
		LOAD_BALANCING_STRATEGY,
		ASYNC_QUEUE_OVERFLOW_POLICY,
		DKIM_CANONICALIZATION,
		WILDCARD
	}

	private enum SensitivityPolicy {
		VISIBLE,
		REDACT,
		REDACT_WHEN_NAME_LOOKS_SENSITIVE
	}

	private static final Map<Property, Binding<?, ?>> BINDINGS = new EnumMap<>(Property.class);
	private static final Map<Property, DiagnosticMetadata> DIAGNOSTIC_METADATA = new EnumMap<>(Property.class);

	static {
		registerDiagnosticMetadata();
		verifyEveryPropertyHasDiagnosticMetadata();
	}

	private PropertySchema() {
	}

    // These typed factories are selected only by ConfigKeys: they bind parsing and Java projection in one declaration.
    static ConfigKey<String> stringKey(final Property p) { return bind(p, ValueType.STRING, v -> requireType(v, String.class), Function.identity()); }
    static ConfigKey<Boolean> booleanKey(final Property p) { return bind(p, ValueType.BOOLEAN, PropertySchema::parseBoolean, Function.identity()); }
    static ConfigKey<Integer> integerKey(final Property p) { return bind(p, ValueType.INTEGER, PropertySchema::parseInteger, Function.identity()); }
    static ConfigKey<Integer> positiveIntegerKey(final Property p) { return bind(p, ValueType.POSITIVE_INTEGER, PropertySchema::parsePositiveInteger, Function.identity()); }
    static ConfigKey<Long> longKey(final Property p) { return bind(p, ValueType.LONG, PropertySchema::parseLong, Function.identity()); }
    static ConfigKey<Double> doubleKey(final Property p) { return bind(p, ValueType.DOUBLE, PropertySchema::parseDouble, Function.identity()); }
    static ConfigKey<Duration> durationKey(final Property p) {
        return bind(p, ValueType.DURATION, v -> v instanceof Duration ? (Duration) v : Duration.parse(requireType(v, String.class)), Function.identity());
    }
    static ConfigKey<Duration> millisecondKey(final Property p) {
        return bind(p, ValueType.INTEGER, PropertySchema::parseInteger, value -> Duration.ofMillis(value));
    }
    static ConfigKey<TransportStrategy> transportStrategyKey(final Property p) { return enumKey(p, ValueType.TRANSPORT_STRATEGY, TransportStrategy.class); }
    static ConfigKey<SessionDebugOutput> sessionDebugOutputKey(final Property p) { return enumKey(p, ValueType.SESSION_DEBUG_OUTPUT, SessionDebugOutput.class); }
    static ConfigKey<LoadBalancingStrategy> loadBalancingStrategyKey(final Property p) { return enumKey(p, ValueType.LOAD_BALANCING_STRATEGY, LoadBalancingStrategy.class); }
    static ConfigKey<AsyncQueueOverflowPolicy> asyncQueueOverflowPolicyKey(final Property p) { return enumKey(p, ValueType.ASYNC_QUEUE_OVERFLOW_POLICY, AsyncQueueOverflowPolicy.class); }
    static ConfigKey<DkimConfig.Canonicalization> dkimCanonicalizationKey(final Property p) { return enumKey(p, ValueType.DKIM_CANONICALIZATION, DkimConfig.Canonicalization.class); }
    static ConfigKey<ContentTransferEncoding> contentTransferEncodingKey(final Property p) {
        return bind(p, ValueType.CONTENT_TRANSFER_ENCODING, v -> v instanceof ContentTransferEncoding ? (ContentTransferEncoding) v
                : ContentTransferEncoding.byEncoder(requireType(v, String.class)), Function.identity());
    }
    static ConfigKey<DeliveryStatusNotification.ReturnOption> dsnReturnOptionKey(final Property p) {
        return bind(p, ValueType.DSN_RETURN_OPTION, v -> v instanceof DeliveryStatusNotification.ReturnOption ? (DeliveryStatusNotification.ReturnOption) v
                : DeliveryStatusNotification.parseReturnOption(requireType(v, String.class)), Function.identity());
    }
    static ConfigKey<Map<String, String>> extraPropertiesKey(final Property p) {
        return bind(p, ValueType.WILDCARD, PropertySchema::<String, String>mapValue, Function.identity());
    }
    static ConfigKey<Map<UUID, ConnectionPoolClusterConfig>> poolClustersKey(final Property p) {
        return bind(p, ValueType.WILDCARD, PropertySchema::<UUID, ConnectionPoolClusterConfig>mapValue, Function.identity());
    }
    static ConfigKey<Map<String, SimpleJavaMailConfig>> namedMailersKey(final Property p) {
        return bind(p, ValueType.WILDCARD, PropertySchema::<String, SimpleJavaMailConfig>mapValue, Function.identity());
    }
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> mapValue(final Object value) { return (Map<K, V>) requireType(value, Map.class); }

    private static <T extends Enum<T>> ConfigKey<T> enumKey(final Property p, final ValueType kind, final Class<T> type) {
        return bind(p, kind, value -> parseEnum(value, type), Function.identity());
    }
    private static <S, T> ConfigKey<T> bind(final Property property, final ValueType kind,
            final Function<Object, S> parser, final Function<S, T> projection) {
        final Binding<S, T> binding = new Binding<>(property, kind, parser, projection);
        if (BINDINGS.put(property, binding) != null) {
            throw new IllegalStateException("Schema already configured for " + property.key());
        }
        return new ConfigKey<>(binding);
    }
    static void verifyKeyCatalogue() {
        if (BINDINGS.size() != Property.values().length) {
            throw new IllegalStateException("Every Simple Java Mail property must have exactly one typed schema key");
        }
    }
    private static Binding<?, ?> binding(final Property property) {
        ConfigKeys.initialize();
        final Binding<?, ?> binding = BINDINGS.get(property);
        if (binding == null) throw new IllegalStateException("No typed schema key configured for " + property.key());
        return binding;
    }

    /** Loader-only binding: S is the existing parsed storage type; T is the key's invariant Java view. */
    static final class Binding<S, T> {
        final Property property;
        private final ValueType kind;
        private final Function<Object, S> parser;
        private final Function<S, T> projection;

        private Binding(final Property property, final ValueType kind, final Function<Object, S> parser, final Function<S, T> projection) {
            this.property = property;
            this.kind = kind;
            this.parser = parser;
            this.projection = projection;
        }

        @SuppressWarnings("unchecked")
        T read(final Object stored) {
            // Only the loader's matching parser/typed wildcard assembly can populate this property's immutable snapshot slot.
            return projection.apply((S) stored);
        }
    }

	private static void registerDiagnosticMetadata() {
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.VISIBLE, Property.SMTP_RETRY_ATTEMPTS, Property.SMTP_RETRY_DELAY,
				Property.SMTP_RETRY_BACKOFF_MULTIPLIER, Property.SMTP_RETRY_MAXIMUM_DELAY, Property.SMTP_RETRY_JITTER);
		setDiagnostics(EXECUTION_AND_POOLING, SensitivityPolicy.VISIBLE, Property.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY,
				Property.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, Property.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.VISIBLE, Property.FAILOVER_MAILER, Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS,
				Property.FAILOVER_PRIMARY_RETRY_DELAY, Property.FAILOVER_PRIMARY_RECHECK_INTERVAL, Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER,
				Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL, Property.FAILOVER_ON_AUTHENTICATION_FAILURE,
				Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE);
		// Individual named leaves use their ordinary schema's group and sensitivity; never render the aggregate's raw credentials.
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.REDACT, Property.NAMED_MAILERS);
		setDiagnostics(DIAGNOSTICS_AND_VALIDATION, SensitivityPolicy.VISIBLE,
				Property.TELEMETRY_ENABLED,
				Property.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES,
				Property.JAVAXMAIL_DEBUG,
				Property.JAVAXMAIL_DEBUG_OUTPUT,
				Property.DISABLE_ALL_CLIENTVALIDATION);
		setDiagnostics(SMTP_CONNECTION, SensitivityPolicy.VISIBLE,
				Property.TRANSPORT_STRATEGY,
				Property.SMTP_HOST,
				Property.SMTP_PORT,
				Property.SMTP_USERNAME,
				Property.SMTP_CLIENT_HOSTNAME,
				Property.SMTP_LEGACY_CONTENT_SUPPORT,
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
				Property.DEFAULT_REQUIRE_TLS,
				Property.OPPORTUNISTIC_TLS);
		setDiagnostics(PROXY, SensitivityPolicy.VISIBLE,
				Property.PROXY_HOST,
				Property.PROXY_PORT,
				Property.PROXY_USERNAME,
				Property.PROXY_SOCKS5BRIDGE_PORT);
		setDiagnostics(PROXY, SensitivityPolicy.REDACT, Property.PROXY_PASSWORD);
		setDiagnostics(EMAIL_DEFAULTS, SensitivityPolicy.VISIBLE,
				Property.DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS,
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
				Property.SMTP_RATE_LIMIT_GROUP,
				Property.SMTP_MESSAGE_RATE_LIMIT,
				Property.SMTP_MESSAGE_RATE_PERIOD,
				Property.SMTP_RECIPIENT_RATE_LIMIT,
				Property.SMTP_RECIPIENT_RATE_PERIOD,
				Property.SMTP_RATE_LIMIT_ALLOW_BURSTS,
				Property.DEFAULT_MAIL_SEND_TIMEOUT,
				Property.DEFAULT_ASYNC_QUEUE_CAPACITY,
				Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY,
				Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS,
				Property.DEFAULT_POOL_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY,
				Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
				Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS,
				Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS,
				Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS,
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

	/** Types come from the canonical schema; errors identify the declaration selected from the ordinary or locked namespace. */
	static Object parse(final Property property, final Object rawValue, final String propertyName, final String sourceName) {
        final Binding<?, ?> binding = binding(property);
        if (binding.kind == ValueType.WILDCARD) {
            throw new IllegalArgumentException("Wildcard property " + escapeControlCharacters(propertyName)
                    + " cannot be set as a single value. Configure its individual child properties instead.");
        }
        try {
            return binding.parser.apply(rawValue);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid value for " + escapeControlCharacters(propertyName)
                    + " from source " + escapeControlCharacters(sourceName) + "; expected " + expectedType(binding.kind)
                    + ". Correct this value in its configuration source.", e);
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
				return isSensitiveName(propertyName);
			case VISIBLE:
				return false;
			default:
				throw new IllegalStateException("Unhandled sensitivity policy " + sensitivityPolicy);
		}
	}

	private static Boolean parseBoolean(final Object value) {
		if (value instanceof Boolean) {
			return (Boolean) value;
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

	static Integer parsePositiveInteger(final Object value) {
		final Integer parsedValue = parseInteger(value);
		if (parsedValue < 1) {
			throw new IllegalArgumentException("Expected a positive integer");
		}
		return parsedValue;
	}

	private static Integer parseInteger(final Object value) {
		if (value instanceof Integer) {
			return (Integer) value;
		}
		return Integer.valueOf(requireType(value, String.class));
	}

	private static Long parseLong(final Object value) {
		if (value instanceof Long || value instanceof Integer) {
			return ((Number) value).longValue();
		}
		return Long.parseLong(requireType(value, String.class));
	}

	private static Double parseDouble(final Object value) {
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		return Double.parseDouble(requireType(value, String.class));
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
			case POSITIVE_INTEGER:
				return "a positive integer";
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
