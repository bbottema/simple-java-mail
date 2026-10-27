package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.configresolution.ConfigurationOrigin.Kind.DEFAULT;
import static org.simplejavamail.internal.configresolution.ConfigurationOrigin.Kind.OVERRIDE;
import static org.simplejavamail.internal.configresolution.ConfigurationOrigin.Kind.SUBMITTED;

class HeaderSettingsTest {
    @Test
    void captureRetainsLogicalIdentityOriginalSpellingAndDetachedSourceValues() {
        final List<String> values = new ArrayList<>(List.of("first", "second"));
        final List<ConfigurationDeclaration<List<String>>> captured = HeaderSettings.capture(
                Map.of("X-Route", values), SUBMITTED, "outer message", true);
        values.clear();

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getSetting()).isEqualTo(HeaderSettings.named("x-route"));
        assertThat(captured.get(0).getOrigin().getProperty()).isEqualTo("X-Route");
        assertThat(captured.get(0).getOrigin().description()).contains("outer message");
        assertThat(captured.get(0).getChoice().valueOrNull()).containsExactly("first", "second");
        assertThatThrownBy(() -> captured.get(0).getChoice().valueOrNull().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void signedContentIsResolvedBeforeFlatteningAndRetainsBothDeclarations() {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>(HeaderSettings.capture(
                Map.of("X-Route", List.of("outer")), SUBMITTED, "outer message", true));
        declarations.addAll(HeaderSettings.capture(Map.of("x-route", List.of("inner-one", "inner-two")),
                SUBMITTED, "inner signed content", true));

        final List<ConfigurationResolution<List<String>>> resolutions = HeaderSettings.resolve(declarations, List.of(SUBMITTED));
        assertThat(resolutions).hasSize(1);
        assertThat(resolutions.get(0).getDeclarations()).hasSize(2);
        assertThat(resolutions.get(0).valueOrNull()).containsExactly("inner-one", "inner-two");
        assertThat(HeaderSettings.snapshot(resolutions)).containsExactly(Map.entry("X-Route", List.of("inner-one", "inner-two")));
    }

    @Test
    void legacyLastMatchingEmptyListIsPresentRatherThanAbsent() {
        final Map<String, Collection<String>> legacy = new LinkedHashMap<>();
        legacy.put("X-Route", List.of("first"));
        legacy.put("x-route", List.of());

        final List<ConfigurationResolution<List<String>>> resolutions = HeaderSettings.resolve(
                HeaderSettings.capture(legacy, SUBMITTED, "legacy snapshot", true), List.of(SUBMITTED));
        assertThat(resolutions.get(0).getChoice().getState()).isEqualTo(ConfigurationChoice.State.SET);
        assertThat(resolutions.get(0).valueOrNull()).isEmpty();
        assertThat(HeaderSettings.snapshot(resolutions)).containsExactly(Map.entry("X-Route", List.of()));
    }

    @Test
    void templateEligibilityControlsSelectionAndDisplaySpellingWithoutLosingSuppressedDeclarations() {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>(HeaderSettings.capture(
                Map.of("X-ROUTE", List.of("default")), DEFAULT, "default template", false));
        declarations.addAll(HeaderSettings.capture(Map.of("X-Route", List.of("submitted")), SUBMITTED, "submitted Email", true));
        declarations.addAll(HeaderSettings.capture(Map.of("x-route", List.of("")), OVERRIDE, "override template", true));

        final List<ConfigurationResolution<List<String>>> resolutions = HeaderSettings.resolve(declarations, List.of(OVERRIDE, SUBMITTED, DEFAULT));
        assertThat(resolutions.get(0).getDeclarations()).hasSize(3);
        assertThat(HeaderSettings.snapshot(resolutions)).containsExactly(Map.entry("X-Route", List.of("")));
    }

    @Test
    void foreignSettingCannotEnterHeaderResolutionEvenWhenIneligible() {
        final ConfigurationDeclaration<List<String>> foreign = ConfigurationDeclaration.of(
                ConfigurationSetting.of("mailer", "X-Route"), ConfigurationChoice.<List<String>>inherit(),
                ConfigurationOrigin.of(DEFAULT, "X-Route", "wrong feature")).withEligibility(false);
        assertThatThrownBy(() -> HeaderSettings.resolve(List.of(foreign), List.of(DEFAULT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header setting");
    }

    @Test
    void aForeignResolutionCannotBeProjectedAsAHeaderSnapshot() {
        final ConfigurationSetting<List<String>> setting = ConfigurationSetting.of("mailer", "X-Route");
        final ConfigurationDeclaration<List<String>> declaration = ConfigurationDeclaration.of(setting,
                ConfigurationChoice.set(List.of("foreign value")), ConfigurationOrigin.of(SUBMITTED, "X-Route", "wrong feature"));
        final ConfigurationResolution<List<String>> resolution = ConfigurationResolver.resolve(setting, List.of(declaration), List.of(SUBMITTED));

        assertThatThrownBy(() -> HeaderSettings.snapshot(List.of(resolution))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header setting");
    }
}
