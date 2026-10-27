package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Immutable choice and provenance. Diagnostic rendering never renders its possibly borrowed value. */
@ApiStatus.Internal
public final class ConfigurationDeclaration<T> {
    private final ConfigurationSetting<T> setting;
    private final ConfigurationChoice<T> choice;
    private final ConfigurationOrigin origin;
    private final boolean eligible;

    private ConfigurationDeclaration(final ConfigurationSetting<T> setting, final ConfigurationChoice<T> choice,
            final ConfigurationOrigin origin, final boolean eligible) {
        this.setting = requireNonNull(setting, "setting");
        this.choice = requireNonNull(choice, "choice");
        this.origin = requireNonNull(origin, "origin");
        this.eligible = eligible;
    }

    public static <T> ConfigurationDeclaration<T> of(final ConfigurationSetting<T> setting, final ConfigurationChoice<T> choice,
            final ConfigurationOrigin origin) {
        return new ConfigurationDeclaration<>(setting, choice, origin, true);
    }

    public static <T> ConfigurationDeclaration<T> configured(final SimpleJavaMailConfig config, final ConfigKey<T> key) {
        return configured(ConfigurationSetting.configured(key), config, key);
    }

    public static <T> ConfigurationDeclaration<T> configured(final ConfigurationSetting<T> setting,
            final SimpleJavaMailConfig config, final ConfigKey<T> key) {
        final T value = config.get(key);
        final String name = locksOf(config).getValues().containsKey(key.key()) ? ConfigurationLocks.lockedName(key.key()) : key.key();
        return of(setting, value == null ? ConfigurationChoice.inherit() : ConfigurationChoice.set(value),
                ConfigurationOrigin.of(ConfigurationOrigin.Kind.FACTORY, name, config.getPropertySource(key)));
    }

    public static <T> ConfigurationDeclaration<T> javaChoice(final ConfigurationSetting<T> setting,
            final ConfigurationChoice<T> choice, final String setter) {
        return of(setting, choice, ConfigurationOrigin.of(ConfigurationOrigin.Kind.JAVA, setter, "Java"));
    }

    public ConfigurationSetting<T> getSetting() { return setting; }
    public ConfigurationChoice<T> getChoice() { return choice; }
    public ConfigurationOrigin getOrigin() { return origin; }
    public boolean isEligible() { return eligible; }
    public ConfigurationDeclaration<T> withEligibility(final boolean eligible) { return new ConfigurationDeclaration<>(setting, choice, origin, eligible); }
    @Override public String toString() { return origin.description() + " (" + choice.getState() + ")"; }
}
