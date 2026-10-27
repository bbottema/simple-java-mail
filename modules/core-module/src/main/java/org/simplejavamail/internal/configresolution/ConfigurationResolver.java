package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Pure selection with feature-owned precedence. Interpretation and requirements remain at the feature boundary. */
@ApiStatus.Internal
public final class ConfigurationResolver {
    private static final List<ConfigurationOrigin.Kind> JAVA_CONFIGURED_DEFAULT = List.of(
            ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.BUILT_IN);

    private ConfigurationResolver() { }

    /**
     * Selects a completed local choice, a captured typed setting, or the owning feature's built-in fallback.
     * Explicit removal blocks lower layers. A null fallback is retained as built-in absence; it is not a local clear.
     * This method selects only: feature owners continue to validate locks, complete compound values and activate resources.
     */
    public static <T> ConfigurationResolution<T> resolveValue(final SimpleJavaMailConfig config, final ConfigKey<T> key,
            final ConfigurationChoice<T> localChoice, @Nullable final T fallback, final String setter) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.configured(key);
        final ConfigurationDeclaration<T> builtIn = ConfigurationDeclaration.of(setting,
                fallback == null ? ConfigurationChoice.remove() : ConfigurationChoice.set(fallback),
                ConfigurationOrigin.of(ConfigurationOrigin.Kind.BUILT_IN, key.key(), "built-in fallback"));
        return resolve(setting, List.of(ConfigurationDeclaration.javaChoice(setting, localChoice, setter),
                ConfigurationDeclaration.configured(setting, config, key), builtIn), JAVA_CONFIGURED_DEFAULT);
    }

    public static <T> ConfigurationResolution<T> resolve(final ConfigurationSetting<T> setting,
            final List<ConfigurationDeclaration<T>> declarations,
            final List<ConfigurationOrigin.Kind> precedence) {
        final List<ConfigurationDeclaration<T>> eligible = eligibleDeclarations(setting, declarations, precedence);
        return new ConfigurationResolution<>(setting, declarations, eligible.isEmpty() ? null : eligible.get(0));
    }

    /**
     * Orders applicable declarations for feature-owned additive or keyed merging without comparing or rendering their values.
     * Inherit contributes nothing, while Remove remains an eligible declaration for the feature to interpret at its own boundary.
     * Ties retain input order and the returned list is immutable; excluded declarations remain available in the caller's capture.
     */
    public static <T> List<ConfigurationDeclaration<T>> eligibleDeclarations(final ConfigurationSetting<T> setting,
            final List<ConfigurationDeclaration<T>> declarations,
            final List<ConfigurationOrigin.Kind> precedence) {
        requireNonNull(setting, "setting");
        final List<ConfigurationDeclaration<T>> eligible = new ArrayList<>();
        for (ConfigurationDeclaration<T> declaration : declarations) {
            if (!setting.equals(declaration.getSetting())) {
                throw new IllegalArgumentException("Configuration declaration for " + declaration.getSetting() + " cannot resolve " + setting);
            }
            if (!declaration.isEligible() || declaration.getChoice().getState() == ConfigurationChoice.State.INHERIT) {
                continue;
            }
            final int rank = precedence.indexOf(declaration.getOrigin().getKind());
            if (rank < 0) {
                throw new IllegalArgumentException("No precedence for configuration source " + declaration.getOrigin().getKind());
            }
            eligible.add(declaration);
        }
        eligible.sort(Comparator.comparingInt(declaration -> precedence.indexOf(declaration.getOrigin().getKind())));
        return List.copyOf(eligible);
    }
}
