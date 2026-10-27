package org.simplejavamail.internal.configresolution;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredValueResolutionTest {
    private static final ConfigurationSetting<String> FIELD = ConfigurationSetting.of("test", "value");
    @Test
    void everyScalarLayerAndLocalChoiceRetainsItsIndependentMeaning() {
        final String[] values = {null, "alpha", "beta"};
        for (String captured : values) {
            final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("deployment",
                    captured == null ? Map.of() : Map.of("simplejavamail.smtp.host", captured)).load();
            for (int local = 0; local < 4; local++) {
                for (String fallback : values) {
                    final ConfigurationChoice<String> choice = local == 0 ? ConfigurationChoice.inherit()
                            : local == 3 ? ConfigurationChoice.remove() : ConfigurationChoice.set(values[local]);
                    final String expected = local == 3 ? null : local != 0 ? values[local] : captured != null ? captured : fallback;
                    final ConfigurationOrigin.Kind expectedSource = local != 0 ? ConfigurationOrigin.Kind.JAVA
                            : captured != null ? ConfigurationOrigin.Kind.FACTORY : ConfigurationOrigin.Kind.BUILT_IN;
                    final ConfigurationResolution<String> resolution = ConfigurationResolver.resolveValue(
                            config, ConfigKeys.SMTP_HOST, choice, fallback, "withSMTPServerHost");
                    assertThat(resolution.valueOrNull()).as("captured=%s local=%s fallback=%s", captured, local, fallback).isEqualTo(expected);
                    assertThat(resolution.getSelected().getOrigin().getKind()).isEqualTo(expectedSource);
                    assertThat(resolution.getDeclarations()).hasSize(3);
                    assertThat(resolution.getDeclarations().get(0).getChoice()).isSameAs(choice);
                    assertThat(resolution.getDeclarations().get(1).getChoice().valueOrNull()).isEqualTo(captured);
                    assertThat(resolution.getDeclarations().get(2).getChoice().valueOrNull()).isEqualTo(fallback);
                    assertThat(resolution.getDeclarations().get(2).getOrigin().isExplicit()).isFalse();
                }
            }
        }
    }

    @Test
    void emptyZeroAndFalseAreSuppliedChoicesRatherThanAbsence() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "configured", "simplejavamail.defaults.trustallhosts", true,
                "simplejavamail.defaults.connectionpool.coresize", 7)).load();
        assertThat(ConfigurationResolver.resolveValue(config, ConfigKeys.SMTP_HOST,
                ConfigurationChoice.ofNullable(""), "fallback", "withSMTPServerHost").valueOrNull()).isEmpty();
        assertThat(ConfigurationResolver.resolveValue(config, ConfigKeys.DEFAULT_TRUST_ALL_HOSTS,
                ConfigurationChoice.ofNullable(false), true, "trustingAllHosts").valueOrNull()).isFalse();
        assertThat(ConfigurationResolver.resolveValue(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
                ConfigurationChoice.ofNullable(0), 3, "withCoreSize").valueOrNull()).isZero();
    }

    @Test
    void typedDurationAndLockedSourceSurviveSelectionWithoutInventedValidation() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("platform policy", Map.of(
                "simplejavamail.locked.defaults.readtimeoutmillis", 123)).load();
        final ConfigurationResolution<Duration> selected = ConfigurationResolver.resolveValue(config, ConfigKeys.READ_TIMEOUT,
                ConfigurationChoice.set(Duration.ofMillis(456)), Duration.ofMinutes(1), "withReadTimeout");
        assertThat(selected.valueOrNull()).isEqualTo(Duration.ofMillis(456));
        // The feature owner receives the competing lock declaration and remains responsible for rejecting the disagreement.
        assertThat(selected.getDeclarations().get(1).getChoice().valueOrNull()).isEqualTo(Duration.ofMillis(123));
        assertThat(selected.getDeclarations().get(1).getOrigin().description())
                .contains("simplejavamail.locked.defaults.readtimeoutmillis", "platform policy");
    }

    @Test
    void nullableDraftConversionKeepsExplicitClearSeparateFromRestoration() {
        assertThat(ConfigurationChoice.ofNullable(null).getState()).isEqualTo(ConfigurationChoice.State.INHERIT);
        assertThat(ConfigurationChoice.ofNullable("").getState()).isEqualTo(ConfigurationChoice.State.SET);
        assertThat(ConfigurationChoice.remove().getState()).isEqualTo(ConfigurationChoice.State.REMOVE);
        final SimpleJavaMailConfig empty = ConfigLoader.builder().load();
        final ConfigurationResolution<String> absent = ConfigurationResolver.resolveValue(empty, ConfigKeys.SMTP_HOST,
                ConfigurationChoice.inherit(), null, "withSMTPServerHost");
        assertThat(absent.getSelected().getOrigin().getKind()).isEqualTo(ConfigurationOrigin.Kind.BUILT_IN);
        assertThat(absent.valueOrNull()).isNull();
        assertThat(absent.getSelected().getOrigin().isExplicit()).isFalse();
    }

    @Test
    void contributionOrderingPreservesTiesEligibilityAndExplicitRemoval() {
        final List<ConfigurationOrigin.Kind> precedence = List.of(ConfigurationOrigin.Kind.OVERRIDE,
                ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.DEFAULT);
        final var defaults = contribution(ConfigurationOrigin.Kind.DEFAULT, ConfigurationChoice.set("default"));
        final var firstJava = contribution(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("first"));
        final var secondJava = contribution(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.set("second"));
        final var overrides = contribution(ConfigurationOrigin.Kind.OVERRIDE, ConfigurationChoice.set("override"));
        final var inherited = contribution(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.<String>inherit());
        final var removed = contribution(ConfigurationOrigin.Kind.JAVA, ConfigurationChoice.<String>remove());
        final List<ContributionCase> cases = List.of(
                new ContributionCase("empty capture", List.of(), List.of()),
                new ContributionCase("feature order beats capture order", List.of(defaults, firstJava, overrides, secondJava),
                        List.of(overrides, firstJava, secondJava, defaults)),
                new ContributionCase("already ordered contributions remain ordered", List.of(overrides, firstJava, secondJava, defaults),
                        List.of(overrides, firstJava, secondJava, defaults)),
                new ContributionCase("inherit contributes nothing", List.of(defaults, inherited, overrides), List.of(overrides, defaults)),
                new ContributionCase("excluded override does not participate", List.of(overrides.withEligibility(false), firstJava, defaults),
                        List.of(firstJava, defaults)),
                new ContributionCase("remove remains a contribution", List.of(defaults, removed, overrides), List.of(overrides, removed, defaults)),
                new ContributionCase("only inherited or excluded choices", List.of(inherited, overrides.withEligibility(false)), List.of()),
                new ContributionCase("equal-rank mixed choices retain input order", List.of(secondJava, removed, firstJava),
                        List.of(secondJava, removed, firstJava)));
        final Set<ConfigurationChoice.State> representedStates = EnumSet.noneOf(ConfigurationChoice.State.class);
        final Set<Boolean> representedEligibility = new HashSet<>();
        for (ContributionCase scenario : cases) {
            final List<ConfigurationDeclaration<String>> contributions = ConfigurationResolver.eligibleDeclarations(FIELD, scenario.declarations, precedence);
            assertThat(contributions).as(scenario.description).containsExactlyElementsOf(scenario.expected);
            assertThatThrownBy(() -> contributions.clear()).isInstanceOf(UnsupportedOperationException.class);
            for (ConfigurationDeclaration<String> declaration : scenario.declarations) {
                representedStates.add(declaration.getChoice().getState());
                representedEligibility.add(declaration.isEligible());
            }
        }
        assertThat(representedStates).containsExactlyInAnyOrder(ConfigurationChoice.State.values());
        assertThat(representedEligibility).containsExactlyInAnyOrder(true, false);
    }

    private static ConfigurationDeclaration<String> contribution(final ConfigurationOrigin.Kind source, final ConfigurationChoice<String> choice) {
        return ConfigurationDeclaration.of(FIELD, choice, ConfigurationOrigin.of(source, "value", null));
    }

    private static final class ContributionCase {
        private final String description;
        private final List<ConfigurationDeclaration<String>> declarations;
        private final List<ConfigurationDeclaration<String>> expected;

        private ContributionCase(final String description, final List<ConfigurationDeclaration<String>> declarations,
                final List<ConfigurationDeclaration<String>> expected) {
            this.description = description;
            this.declarations = declarations;
            this.expected = expected;
        }
    }

    @Test
    void anUnclassifiedEligibleSourceFailsWhileExcludedOrInheritedSourcesRemainCaptured() {
        final ConfigurationOrigin origin = ConfigurationOrigin.of(ConfigurationOrigin.Kind.OVERRIDE, "value", null);
        final ConfigurationDeclaration<String> applicable = ConfigurationDeclaration.of(FIELD, ConfigurationChoice.set("chosen"), origin);
        final List<ConfigurationOrigin.Kind> precedence = List.of(ConfigurationOrigin.Kind.JAVA);
        assertThatThrownBy(() -> ConfigurationResolver.eligibleDeclarations(FIELD, List.of(applicable), precedence))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OVERRIDE");
        assertThat(ConfigurationResolver.eligibleDeclarations(FIELD, List.of(applicable.withEligibility(false)), precedence)).isEmpty();
        assertThat(ConfigurationResolver.eligibleDeclarations(FIELD, List.of(ConfigurationDeclaration.of(FIELD, ConfigurationChoice.inherit(), origin)), precedence)).isEmpty();
    }
}
