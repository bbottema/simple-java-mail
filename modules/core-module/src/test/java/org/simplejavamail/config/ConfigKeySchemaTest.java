package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigKeySchemaTest {
    @Test
    void everyTextualPropertyHasOneOpaqueCanonicalTypedKey() throws Exception {
        final Set<String> names = new HashSet<>();
        for (final Field field : ConfigKeys.class.getFields()) {
            if (field.getType() == ConfigKey.class && Modifier.isStatic(field.getModifiers())) {
                final ConfigKey<?> key = (ConfigKey<?>) field.get(null);
                assertThat(names.add(key.key())).as(field.getName()).isTrue();
                assertThat(key.toString()).isEqualTo(key.key());
            }
        }
        assertThat(names).containsExactlyInAnyOrder(java.util.Arrays.stream(ConfigLoader.Property.values())
                .map(ConfigLoader.Property::key).toArray(String[]::new));
        assertThat(ConfigKey.class.getConstructors()).isEmpty();
        assertThat(Modifier.isFinal(ConfigKey.class.getModifiers())).isTrue();
    }

    @Test
    void readsScalarTypesWithoutCallerChosenConversions() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SMTP_PORT.key(), "2525", ConfigKeys.SMTP_HOST.key(), "relay.example.test",
                ConfigKeys.JAVAXMAIL_DEBUG.key(), "YES", ConfigKeys.TRANSPORT_STRATEGY.key(), "SMTPS_OAUTH2",
                ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT.key(), "9000000000", ConfigKeys.SMTP_RETRY_JITTER.key(), "0.25")).load();
        assertThat(config.get(ConfigKeys.SMTP_PORT)).isEqualTo(2525);
        assertThat(config.get(ConfigKeys.SMTP_HOST)).isEqualTo("relay.example.test");
        assertThat(config.get(ConfigKeys.JAVAXMAIL_DEBUG)).isTrue();
        assertThat(config.get(ConfigKeys.TRANSPORT_STRATEGY)).isSameAs(TransportStrategy.SMTPS_OAUTH2);
        assertThat(config.get(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT)).isEqualTo(9_000_000_000L);
        assertThat(config.get(ConfigKeys.SMTP_RETRY_JITTER)).isEqualTo(0.25d);
    }

    @Test
    void absentScalarsAndAggregatesStayAbsentWithoutInventingDefaults() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().load();
        assertThat(config.get(ConfigKeys.SMTP_PORT)).isNull();
        assertThat(config.get(ConfigKeys.SESSION_TIMEOUT)).isNull();
        assertThat(config.get(ConfigKeys.CONNECT_TIMEOUT)).isNull();
        assertThat(config.get(ConfigKeys.READ_TIMEOUT)).isNull();
        assertThat(config.get(ConfigKeys.WRITE_TIMEOUT)).isNull();
        assertThat(config.get(ConfigKeys.EXTRA_PROPERTIES)).isNull();
        assertThat(config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).isNull();
        assertThat(config.get(ConfigKeys.NAMED_MAILERS)).isNull();
        assertThat(config.hasProperty(ConfigKeys.EXTRA_PROPERTIES)).isFalse();
        assertThat(config.getPropertySource(ConfigKeys.SMTP_PORT)).isNull();
        assertThat(config.asMap()).isEmpty();
    }

    @Test
    void invariantFallbackSelectionPreservesExplicitConfiguredAndFallbackPrecedence() {
        final SimpleJavaMailConfig configured = ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SMTP_PORT.key(), 2525, ConfigKeys.SMTP_HOST.key(), "configured.example.test",
                ConfigKeys.JAVAXMAIL_DEBUG.key(), true, ConfigKeys.SESSION_TIMEOUT.key(), 1250)).load();
        assertThat(configured.valueOrProperty(0, ConfigKeys.SMTP_PORT, 25)).isZero();
        assertThat(configured.valueOrProperty(null, ConfigKeys.SMTP_PORT, 25)).isEqualTo(2525);
        assertThat(configured.valueOrProperty(null, ConfigKeys.SMTP_PORT)).isEqualTo(2525);
        assertThat(configured.valueOrProperty("", ConfigKeys.SMTP_HOST, "fallback")).isEqualTo("configured.example.test");
        assertThat(configured.valueOrProperty(" ", ConfigKeys.SMTP_HOST, "fallback")).isEqualTo(" ");
        assertThat(configured.valueOrProperty(false, ConfigKeys.JAVAXMAIL_DEBUG, true)).isFalse();
        assertThat(configured.valueOrProperty(null, ConfigKeys.SESSION_TIMEOUT, Duration.ofSeconds(4))).isEqualTo(Duration.ofMillis(1250));
        final SimpleJavaMailConfig empty = ConfigLoader.builder().load();
        assertThat(empty.valueOrProperty(null, ConfigKeys.SMTP_PORT, 25)).isEqualTo(25);
        assertThat(empty.valueOrProperty(null, ConfigKeys.SMTP_PORT)).isNull();
        final Map<String, String> supplied = new LinkedHashMap<>();
        assertThat(configured.valueOrProperty(supplied, ConfigKeys.EXTRA_PROPERTIES, null)).isSameAs(supplied);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE})
    void allMillisecondKeysProjectExactlyAndRetainRawUnits(final int millis) {
        for (final ConfigKey<Duration> key : List.of(ConfigKeys.SESSION_TIMEOUT, ConfigKeys.CONNECT_TIMEOUT, ConfigKeys.READ_TIMEOUT,
                ConfigKeys.WRITE_TIMEOUT, ConfigKeys.ASYNC_QUEUE_WAIT_TIMEOUT,
                ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME, ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT,
                ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER, ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION)) {
            final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("milliseconds", Map.of(key.key(), Integer.toString(millis))).load();
            assertThat(config.get(key)).as(key.key()).isEqualTo(Duration.ofMillis(millis));
            assertThat(config.getPropertySource(key)).isEqualTo("milliseconds");
            assertThat(config.asMap().get(key.property())).isEqualTo(millis);
            final SimpleJavaMailConfig reloaded = ConfigLoader.builder().withConfig(config).load();
            assertThat(reloaded.get(key)).isEqualTo(Duration.ofMillis(millis));
            assertThat(reloaded.asMap().get(key.property())).isEqualTo(millis);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "0.5", "PT1S"})
    void legacyMillisecondTextDoesNotSilentlyOverflowTruncateOrChangeSyntax(final String input) {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap("source", Map.of(ConfigKeys.SESSION_TIMEOUT.key(), input)).load())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(ConfigKeys.SESSION_TIMEOUT.key(), "integer", "source");
    }

    @Test
    void typedJavaReadDoesNotChangeDynamicInputTypesOrIsoDurationPrecision() {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of(ConfigKeys.SESSION_TIMEOUT.key(), Duration.ofMillis(1))).load())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer");
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SMTP_RETRY_DELAY.key(), "PT0.000000001S",
                ConfigKeys.FAILOVER_PRIMARY_RECHECK_INTERVAL.key(), Duration.ofNanos(2))).load();
        assertThat(config.get(ConfigKeys.SMTP_RETRY_DELAY)).isEqualTo(Duration.ofNanos(1));
        assertThat(config.get(ConfigKeys.FAILOVER_PRIMARY_RECHECK_INTERVAL)).isEqualTo(Duration.ofNanos(2));
    }

    @Test
    void locksSourcesAndNamedIsolationSurviveProjectionAndSnapshotRoundTrips() {
        final Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("simplejavamail.smtp.host", "root.example.test");
        inputs.put("simplejavamail.defaults.sessiontimeoutmillis", 100);
        inputs.put("simplejavamail.locked.defaults.sessiontimeoutmillis", 2500);
        inputs.put("simplejavamail.mailers.secondary.smtp.port", 465);
        inputs.put("simplejavamail.locked.mailers.secondary.defaults.sessiontimeoutmillis", 3500);
        final SimpleJavaMailConfig first = ConfigLoader.builder().withMap("company", inputs).load();
        inputs.put("simplejavamail.locked.defaults.sessiontimeoutmillis", 9999);
        assertThat(first.get(ConfigKeys.SESSION_TIMEOUT)).isEqualTo(Duration.ofMillis(2500));
        assertThat(first.getPropertySource(ConfigKeys.SESSION_TIMEOUT)).isEqualTo("company");
        final Map<String, SimpleJavaMailConfig> named = first.get(ConfigKeys.NAMED_MAILERS);
        final SimpleJavaMailConfig secondary = named.get("secondary");
        assertThat(secondary.get(ConfigKeys.SMTP_PORT)).isEqualTo(465);
        assertThat(secondary.get(ConfigKeys.SMTP_HOST)).isNull();
        assertThat(secondary.get(ConfigKeys.SESSION_TIMEOUT)).isEqualTo(Duration.ofMillis(3500));
        assertThatThrownBy(() -> named.clear()).isInstanceOf(UnsupportedOperationException.class);
        final SimpleJavaMailConfig restored = ConfigLoader.builder().withConfig(first).load();
        assertThat(restored.get(ConfigKeys.SESSION_TIMEOUT)).isEqualTo(Duration.ofMillis(2500));
        assertThat(restored.get(ConfigKeys.NAMED_MAILERS).get("secondary").get(ConfigKeys.SESSION_TIMEOUT)).isEqualTo(Duration.ofMillis(3500));
        assertThat(first.getDiagnostics().toString()).contains("locked.defaults.sessiontimeoutmillis", "2500", "company");
        assertThat(restored.getDiagnostics().toString()).contains("locked.defaults.sessiontimeoutmillis", "2500", "existing SimpleJavaMailConfig");
    }

    @Test
    void aggregateKeyGenericsMatchDetachedExtrasAndClusterValues() {
        final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000123");
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("simplejavamail.extraproperties.mail.smtp.ehlo", "false");
        source.put("simplejavamail.defaults.connectionpool.clusters.orders.clusterkey.uuid", id.toString());
        source.put("simplejavamail.defaults.connectionpool.clusters.orders.claimtimeout.millis", "100");
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(source).load();
        source.put("simplejavamail.extraproperties.mail.smtp.ehlo", "true");
        final Map<String, String> extras = config.get(ConfigKeys.EXTRA_PROPERTIES);
        final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
        assertThat(extras).containsEntry("mail.smtp.ehlo", "false");
        assertThat(clusters.get(id).getClaimTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThatThrownBy(() -> extras.put("new", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(clusters::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> config.asMap().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typedReadsExposeValuesWhileDiagnosticsAndRenderingRetainRedaction() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("company", Map.of(
                ConfigKeys.SMTP_PASSWORD.key(), "private-token",
                "simplejavamail.extraproperties.mail.smtp.password", "private-extra-token")).load();
        assertThat(config.get(ConfigKeys.SMTP_PASSWORD)).isEqualTo("private-token");
        assertThat(config.get(ConfigKeys.EXTRA_PROPERTIES)).containsEntry("mail.smtp.password", "private-extra-token");
        assertThat(config.toString()).doesNotContain("private-token", "private-extra-token");
        assertThat(config.getDiagnostics().toString()).contains("company").doesNotContain("private-token", "private-extra-token");
    }
}
