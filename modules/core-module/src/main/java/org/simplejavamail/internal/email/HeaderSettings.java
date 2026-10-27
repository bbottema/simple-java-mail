package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Interprets header identity before source lists are flattened into a message snapshot. */
@ApiStatus.Internal
public final class HeaderSettings {
    private HeaderSettings() { }

    /** Uses header-name equivalence without rewriting the source spelling. */
    public static ConfigurationSetting<List<String>> named(final String name) {
        return ConfigurationSetting.caseInsensitive("email.header", name);
    }

    /** Captures every spelling and its source, including legacy collisions and suppressed template declarations. */
    public static List<ConfigurationDeclaration<List<String>>> capture(final Map<String, ? extends Collection<String>> headers,
            final ConfigurationOrigin.Kind kind, final String source, final boolean eligible) {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>();
        for (Map.Entry<String, ? extends Collection<String>> entry : headers.entrySet()) {
            final List<String> values = Collections.unmodifiableList(new ArrayList<>(entry.getValue()));
            declarations.add(ConfigurationDeclaration.of(named(entry.getKey()), ConfigurationChoice.set(values),
                    ConfigurationOrigin.of(kind, entry.getKey(), source)).withEligibility(eligible));
        }
        return Collections.unmodifiableList(declarations);
    }

    /**
     * Resolves each logical name while retaining its declarations. Within one precedence level the last matching entry wins,
     * preserving legacy flat-map interpretation and allowing a later inner signed-content list to replace the outer list.
     */
    public static List<ConfigurationResolution<List<String>>> resolve(final List<ConfigurationDeclaration<List<String>>> declarations,
            final List<ConfigurationOrigin.Kind> precedence) {
        final Map<ConfigurationSetting<List<String>>, List<ConfigurationDeclaration<List<String>>>> byName = new LinkedHashMap<>();
        for (ConfigurationDeclaration<List<String>> declaration : declarations) {
            final ConfigurationSetting<List<String>> setting = declaration.getSetting();
            verifyHeaderSetting(setting);
            byName.computeIfAbsent(setting, ignored -> new ArrayList<>()).add(declaration);
        }
        final List<ConfigurationResolution<List<String>>> resolutions = new ArrayList<>();
        for (List<ConfigurationDeclaration<List<String>>> candidates : byName.values()) {
            final ConfigurationSetting<List<String>> setting = firstDisplaySetting(candidates);
            final List<ConfigurationDeclaration<List<String>>> lastMatchFirst = new ArrayList<>(candidates);
            Collections.reverse(lastMatchFirst);
            resolutions.add(ConfigurationResolver.resolve(setting, lastMatchFirst, precedence));
        }
        return Collections.unmodifiableList(resolutions);
    }

    private static void verifyHeaderSetting(final ConfigurationSetting<List<String>> setting) {
        if (!setting.equals(named(setting.getName()))) {
            throw new IllegalArgumentException("Expected a header setting, received " + setting);
        }
    }

    private static ConfigurationSetting<List<String>> firstDisplaySetting(final List<ConfigurationDeclaration<List<String>>> declarations) {
        for (ConfigurationDeclaration<List<String>> declaration : declarations) {
            if (declaration.isEligible() && declaration.getChoice().getState() != ConfigurationChoice.State.INHERIT) {
                return declaration.getSetting();
            }
        }
        return declarations.get(0).getSetting();
    }

    /** Produces detached complete value lists after identity and source precedence have been resolved. */
    public static Map<String, Collection<String>> snapshot(final List<ConfigurationResolution<List<String>>> resolutions) {
        final Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ConfigurationResolution<List<String>> resolution : resolutions) {
            verifyHeaderSetting(resolution.getSetting());
            final List<String> selected = resolution.valueOrNull();
            if (selected != null) {
                headers.put(resolution.getSetting().getName(), new ArrayList<>(selected));
            }
        }
        return headers;
    }

    /** Inner signed-content declarations follow the outer declarations so their complete value lists replace outer matches. */
    public static Map<String, Collection<String>> mergeSignedContent(final Map<String, ? extends Collection<String>> outer,
            final Map<String, ? extends Collection<String>> inner) {
        final List<ConfigurationDeclaration<List<String>>> declarations = new ArrayList<>(capture(
                outer, ConfigurationOrigin.Kind.SUBMITTED, "outer message", true));
        declarations.addAll(capture(inner, ConfigurationOrigin.Kind.SUBMITTED, "inner signed content", true));
        return snapshot(resolve(declarations, List.of(ConfigurationOrigin.Kind.SUBMITTED)));
    }
}
