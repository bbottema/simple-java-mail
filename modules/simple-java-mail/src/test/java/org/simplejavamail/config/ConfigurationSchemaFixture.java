package org.simplejavamail.config;

import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent literal examples for each declared input grammar; no schema parser or production projection supplies expected values. */
final class ConfigurationSchemaFixture {
    private static final UUID CLUSTER = UUID.fromString("00000000-0000-0000-0000-000000000867");
    private static final EnumSet<Property> MILLISECONDS = EnumSet.of(Property.DEFAULT_SESSION_TIMEOUT_MILLIS,
            Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS,
            Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, Property.DEFAULT_POOL_KEEP_ALIVE_TIME,
            Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS,
            Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS);

    final Property property;
    final ConfigKey<?> key;
    private final Class<?> type;
    private final String sourceKey;
    private final Object lowerRaw;
    private final Object upperRaw;
    private final Object expected;

    private ConfigurationSchemaFixture(final Property property, final ConfigKey<?> key, final Class<?> type,
            final String sourceKey, final Object lowerRaw, final Object upperRaw, final Object expected) {
        this.property = property;
        this.key = key;
        this.type = type;
        this.sourceKey = sourceKey;
        this.lowerRaw = lowerRaw;
        this.upperRaw = upperRaw;
        this.expected = expected;
    }

    static ConfigurationSchemaFixture forProperty(final Property property) throws Exception {
        final Field field = Arrays.stream(ConfigKeys.class.getFields()).filter(candidate -> candidate.getType() == ConfigKey.class)
                .filter(candidate -> matches(candidate, property)).findFirst()
                .orElseThrow(() -> new AssertionError("No typed field for " + property));
        final ConfigKey<?> key = (ConfigKey<?>) field.get(null);
        final Type declared = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        final Class<?> type = declared instanceof Class<?> ? (Class<?>) declared : (Class<?>) ((ParameterizedType) declared).getRawType();
        if (property == Property.EXTRA_PROPERTIES) {
            return new ConfigurationSchemaFixture(property, key, type,
                    "simplejavamail.extraproperties.application.schema-marker", "lower", "upper", Map.of("application.schema-marker", "upper"));
        }
        if (property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
            return new ConfigurationSchemaFixture(property, key, type,
                    "simplejavamail.defaults.connectionpool.clusters." + CLUSTER + ".maxsize", "3", "7", 7);
        }
        if (property == Property.NAMED_MAILERS) {
            return new ConfigurationSchemaFixture(property, key, type,
                    "simplejavamail.mailers.schema-backup.smtp.host", "lower.example.test", "upper.example.test", "upper.example.test");
        }
        if (type == String.class) {
            return scalar(property, key, type, "lower-" + property.name(), "upper-" + property.name(), "upper-" + property.name());
        }
        if (type == Boolean.class) { return scalar(property, key, type, "no", "YES", true); }
        if (type == Integer.class) { return scalar(property, key, type, "3", "7", 7); }
        if (type == Long.class) { return scalar(property, key, type, "5000000001", "5000000003", 5_000_000_003L); }
        if (type == Double.class) { return scalar(property, key, type, "0.25", "0.75", 0.75d); }
        if (type == Duration.class) {
            return MILLISECONDS.contains(property) ? scalar(property, key, type, "123", "2123", Duration.ofMillis(2123))
                    : scalar(property, key, type, "PT1.001S", "PT2.123S", Duration.ofMillis(2123));
        }
        if (type == TransportStrategy.class) { return enumeration(property, key, TransportStrategy.SMTP, TransportStrategy.SMTPS); }
        if (type == ConnectionConflictPolicy.class) {
            return enumeration(property, key, ConnectionConflictPolicy.REJECT_CONFLICTS, ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
        }
        if (type == SessionDebugOutput.class) { return enumeration(property, key, SessionDebugOutput.STDOUT, SessionDebugOutput.STDERR); }
        if (type == ContentTransferEncoding.class) { return scalar(property, key, type, "7bit", "base64", ContentTransferEncoding.BASE_64); }
        if (type == DeliveryStatusNotification.ReturnOption.class) {
            return scalar(property, key, type, "FULL", "HDRS", DeliveryStatusNotification.ReturnOption.HEADERS_ONLY);
        }
        if (type == AsyncQueueOverflowPolicy.class) {
            return enumeration(property, key, AsyncQueueOverflowPolicy.REJECT, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY);
        }
        if (type == LoadBalancingStrategy.class) { return enumeration(property, key, LoadBalancingStrategy.ROUND_ROBIN, LoadBalancingStrategy.RANDOM_ACCESS); }
        if (type == DkimConfig.Canonicalization.class) { return enumeration(property, key, DkimConfig.Canonicalization.SIMPLE, DkimConfig.Canonicalization.RELAXED); }
        throw new AssertionError("A new input grammar needs an independent fixture: " + property + " / " + declared);
    }

    Map<String, Object> lowerSource() { return Map.of(sourceKey, lowerRaw); }
    Map<String, Object> upperSource() { return Map.of(sourceKey, upperRaw); }

    void assertUpperSelection(final SimpleJavaMailConfig config) {
        final Object actual = config.get(key);
        assertThat(config.hasProperty(key)).as(property.name()).isTrue();
        assertThat(actual).as(property.name()).isInstanceOf(type);
        if (property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
            final Map<?, ?> clusters = (Map<?, ?>) actual;
            assertThat(clusters).hasSize(1);
            assertThat(clusters.containsKey(CLUSTER)).isTrue();
            assertThat(((ConnectionPoolClusterConfig) clusters.get(CLUSTER)).getMaxSize()).isEqualTo(expected);
            assertThat(config.getPropertySource(key)).isEqualTo("upper schema source");
        } else if (property == Property.NAMED_MAILERS) {
            final Map<?, ?> named = (Map<?, ?>) actual;
            assertThat(named).hasSize(1);
            assertThat(named.containsKey("schema-backup")).isTrue();
            final SimpleJavaMailConfig child = (SimpleJavaMailConfig) named.get("schema-backup");
            assertThat(child.get(ConfigKeys.SMTP_HOST)).isEqualTo(expected);
            assertThat(child.getPropertySource(ConfigKeys.SMTP_HOST)).isEqualTo("upper schema source");
            assertThat(config.getPropertySource(key)).isNull();
        } else {
            assertThat(actual).as(property.name()).isEqualTo(expected);
            assertThat(config.getPropertySource(key)).isEqualTo("upper schema source");
            final Object expectedStorage = MILLISECONDS.contains(property) ? 2123 : expected;
            assertThat(config.asMap().get(property)).as("parser representation of %s", property).isEqualTo(expectedStorage);
        }
    }

    private static boolean matches(final Field field, final Property property) {
        try { return ((ConfigKey<?>) field.get(null)).key().equals(property.key()); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }

    private static ConfigurationSchemaFixture scalar(final Property property, final ConfigKey<?> key, final Class<?> type,
            final Object lower, final Object upper, final Object expected) {
        return new ConfigurationSchemaFixture(property, key, type, property.key(), lower, upper, expected);
    }

    private static ConfigurationSchemaFixture enumeration(final Property property, final ConfigKey<?> key,
            final Enum<?> lower, final Enum<?> upper) {
        return scalar(property, key, upper.getDeclaringClass(), lower.name(), upper.name(), upper);
    }
}
