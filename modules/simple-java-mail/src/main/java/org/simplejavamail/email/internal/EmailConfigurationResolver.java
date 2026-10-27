package org.simplejavamail.email.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;
import org.simplejavamail.internal.email.HeaderSettings;
import org.simplejavamail.internal.util.AttachmentResourceMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Email owns template eligibility and merge rules; the shared foundation orders and retains their declarations. */
@ApiStatus.Internal
public final class EmailConfigurationResolver {
    private static final List<ConfigurationOrigin.Kind> TEMPLATE_PRECEDENCE = List.of(
            ConfigurationOrigin.Kind.OVERRIDE, ConfigurationOrigin.Kind.SUBMITTED, ConfigurationOrigin.Kind.DEFAULT);

    private EmailConfigurationResolver() { }

    public static Email template(@Nullable final Email supplied, @Nullable final Email fallback, final boolean configured, final String setter) {
        final ConfigurationSetting<Email> setting = ConfigurationSetting.of("mailer.email-template", setter);
        return ConfigurationResolver.resolve(setting, List.of(
                ConfigurationDeclaration.javaChoice(setting, ConfigurationChoice.ofNullable(supplied), setter),
                ConfigurationDeclaration.of(setting, ConfigurationChoice.ofNullable(fallback), ConfigurationOrigin.of(
                        configured ? ConfigurationOrigin.Kind.FACTORY : ConfigurationOrigin.Kind.BUILT_IN, setter,
                        configured ? "captured factory template" : "empty template"))),
                List.of(ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.FACTORY, ConfigurationOrigin.Kind.BUILT_IN)).valueOrNull();
    }

    public static <T> ConfigurationResolution<T> scalar(@Nullable final Email provided, final Email defaults, final Email overrides,
            final EmailProperty property, final Function<Email, T> getter) {
        final ConfigurationSetting<T> setting = ConfigurationSetting.of("email", property.name());
        return ConfigurationResolver.resolve(setting, capture(setting, provided, defaults, overrides, property, getter), TEMPLATE_PRECEDENCE);
    }

    @Nullable
    public static Email calendarSource(@Nullable final Email provided, final Email defaults, final Email overrides) {
        return scalar(provided, defaults, overrides, EmailProperty.CALENDAR_TEXT,
                email -> email.getCalendarText() == null ? null : email).valueOrNull();
    }

    public static <T> List<T> collection(@Nullable final Email provided, final Email defaults, final Email overrides,
            final EmailProperty property, final Function<Email, ? extends Collection<T>> getter, final BiPredicate<T, T> sameValue) {
        final Function<Email, List<T>> listGetter = email -> new ArrayList<>(getter.apply(email));
        final ConfigurationSetting<List<T>> setting = ConfigurationSetting.of("email", property.name());
        final List<ConfigurationDeclaration<List<T>>> declarations = capture(setting, provided, defaults, overrides, property, listGetter);
        final Collection<T> submitted = provided == null ? List.of() : getter.apply(provided);
        final List<T> result = new ArrayList<>();
        for (ConfigurationDeclaration<List<T>> declaration : ConfigurationResolver.eligibleDeclarations(setting, declarations, TEMPLATE_PRECEDENCE)) {
            final List<T> values = declaration.getChoice().valueOrNull();
            if (declaration.getOrigin().getKind() == ConfigurationOrigin.Kind.SUBMITTED) {
                result.addAll(values); // Every submitted occurrence belongs to the caller, including intentional duplicates.
            } else {
                for (T candidate : values) {
                    final boolean alreadySubmitted = declaration.getOrigin().getKind() == ConfigurationOrigin.Kind.OVERRIDE
                            && contains(submitted, candidate, sameValue);
                    if (!alreadySubmitted && !contains(result, candidate, sameValue)) {
                        result.add(candidate);
                    }
                }
            }
        }
        return result;
    }

    public static Map<String, Collection<String>> headers(@Nullable final Email provided, final Email defaults, final Email overrides) {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>(HeaderSettings.capture(
                defaults.getHeaders(), ConfigurationOrigin.Kind.DEFAULT, "default template", allowed(provided, EmailProperty.HEADERS, false)));
        if (provided != null) {
            declarations.addAll(HeaderSettings.capture(provided.getHeaders(), ConfigurationOrigin.Kind.SUBMITTED, "submitted Email", true));
        }
        declarations.addAll(HeaderSettings.capture(overrides.getHeaders(), ConfigurationOrigin.Kind.OVERRIDE,
                "override template", allowed(provided, EmailProperty.HEADERS, true)));
        return HeaderSettings.snapshot(HeaderSettings.resolve(declarations, TEMPLATE_PRECEDENCE));
    }

    public static boolean sameResource(final AttachmentResource first, final AttachmentResource second) {
        return AttachmentResourceMatcher.sameResource(first, second);
    }

    private static <T> boolean contains(final Collection<T> values, final T candidate, final BiPredicate<T, T> sameValue) {
        for (T existing : values) {
            if (sameValue.test(existing, candidate)) { return true; }
        }
        return false;
    }

    private static <T> List<ConfigurationDeclaration<T>> capture(final ConfigurationSetting<T> setting,
            @Nullable final Email provided, final Email defaults,
            final Email overrides, final EmailProperty property, final Function<Email, T> getter) {
        return List.of(
                declaration(setting, ConfigurationOrigin.Kind.OVERRIDE, property, getter.apply(overrides)).withEligibility(allowed(provided, property, true)),
                declaration(setting, ConfigurationOrigin.Kind.SUBMITTED, property, provided == null ? null : getter.apply(provided)),
                declaration(setting, ConfigurationOrigin.Kind.DEFAULT, property, getter.apply(defaults)).withEligibility(allowed(provided, property, false)));
    }

    private static <T> ConfigurationDeclaration<T> declaration(final ConfigurationSetting<T> setting,
            final ConfigurationOrigin.Kind source, final EmailProperty property,
            @Nullable final T value) {
        return ConfigurationDeclaration.of(setting, value == null ? ConfigurationChoice.inherit() : ConfigurationChoice.set(value),
                ConfigurationOrigin.of(source, property.name(), source.name()));
    }

    private static boolean allowed(@Nullable final Email provided, final EmailProperty property, final boolean override) {
        if (provided == null) { return true; }
        final Set<EmailProperty> excluded = override ? provided.getPropertiesNotToApplyOverrideValueFor()
                : provided.getPropertiesNotToApplyDefaultValueFor();
        final boolean globallyIgnored = override ? provided.isIgnoreOverrides() : provided.isIgnoreDefaults();
        return !globallyIgnored && !suppressed(excluded, property);
    }

    private static boolean suppressed(@Nullable final Set<EmailProperty> excluded, final EmailProperty property) {
        if (excluded == null) { return false; }
        if (property == EmailProperty.CALENDAR_TEXT || property == EmailProperty.CALENDAR_METHOD) {
            return excluded.contains(EmailProperty.CALENDAR_TEXT) || excluded.contains(EmailProperty.CALENDAR_METHOD);
        }
        return excluded.contains(property) || excluded.contains(EmailProperty.ALL_RECIPIENTS)
                && (property == EmailProperty.TO_RECIPIENTS || property == EmailProperty.CC_RECIPIENTS || property == EmailProperty.BCC_RECIPIENTS);
    }
}
