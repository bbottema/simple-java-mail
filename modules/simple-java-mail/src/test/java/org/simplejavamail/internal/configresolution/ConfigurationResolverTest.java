package org.simplejavamail.internal.configresolution;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationResolverTest {
    @Test
    void allFiniteConnectionSourceChoicesRetainTheirDeclarations() {
        verifyMatrix(List.of(ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.FACTORY_RAW,
                ConfigurationOrigin.Kind.SESSION, ConfigurationOrigin.Kind.JAVA_RAW,
                ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN), new int[] {4, 3, 1, 2, 0, 5});
    }

    @Test
    void allFiniteSubjectSourceChoicesUseTheirOwnOrder() {
        verifyMatrix(List.of(ConfigurationOrigin.Kind.DEFAULT, ConfigurationOrigin.Kind.SUBMITTED,
                ConfigurationOrigin.Kind.OVERRIDE), new int[] {2, 1, 0});
    }

    private static void verifyMatrix(final List<ConfigurationOrigin.Kind> sources, final int[] priority) {
        final List<ConfigurationOrigin.Kind> precedence = new ArrayList<>();
        for (int index : priority) { precedence.add(sources.get(index)); }
        final int combinations = (int) Math.pow(5, sources.size());
        for (int combination = 0; combination < combinations; combination++) {
            final int[] states = ConfigurationContractModel.digits(combination, 5, sources.size());
            final int[] eligibleStates = states.clone();
            final List<ConfigurationDeclaration<String>> declarations = new ArrayList<>();
            for (int index = 0; index < states.length; index++) {
                declarations.add(ConfigurationDeclaration.of(choice(states[index] == 4 ? 1 : states[index]),
                        ConfigurationOrigin.of(sources.get(index), "field", sources.get(index).name())).withEligibility(states[index] != 4));
                if (states[index] == 4) { eligibleStates[index] = 0; }
            }
            final int expectedIndex = ConfigurationContractModel.selectedIndex(eligibleStates, priority);
            final ConfigurationResolution<String> resolved = ConfigurationResolver.resolve(declarations, precedence);
            assertThat(resolved.getDeclarations()).as("declarations %s", combination).containsExactlyElementsOf(declarations);
            assertThat(resolved.getSelected()).as("selection %s", combination)
                    .isSameAs(expectedIndex < 0 ? null : declarations.get(expectedIndex));
            assertThatThrownBy(() -> resolved.getDeclarations().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static ConfigurationChoice<String> choice(final int state) {
        switch (state) {
            case ConfigurationContractModel.VALUE_A: return ConfigurationChoice.set("alpha");
            case ConfigurationContractModel.VALUE_B: return ConfigurationChoice.set("beta");
            case ConfigurationContractModel.REMOVE: return ConfigurationChoice.remove();
            default: return ConfigurationChoice.inherit();
        }
    }

    @Test
    void configuredReadsStayTypedAndRetainSourceNames() {
        final var config = ConfigLoader.builder().withMap("deployment", Map.of("simplejavamail.defaults.readtimeoutmillis", 123)).load();
        final ConfigurationDeclaration<Duration> declaration = ConfigurationDeclaration.configured(config, ConfigKeys.READ_TIMEOUT);
        assertThat(declaration.getChoice().valueOrNull()).isEqualTo(Duration.ofMillis(123));
        assertThat(declaration.getOrigin().description()).contains("deployment", "simplejavamail.defaults.readtimeoutmillis");
    }

    @Test
    void excludedDeclarationsRetainTheirValuesWithoutParticipatingInSelection() {
        final var excluded = ConfigurationDeclaration.javaChoice(ConfigurationChoice.set("excluded"), "setter").withEligibility(false);
        final var fallback = ConfigurationDeclaration.of(ConfigurationChoice.set("selected"),
                ConfigurationOrigin.of(ConfigurationOrigin.Kind.BUILT_IN, "fallback", null));
        final var result = ConfigurationResolver.resolve(List.of(excluded, fallback),
                List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN));
        assertThat(result.valueOrNull()).isEqualTo("selected");
        assertThat(result.getDeclarations().get(0).getChoice().valueOrNull()).isEqualTo("excluded");
    }

    @Test
    void selectionAndDiagnosticMetadataDoNotInvokeOpaqueValues() {
        final Object value = new Object() {
            @Override public boolean equals(final Object other) { throw new AssertionError("equals callback"); }
            @Override public int hashCode() { throw new AssertionError("hash callback"); }
            @Override public String toString() { throw new AssertionError("render callback"); }
        };
        final var declaration = ConfigurationDeclaration.javaChoice(ConfigurationChoice.set(value), "typed setter");
        final var result = ConfigurationResolver.resolve(List.of(declaration), List.of(ConfigurationOrigin.Kind.JAVA));
        assertThat(result.valueOrNull()).isSameAs(value);
        assertThat(result.toString()).contains("typed setter");
        assertThatThrownBy(() -> ConfigurationChoice.set(null)).isInstanceOf(NullPointerException.class);
    }
}
