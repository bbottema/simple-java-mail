package org.simplejavamail.internal.mailprovider.angus;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.List;

/** Provider input roles retain their existing authority; interpretation and actual connection checks stay with Angus. */
final class AngusConfigurationResolver {
    private static final List<ConfigurationOrigin.Kind> PROVIDER_INPUT_ORDER = List.of(
            ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.JAVA_RAW, ConfigurationOrigin.Kind.SESSION,
            ConfigurationOrigin.Kind.DEFAULT, ConfigurationOrigin.Kind.BUILT_IN);

    private AngusConfigurationResolver() { }

    static <T> ConfigurationDeclaration<T> declaration(final ConfigurationSetting<T> setting,
            final ConfigurationOrigin.Kind kind, final String source, @Nullable final T value) {
        return ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(value), ConfigurationOrigin.of(kind, source, "Angus provider input"));
    }

    static <T> ConfigurationResolution<T> resolve(final ConfigurationSetting<T> setting, final List<ConfigurationDeclaration<T>> declarations) {
        return ConfigurationResolver.resolve(setting, declarations, PROVIDER_INPUT_ORDER);
    }

    @Nullable
    static <T> T messageOrSession(final String property, @Nullable final T messageChoice, @Nullable final T sessionChoice) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.of("angus.smtp", property);
        return resolve(setting, List.of(declaration(setting, ConfigurationOrigin.Kind.SUBMITTED, "per-message " + property, messageChoice),
                declaration(setting, ConfigurationOrigin.Kind.SESSION, property, sessionChoice))).valueOrNull();
    }

    @Nullable
    static <T> T envelopeOrLegacyMessage(final String property, @Nullable final T envelopeChoice, @Nullable final T legacyChoice) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.of("angus.envelope", property);
        return resolve(setting, List.of(declaration(setting, ConfigurationOrigin.Kind.SUBMITTED, "Email envelope " + property, envelopeChoice),
                declaration(setting, ConfigurationOrigin.Kind.JAVA_RAW, "SMTPMessage " + property, legacyChoice))).valueOrNull();
    }
}
