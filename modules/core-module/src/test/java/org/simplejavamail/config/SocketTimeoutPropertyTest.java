package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocketTimeoutPropertyTest {
    private static final String[] PROPERTIES = {"simplejavamail.defaults.connecttimeoutmillis",
            "simplejavamail.defaults.readtimeoutmillis", "simplejavamail.defaults.writetimeoutmillis"};

    @Test
    void typedReadsExposeOnlyConfiguredChoicesAndLeaveTheSharedFallbackForMailerResolution() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SESSION_TIMEOUT.key(), 30_000, ConfigKeys.CONNECT_TIMEOUT.key(), 10_000)).load();
        assertThat(config.get(ConfigKeys.SESSION_TIMEOUT)).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.get(ConfigKeys.CONNECT_TIMEOUT)).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.get(ConfigKeys.READ_TIMEOUT)).isNull();
        assertThat(config.get(ConfigKeys.WRITE_TIMEOUT)).isNull();
    }

    @Test
    void perTimeoutLocksAndNamedConfigurationsSurviveSnapshotRoundTrips() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("deployment", Map.of(
                ConfigKeys.CONNECT_TIMEOUT.key(), 10_000,
                "simplejavamail.locked.defaults.readtimeoutmillis", 20_000,
                "simplejavamail.mailers.secondary.defaults.writetimeoutmillis", 30_000)).load();
        final SimpleJavaMailConfig restored = ConfigLoader.builder().withConfig(config).load();
        assertThat(restored.get(ConfigKeys.CONNECT_TIMEOUT)).isEqualTo(Duration.ofSeconds(10));
        assertThat(restored.get(ConfigKeys.READ_TIMEOUT)).isEqualTo(Duration.ofSeconds(20));
        assertThat(restored.get(ConfigKeys.NAMED_MAILERS).get("secondary").get(ConfigKeys.WRITE_TIMEOUT)).isEqualTo(Duration.ofSeconds(30));
        assertThat(restored.get(ConfigKeys.NAMED_MAILERS).get("secondary").get(ConfigKeys.CONNECT_TIMEOUT)).isNull();
        assertThat(org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf(restored)
                .contains(ConfigLoader.Property.DEFAULT_READ_TIMEOUT_MILLIS)).isTrue();
        assertThat(restored.getDiagnostics().toString()).contains("simplejavamail.locked.defaults.readtimeoutmillis = 20000");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, 0, Integer.MAX_VALUE})
    void individualSocketPropertiesRetainSignedMillisecondsAndDiagnosticSources(final int millis) {
        final Map<String, Object> supplied = new LinkedHashMap<>();
        for (final String property : PROPERTIES) {
            supplied.put(property, Integer.toString(millis));
        }
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("socket settings", supplied).load();
        for (final String property : PROPERTIES) {
            assertThat(config.asMap().entrySet()).anySatisfy(entry -> {
                assertThat(entry.getKey().key()).isEqualTo(property);
                assertThat(entry.getValue()).isEqualTo(millis);
            });
            assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION)).anySatisfy(diagnostic -> {
                assertThat(diagnostic.getPropertyName()).isEqualTo(property);
                assertThat(diagnostic.getDisplayValue()).isEqualTo(Integer.toString(millis));
                assertThat(diagnostic.getSourceName()).isEqualTo("socket settings");
                assertThat(diagnostic.isRedacted()).isFalse();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "0.5", "PT1S"})
    void individualSocketPropertiesRejectOverflowFractionsAndIsoSyntax(final String input) {
        for (final String property : PROPERTIES) {
            assertThatThrownBy(() -> ConfigLoader.builder().withMap("socket settings", Map.of(property, input)).load())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(property, "integer", "socket settings");
        }
    }
}
