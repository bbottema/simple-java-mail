package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.List;

import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/** Adapts Mailer property inputs and feature-owned compound values to the shared choice model. */
final class MailerConfigurationResolver {
    private static final List<ConfigurationOrigin.Kind> JAVA_CONFIGURED_FALLBACK = List.of(
            ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.BUILT_IN);

    private MailerConfigurationResolver() { }

    @Nullable
    static <T> T configured(final SimpleJavaMailConfig config, final ConfigKey<T> key, @Nullable final T fallback) {
        return ConfigurationResolver.resolveValue(config, key, ConfigurationChoice.inherit(), fallback, "Mailer configuration").valueOrNull();
    }

    @Nullable
    static <T> T resolve(final SimpleJavaMailConfig config, final ConfigKey<T> key, @Nullable final T supplied, @Nullable final T fallback) {
        // Existing valueOrProperty adapters treated empty text/collections as omission. Literal-empty settings use the choice overload.
        final ConfigurationChoice<T> choice = valueNullOrEmpty(supplied) ? ConfigurationChoice.inherit() : ConfigurationChoice.set(supplied);
        return resolve(config, key, choice, fallback, "Mailer setting " + key.key());
    }

    static <T> ConfigurationChoice<T> choice(@Nullable final T supplied, final boolean removed) {
        return removed ? ConfigurationChoice.remove() : ConfigurationChoice.ofNullable(supplied);
    }

    /**
     * Only omitted group members inherit; explicit empty strings retain their setter's validation or clearing semantics.
     * This adapter retains the literal choice; the setting owner decides whether empty text is valid or removes a raw setting.
     */
    @Nullable
    static <T> T resolve(final SimpleJavaMailConfig config, final ConfigKey<T> key, final ConfigurationChoice<T> choice,
            @Nullable final T fallback, final String setter) {
        return ConfigurationResolver.resolveValue(config, key, choice, fallback, setter).valueOrNull();
    }

    /** Conversion and atomic-value construction remain with the feature; selection never inspects the supplied resource. */
    @Nullable
    static <T> T select(final ConfigurationChoice<T> choice, @Nullable final T captured, @Nullable final T fallback,
            final String property, final String setter) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.of("mailer", property);
        return ConfigurationResolver.resolve(setting, List.of(
                ConfigurationDeclaration.javaChoice(setting, choice, setter),
                ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(captured),
                        ConfigurationOrigin.of(ConfigurationOrigin.Kind.FACTORY, property, "captured Mailer configuration")),
                ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(fallback),
                        ConfigurationOrigin.of(ConfigurationOrigin.Kind.BUILT_IN, property, "built-in fallback"))),
                JAVA_CONFIGURED_FALLBACK).valueOrNull();
    }
}
