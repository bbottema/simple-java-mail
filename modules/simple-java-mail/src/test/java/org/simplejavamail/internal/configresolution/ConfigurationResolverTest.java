package org.simplejavamail.internal.configresolution;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationResolverTest {
    private static final ConfigurationSetting<String> FIELD = ConfigurationSetting.of("test", "field");
    private static final List<ConfigurationOrigin.Kind> CLASSIFIED_SOURCES = List.of(
            ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.FACTORY_RAW, ConfigurationOrigin.Kind.SESSION,
            ConfigurationOrigin.Kind.JAVA_RAW, ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN,
            ConfigurationOrigin.Kind.DERIVED, ConfigurationOrigin.Kind.OVERRIDE, ConfigurationOrigin.Kind.SUBMITTED,
            ConfigurationOrigin.Kind.DEFAULT);

    @Test
    void everySourceRoleUsesTheFeatureOrderAndRetainsItsExplicitness() {
        assertThat(CLASSIFIED_SOURCES).containsExactlyInAnyOrder(ConfigurationOrigin.Kind.values());
        for (ConfigurationOrigin.Kind source : CLASSIFIED_SOURCES) {
            final ConfigurationOrigin.Kind lowerRole = source == ConfigurationOrigin.Kind.BUILT_IN
                    ? ConfigurationOrigin.Kind.DERIVED : ConfigurationOrigin.Kind.BUILT_IN;
            final var lower = declaration(lowerRole, ConfigurationChoice.set("fallback"));
            final var preferred = declaration(source, ConfigurationChoice.set("preferred"));
            final var resolution = ConfigurationResolver.resolve(FIELD, List.of(lower, preferred), List.of(source, lowerRole));
            assertThat(resolution.getSelected()).as("source %s", source).isSameAs(preferred);
            assertThat(preferred.getOrigin().isExplicit()).isEqualTo(source != ConfigurationOrigin.Kind.BUILT_IN
                    && source != ConfigurationOrigin.Kind.DERIVED);
        }
    }

    @Test
    void aFeatureCanReversePrecedenceWithoutChangingTheCapturedInputs() {
        final var defaults = declaration(ConfigurationOrigin.Kind.DEFAULT, ConfigurationChoice.set("default"));
        final var submitted = declaration(ConfigurationOrigin.Kind.SUBMITTED, ConfigurationChoice.set("submitted"));
        final var overrides = declaration(ConfigurationOrigin.Kind.OVERRIDE, ConfigurationChoice.set("override"));
        final List<ConfigurationDeclaration<String>> captured = List.of(defaults, submitted, overrides);
        assertThat(ConfigurationResolver.resolve(FIELD, captured, List.of(ConfigurationOrigin.Kind.OVERRIDE,
                ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.DEFAULT)).getSelected()).isSameAs(overrides);
        assertThat(ConfigurationResolver.resolve(FIELD, captured, List.of(ConfigurationOrigin.Kind.DEFAULT,
                ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.OVERRIDE)).getSelected()).isSameAs(defaults);
        assertThat(ConfigurationResolver.resolve(FIELD, captured, List.of(ConfigurationOrigin.Kind.SUBMITTED,
                ConfigurationOrigin.Kind.DEFAULT, ConfigurationOrigin.Kind.OVERRIDE)).getSelected()).isSameAs(submitted);
    }

    @Test
    void choiceAndEligibilityRulesPreserveSelectionAndEveryCapturedDeclaration() {
        final var inherited = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.<String>inherit());
        final var supplied = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("local"));
        final var removed = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.<String>remove());
        final var empty = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set(""));
        final var later = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("later"));
        final var sameValue = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("local"));
        final var fallback = declaration(ConfigurationOrigin.Kind.BUILT_IN, ConfigurationChoice.set("fallback"));
        final List<SelectionCase> cases = List.of(
                new SelectionCase("inherit considers the lower layer", List.of(inherited, fallback), 1),
                new SelectionCase("set wins regardless of capture order", List.of(fallback, supplied), 1),
                new SelectionCase("remove blocks the lower layer", List.of(removed, fallback), 0),
                new SelectionCase("excluded set is retained but skipped", List.of(supplied.withEligibility(false), fallback), 1),
                new SelectionCase("excluded removal cannot block fallback", List.of(removed.withEligibility(false), fallback), 1),
                new SelectionCase("all inherited has no selected declaration", List.of(inherited), -1),
                new SelectionCase("empty capture has no selected declaration", List.of(), -1),
                new SelectionCase("empty text is still supplied", List.of(empty, fallback), 0),
                new SelectionCase("equal ranks retain the first declaration", List.of(supplied, later, fallback), 0),
                new SelectionCase("inherited tie yields to a supplied choice", List.of(inherited, later, fallback), 1),
                new SelectionCase("excluded tie yields to an eligible choice", List.of(supplied.withEligibility(false), later), 1),
                new SelectionCase("removal wins an equal-rank tie", List.of(removed, later, fallback), 0),
                new SelectionCase("equivalent values remain separate declarations", List.of(supplied, sameValue, fallback), 0));
        final Set<ConfigurationChoice.State> representedStates = EnumSet.noneOf(ConfigurationChoice.State.class);
        final Set<Boolean> representedEligibility = new HashSet<>();
        for (SelectionCase scenario : cases) {
            final ConfigurationResolution<String> resolved = ConfigurationResolver.resolve(FIELD, scenario.declarations,
                    List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN));
            final ConfigurationDeclaration<String> expected = scenario.expectedIndex < 0 ? null
                    : scenario.declarations.get(scenario.expectedIndex);
            assertThat(resolved.getSelected()).as(scenario.description).isSameAs(expected);
            assertThat(resolved.getDeclarations()).as(scenario.description).containsExactlyElementsOf(scenario.declarations);
            if (expected != null) { assertThat(resolved.getChoice()).isSameAs(expected.getChoice()); }
            assertThatThrownBy(() -> resolved.getDeclarations().clear()).isInstanceOf(UnsupportedOperationException.class);
            for (ConfigurationDeclaration<String> declaration : scenario.declarations) {
                representedStates.add(declaration.getChoice().getState());
                representedEligibility.add(declaration.isEligible());
            }
        }
        assertThat(representedStates).containsExactlyInAnyOrder(ConfigurationChoice.State.values());
        assertThat(representedEligibility).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void retainedDeclarationsAreDetachedFromTheCaptureList() {
        final var supplied = declaration(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("local"));
        final var fallback = declaration(ConfigurationOrigin.Kind.BUILT_IN, ConfigurationChoice.set("fallback"));
        final List<ConfigurationDeclaration<String>> captured = new ArrayList<>(List.of(supplied, fallback));
        final var resolved = ConfigurationResolver.resolve(FIELD, captured, List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.BUILT_IN));
        captured.clear();
        assertThat(resolved.getDeclarations()).containsExactly(supplied, fallback);
        assertThat(resolved.getSelected()).isSameAs(supplied);
    }

    @Test
    void onlyAnEligibleSuppliedSourceNeedsAnAssignedPrecedence() {
        final var override = declaration(ConfigurationOrigin.Kind.OVERRIDE, ConfigurationChoice.set("value"));
        final var inherited = declaration(ConfigurationOrigin.Kind.OVERRIDE, ConfigurationChoice.<String>inherit());
        final List<ConfigurationOrigin.Kind> roles = List.of(ConfigurationOrigin.Kind.JAVA);
        assertThatThrownBy(() -> ConfigurationResolver.resolve(FIELD, List.of(override), roles))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OVERRIDE");
        assertThat(ConfigurationResolver.resolve(FIELD, List.of(override.withEligibility(false)), roles).getSelected()).isNull();
        assertThat(ConfigurationResolver.resolve(FIELD, List.of(inherited), roles).getSelected()).isNull();
    }

    private static ConfigurationDeclaration<String> declaration(final ConfigurationOrigin.Kind source, final ConfigurationChoice<String> choice) {
        return ConfigurationDeclaration.of(FIELD, choice, ConfigurationOrigin.of(source, "field", source.name()));
    }

    private static final class SelectionCase {
        private final String description;
        private final List<ConfigurationDeclaration<String>> declarations;
        private final int expectedIndex;

        private SelectionCase(final String description, final List<ConfigurationDeclaration<String>> declarations, final int expectedIndex) {
            this.description = description;
            this.declarations = declarations;
            this.expectedIndex = expectedIndex;
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
        final var excluded = ConfigurationDeclaration.javaChoice(FIELD, ConfigurationChoice.set("excluded"), "setter").withEligibility(false);
        final var fallback = ConfigurationDeclaration.of(FIELD, ConfigurationChoice.set("selected"),
                ConfigurationOrigin.of(ConfigurationOrigin.Kind.BUILT_IN, "fallback", null));
        final var result = ConfigurationResolver.resolve(FIELD, List.of(excluded, fallback),
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
        final ConfigurationSetting<Object> setting = ConfigurationSetting.of("test", "opaque");
        final var declaration = ConfigurationDeclaration.javaChoice(setting, ConfigurationChoice.set(value), "typed setter");
        final var result = ConfigurationResolver.resolve(setting, List.of(declaration), List.of(ConfigurationOrigin.Kind.JAVA));
        assertThat(result.valueOrNull()).isSameAs(value);
        assertThat(result.toString()).contains("typed setter");
        assertThatThrownBy(() -> ConfigurationChoice.set(null)).isInstanceOf(NullPointerException.class);
    }
}
