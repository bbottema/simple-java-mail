package org.simplejavamail.recipient.internal;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.List;

/** Recipient policies own eligibility; supplied names and borrowed metadata use the common declaration selection. */
@ApiStatus.Internal
public final class RecipientConfigurationResolver {
    private static final List<ConfigurationOrigin.Kind> PRECEDENCE = List.of(
            ConfigurationOrigin.Kind.OVERRIDE, ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.DEFAULT);

    private RecipientConfigurationResolver() { }

    @Nullable
    public static String preferName(@Nullable final String supplied, @Nullable final String embedded) {
        return select("recipient.name", supplied, embedded, null);
    }

    @Nullable
    static <T> T select(final String field, @Nullable final T fixed, @Nullable final T supplied, @Nullable final T fallback) {
        return select(field, ConfigurationChoice.ofNullable(fixed), supplied, true, fallback);
    }

    @Nullable
    static <T> T select(final String field, final ConfigurationChoice<T> fixed, @Nullable final T supplied,
            final boolean suppliedEligible, @Nullable final T fallback) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.of("recipient", field);
        return ConfigurationResolver.resolve(setting, List.of(
                ConfigurationDeclaration.of(setting, fixed, ConfigurationOrigin.of(ConfigurationOrigin.Kind.OVERRIDE, field, "fixed recipient policy")),
                declaration(setting, ConfigurationOrigin.Kind.SUBMITTED, field, supplied).withEligibility(suppliedEligible),
                declaration(setting, ConfigurationOrigin.Kind.DEFAULT, field, fallback)), PRECEDENCE).valueOrNull();
    }

    private static <T> ConfigurationDeclaration<T> declaration(final ConfigurationSetting<T> setting,
            final ConfigurationOrigin.Kind source, final String field, @Nullable final T value) {
        return ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(value), ConfigurationOrigin.of(source, field, source.name()));
    }
}
