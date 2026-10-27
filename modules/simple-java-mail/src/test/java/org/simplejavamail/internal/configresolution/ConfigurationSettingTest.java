package org.simplejavamail.internal.configresolution;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationSettingTest {
    @Test
    void logicalIdentityIncludesTheDomainAndTheOwningFeaturesNameSemantics() {
        final ConfigurationSetting<String> subject = ConfigurationSetting.of("email", "subject");
        assertThat(subject).isEqualTo(ConfigurationSetting.of("email", "subject"));
        assertThat(subject.hashCode()).isEqualTo(ConfigurationSetting.of("email", "subject").hashCode());
        assertThat(subject).isNotEqualTo(ConfigurationSetting.of("email", "body"));
        assertThat(subject).isNotEqualTo(ConfigurationSetting.of("mailer", "subject"));
        assertThat(subject).isNotEqualTo(ConfigurationSetting.of("email", "SUBJECT"));
        assertThat(subject).isNotEqualTo(ConfigurationSetting.caseInsensitive("email", "subject"));
    }

    @Test
    void caseInsensitiveKeysKeepTheExistingTextComparisonAndWorkAsMapKeys() {
        final String[][] equivalentNames = {{"X-Route", "x-route"}, {"I", "\u0131"}, {"\u0130", "i"},
                {"\u03a3", "\u03c2"}, {"\u212a", "k"}, {"\uD801\uDC00", "\uD801\uDC28"}};
        for (String[] names : equivalentNames) {
            final ConfigurationSetting<String> first = ConfigurationSetting.caseInsensitive("headers", names[0]);
            final ConfigurationSetting<String> second = ConfigurationSetting.caseInsensitive("headers", names[1]);
            final boolean equivalent = String.CASE_INSENSITIVE_ORDER.compare(names[0], names[1]) == 0;
            assertThat(first.equals(second)).as("%s / %s", names[0], names[1]).isEqualTo(equivalent);
            if (equivalent) {
                assertThat(first.hashCode()).isEqualTo(second.hashCode());
                final Map<ConfigurationSetting<String>, String> values = new HashMap<>();
                values.put(first, "first");
                values.put(second, "selected");
                assertThat(values).hasSize(1).containsEntry(first, "selected");
            }
            assertThat(first.getName()).isEqualTo(names[0]);
        }
    }

    @Test
    void mixedSettingsAreRejectedEvenWhenTheyHaveTheSameValueTypeOrDoNotParticipate() {
        final ConfigurationSetting<String> subject = ConfigurationSetting.of("email", "subject");
        final ConfigurationSetting<String> body = ConfigurationSetting.of("email", "body");
        final List<ConfigurationChoice<String>> choices = List.of(ConfigurationChoice.inherit(), ConfigurationChoice.set("text"),
                ConfigurationChoice.remove());
        final List<ConfigurationOrigin.Kind> precedence = List.of(ConfigurationOrigin.Kind.JAVA);
        for (ConfigurationChoice<String> choice : choices) {
            for (boolean eligible : new boolean[] {false, true}) {
                final ConfigurationDeclaration<String> declaration = ConfigurationDeclaration.javaChoice(body, choice, "withPlainText")
                        .withEligibility(eligible);
                assertThatThrownBy(() -> ConfigurationResolver.resolve(subject, List.of(declaration), precedence))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email:body").hasMessageContaining("email:subject");
                assertThatThrownBy(() -> ConfigurationResolver.eligibleDeclarations(subject, List.of(declaration), precedence))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("email:body").hasMessageContaining("email:subject");
            }
        }
    }

    @Test
    void explicitLogicalSettingsConnectConfigurationKeysAndJavaWithoutLosingSourceFacts() {
        final ConfigurationSetting<Duration> timeout = ConfigurationSetting.of("connection", "read-timeout");
        final var config = ConfigLoader.builder().withMap("deployment", Map.of("simplejavamail.defaults.readtimeoutmillis", 123)).load();
        final var configured = ConfigurationDeclaration.configured(timeout, config, ConfigKeys.READ_TIMEOUT);
        final var supplied = ConfigurationDeclaration.javaChoice(timeout, ConfigurationChoice.set(Duration.ofSeconds(10)), "withConnectionConfig");
        final var resolution = ConfigurationResolver.resolve(timeout, List.of(configured, supplied),
                List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.FACTORY));
        assertThat(resolution.getSetting()).isEqualTo(timeout);
        assertThat(resolution.getSelected()).isSameAs(supplied);
        assertThat(resolution.getDeclarations()).containsExactly(configured, supplied);
        assertThat(configured.getSetting()).isEqualTo(timeout);
        assertThat(configured.getOrigin().description()).contains("deployment", "simplejavamail.defaults.readtimeoutmillis");
        assertThat(configured.getChoice().valueOrNull()).isEqualTo(Duration.ofMillis(123));
    }

    @Test
    void inheritedResultsRetainTheirTargetAndKeyDiagnosticsDoNotRenderValues() {
        final ConfigurationSetting<Object> setting = ConfigurationSetting.of("test", "opaque");
        final Object value = new Object() {
            @Override public boolean equals(final Object other) { throw new AssertionError("equals callback"); }
            @Override public int hashCode() { throw new AssertionError("hash callback"); }
            @Override public String toString() { throw new AssertionError("render callback"); }
        };
        final var declaration = ConfigurationDeclaration.javaChoice(setting, ConfigurationChoice.set(value), "typed setter");
        final var resolution = ConfigurationResolver.resolve(setting, List.of(declaration), List.of(ConfigurationOrigin.Kind.JAVA));
        assertThat(resolution.getSetting()).isEqualTo(setting);
        assertThat(resolution.valueOrNull()).isSameAs(value);
        assertThat(resolution.toString()).contains("typed setter");
        assertThat(ConfigurationResolver.resolve(setting, List.of(), List.of()).getSetting()).isEqualTo(setting);
    }
}
