package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

class ImportLimitsPropertyTest {

    @Test
    void absentPropertiesStayAbsentInsteadOfInventingUnlimitedValues() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().load();
        assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES)).isNull();
        assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isNull();
        assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH)).isNull();
    }

    @Test
    void typedAndTextualInputsUseTheSameWidthsAndVisibleProvenance() {
        for (final boolean textual : new boolean[] {false, true}) {
            final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("import policy", Map.of(
                    ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), textual ? "4294967296" : 4_294_967_296L,
                    ConfigKeys.IMPORT_MAXIMUM_PART_COUNT.key(), textual ? "1000" : 1000,
                    ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH.key(), textual ? "0" : 0)).load();
            assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES)).isEqualTo(4_294_967_296L);
            assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo(1000);
            assertThat(config.get(ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH)).isZero();
            assertThat(config.getPropertySource(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES)).isEqualTo("import policy");
            assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.DIAGNOSTICS_AND_VALIDATION)).anySatisfy(entry -> {
                assertThat(entry.getPropertyName()).isEqualTo(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key());
                assertThat(entry.getDisplayValue()).isEqualTo("4294967296");
                assertThat(entry.getSourceName()).isEqualTo("import policy");
                assertThat(entry.isRedacted()).isFalse();
            });
        }
    }

    @Test
    void orderedSourcesAndSnapshotCopiesKeepCapturedImportChoices() {
        final Map<String, Object> deployment = new LinkedHashMap<>();
        deployment.put(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT.key(), 100);
        final SimpleJavaMailConfig original = ConfigLoader.builder()
                .withMap("application", Map.of(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT.key(), 50))
                .withMap("deployment", deployment).load();
        deployment.put(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT.key(), 200);
        assertThat(original.get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo(100);
        assertThat(original.getPropertySource(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo("deployment");
        assertThat(ConfigLoader.builder().withConfig(original).load().get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo(100);
        assertThat(ConfigLoader.builder().withMap(deployment).load().get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo(200);
    }

    @Test
    void ordinaryAndLockedChoicesRetainIdentityAcrossNamedSnapshotCopies() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("central policy", Map.of(
                ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), 100L,
                "simplejavamail.locked.conversion.import.maximuminputbytes", 50L,
                "simplejavamail.mailers.secondary.conversion.import.maximumpartcount", 25)).load();
        final SimpleJavaMailConfig restored = ConfigLoader.builder().withConfig(config).load();
        assertThat(restored.get(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES)).isEqualTo(50L);
        assertThat(locksOf(restored).contains(ConfigLoader.Property.IMPORT_MAXIMUM_INPUT_BYTES)).isTrue();
        assertThat(config.getDiagnostics().toString()).contains(
                "simplejavamail.locked.conversion.import.maximuminputbytes = 50 (source: central policy)");
        assertThat(restored.getDiagnostics().toString()).contains(
                "simplejavamail.locked.conversion.import.maximuminputbytes = 50 (source: existing SimpleJavaMailConfig)");
        assertThat(restored.get(ConfigKeys.NAMED_MAILERS).get("secondary").get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isEqualTo(25);
        assertThat(restored.get(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "1.5", "true"})
    void structuralCountsRejectInvalidIntegerRepresentations(final String value) {
        for (final ConfigKey<Integer> key : java.util.List.of(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT, ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH)) {
            assertThatThrownBy(() -> ConfigLoader.builder().withMap("import policy", Map.of(key.key(), value)).load())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key.key(), "import policy", "integer");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854775808", "1.5", "true"})
    void inputBytesRejectInvalidLongRepresentations(final String value) {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap("import policy", Map.of(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), value)).load())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), "import policy", "long");
    }
}
