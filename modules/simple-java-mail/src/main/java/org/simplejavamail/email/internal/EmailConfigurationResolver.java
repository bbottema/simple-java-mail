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
import org.simplejavamail.internal.email.HeaderRoles;
import org.simplejavamail.internal.email.RepresentedHeaders;
import org.simplejavamail.internal.util.AttachmentResourceMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        return collectionSelection(provided, defaults, overrides, property, getter, sameValue).getValues();
    }

    /** Records ownership when each occurrence is admitted, before equal/reused resources can lose their distinct source positions. */
    public static <T> CollectionSelection<T> collectionSelection(@Nullable final Email provided, final Email defaults, final Email overrides,
            final EmailProperty property, final Function<Email, ? extends Collection<T>> getter, final BiPredicate<T, T> sameValue) {
        final Function<Email, List<T>> listGetter = email -> new ArrayList<>(getter.apply(email));
        final ConfigurationSetting<List<T>> setting = ConfigurationSetting.of("email", property.name());
        final List<ConfigurationDeclaration<List<T>>> declarations = capture(setting, provided, defaults, overrides, property, listGetter);
        final Collection<T> submitted = provided == null ? List.of() : getter.apply(provided);
        final List<T> result = new ArrayList<>();
        final List<CollectionOccurrence<T>> occurrences = new ArrayList<>();
        for (ConfigurationDeclaration<List<T>> declaration : ConfigurationResolver.eligibleDeclarations(setting, declarations, TEMPLATE_PRECEDENCE)) {
            final List<T> values = declaration.getChoice().valueOrNull();
            if (declaration.getOrigin().getKind() == ConfigurationOrigin.Kind.SUBMITTED) {
                result.addAll(values); // Every submitted occurrence belongs to the caller, including intentional duplicates.
                for (int index = 0; index < values.size(); index++) {
                    occurrences.add(new CollectionOccurrence<>(values.get(index), ConfigurationOrigin.Kind.SUBMITTED, index));
                }
            } else {
                for (int index = 0; index < values.size(); index++) {
                    final T candidate = values.get(index);
                    final boolean alreadySubmitted = declaration.getOrigin().getKind() == ConfigurationOrigin.Kind.OVERRIDE
                            && contains(submitted, candidate, sameValue);
                    if (!alreadySubmitted && !contains(result, candidate, sameValue)) {
                        result.add(candidate);
                        occurrences.add(new CollectionOccurrence<>(candidate, declaration.getOrigin().getKind(), index));
                    }
                }
            }
        }
        return new CollectionSelection<>(occurrences);
    }

    /** Transient selected data and exact origin positions; no source Email is retained by this record or the resulting inspection. */
    public static final class CollectionSelection<T> {
        private final List<CollectionOccurrence<T>> occurrences;
        private CollectionSelection(final List<CollectionOccurrence<T>> occurrences) { this.occurrences = List.copyOf(occurrences); }
        public List<CollectionOccurrence<T>> getOccurrences() { return occurrences; }
        public List<T> getValues() {
            final List<T> values = new ArrayList<>(occurrences.size());
            for (CollectionOccurrence<T> occurrence : occurrences) values.add(occurrence.getValue());
            return values;
        }
    }

    public static final class CollectionOccurrence<T> {
        private final T value;
        private final ConfigurationOrigin.Kind origin;
        private final int sourceIndex;
        private CollectionOccurrence(final T value, final ConfigurationOrigin.Kind origin, final int sourceIndex) {
            this.value = value; this.origin = origin; this.sourceIndex = sourceIndex;
        }
        public T getValue() { return value; }
        public ConfigurationOrigin.Kind getOrigin() { return origin; }
        public int getSourceIndex() { return sourceIndex; }
    }

    /** Retains the selected source of each complete header list for evidence and explicit composition intent. */
    public static List<ConfigurationResolution<List<String>>> headerResolutions(@Nullable final Email provided, final Email defaults,
            final Email overrides) {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>(HeaderSettings.capture(
                additionalHeaders(defaults, true), ConfigurationOrigin.Kind.DEFAULT, "default template", allowed(provided, EmailProperty.HEADERS, false)));
        if (provided != null) {
            declarations.addAll(HeaderSettings.capture(additionalHeaders(provided, false), ConfigurationOrigin.Kind.SUBMITTED, "submitted Email", true));
        }
        declarations.addAll(HeaderSettings.capture(additionalHeaders(overrides, true), ConfigurationOrigin.Kind.OVERRIDE,
                "override template", allowed(provided, EmailProperty.HEADERS, true)));
        return HeaderSettings.resolve(declarations, TEMPLATE_PRECEDENCE);
    }

    /** Typed fields have their own governance choices; preserve provided raw fallback without importing template input history. */
    private static Map<String, Collection<String>> additionalHeaders(final Email email, final boolean template) {
        final Map<String, Collection<String>> additional = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        additional.putAll(email.getHeaders());
        RepresentedHeaders.snapshot(email, Map.of(), Set.of()).keySet().forEach(additional::remove);
        if (template) {
            final Set<String> explicit = InternalEmail.requireInternalEmail(email).getExplicitHeaderNames();
            additional.keySet().removeIf(name -> !explicit.contains(name)
                    && (HeaderRoles.classify(name) != HeaderRoles.Role.ADDITIONAL || HeaderRoles.isThreading(name)));
        }
        return additional;
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
