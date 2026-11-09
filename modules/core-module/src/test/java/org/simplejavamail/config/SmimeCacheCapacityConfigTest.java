package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmimeCacheCapacityConfigTest {
    private static final String KEY = "simplejavamail.smime.cache.capacity";
    private static final String LOCKED = "simplejavamail.locked.smime.cache.capacity";

    @Test
    void absenceRemainsAbsentAndThePublishedFallbackIsUnlimited() {
        final SimpleJavaMailConfig empty = ConfigLoader.builder().load();

        assertThat(ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY).isEqualTo(-1);
        assertThat(empty.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isNull();
        assertThat(empty.hasProperty(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isFalse();
        assertThat(empty.getPropertySource(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isNull();
        assertThat(empty.asMap()).doesNotContainKey(ConfigLoader.Property.SMIME_KEY_CACHE_CAPACITY);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, -1, 0, 1, 10000, Integer.MAX_VALUE})
    void theCoreSchemaCapturesIntegerChoicesWithoutActivatingOrValidatingTheFeature(final int capacity) {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("application", Map.of(KEY, capacity)).load();

        assertThat(config.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(capacity);
        assertThat(config.getPropertySource(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo("application");
        assertThat(config.asMap().get(ConfigLoader.Property.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(capacity);
    }

    @Test
    void orderedSourcesAndSnapshotCopyRetainTheSelectedIntegerAndItsVisibleDiagnostics() {
        final Properties lower = new Properties();
        lower.setProperty(KEY, "-1");
        final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties("defaults", lower)
                .withMap("deployment", Map.of(KEY, "10000")).load();
        final SimpleJavaMailConfig copy = ConfigLoader.builder().withConfig(config).load();

        assertThat(config.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(10000);
        assertThat(copy.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(10000);
        final ConfigPropertyDiagnostic diagnostic = config.getDiagnostics().getProperties(ConfigDiagnosticGroup.MESSAGE_SECURITY)
                .stream().filter(entry -> entry.getPropertyName().equals(KEY)).findFirst().orElseThrow();
        assertThat(diagnostic.getDisplayValue()).isEqualTo("10000");
        assertThat(diagnostic.getSourceName()).isEqualTo("deployment");
        assertThat(diagnostic.isRedacted()).isFalse();
    }

    @Test
    void ordinaryOverlaysCannotWeakenTheSelectedLockedCapacity() {
        final SimpleJavaMailConfig config = ConfigLoader.builder()
                .withMap("central", Map.of(LOCKED, "0"))
                .withMap("application", Map.of(KEY, "10000")).load();
        final SimpleJavaMailConfig copy = ConfigLoader.builder().withConfig(config).withMap(Map.of(KEY, -1)).load();

        assertThat(config.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isZero();
        assertThat(copy.get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isZero();
        assertThat(config.getPropertySource(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo("central");
    }

    @Test
    void unsupportedTypesFractionsAndOverflowAreSourceErrors() {
        for (final Object value : List.of(true, 1.5d, 1.0d, 3L, "1.5", "2147483648", "-2147483649")) {
            assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of(KEY, value)).load())
                    .as("capacity input %s", value).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(KEY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simplejavamail.mailers.backup.smime.cache.capacity", "simplejavamail.locked.mailers.backup.smime.cache.capacity"})
    void namedMailersCannotReplaceTheirCreatingFactorysCapacity(final String misplaced) {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of(misplaced, "10")).load())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KEY).hasMessageContaining("factory").hasMessageContaining("root");
    }
}
