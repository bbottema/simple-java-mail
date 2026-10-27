package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.config.ConfigKey;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Logical identity shared by a setting's input representations; parsing and applicability belong to the owning feature. */
@ApiStatus.Internal
public final class ConfigurationSetting<T> {
    private final String domain;
    private final String name;
    private final boolean caseInsensitive;

    private ConfigurationSetting(final String domain, final String name, final boolean caseInsensitive) {
        this.domain = requireNonNull(domain, "domain");
        this.name = requireNonNull(name, "name");
        this.caseInsensitive = caseInsensitive;
    }

    public static <T> ConfigurationSetting<T> of(final String domain, final String name) {
        return new ConfigurationSetting<>(domain, name, false);
    }

    /** Retains display text while using the same name equivalence as a case-insensitive String map. */
    public static <T> ConfigurationSetting<T> caseInsensitive(final String domain, final String name) {
        return new ConfigurationSetting<>(domain, name, true);
    }

    /** A configured-value helper with no feature-specific aliases can use its source key as its logical name. */
    public static <T> ConfigurationSetting<T> configured(final ConfigKey<T> key) {
        return of("configuration", requireNonNull(key, "key").key());
    }

    public String getDomain() { return domain; }
    public String getName() { return name; }

    @Override public boolean equals(final Object other) {
        if (this == other) { return true; }
        if (!(other instanceof ConfigurationSetting)) { return false; }
        final ConfigurationSetting<?> candidate = (ConfigurationSetting<?>) other;
        return domain.equals(candidate.domain) && caseInsensitive == candidate.caseInsensitive
                && (caseInsensitive ? String.CASE_INSENSITIVE_ORDER.compare(name, candidate.name) == 0 : name.equals(candidate.name));
    }

    @Override public int hashCode() {
        return 31 * (31 * domain.hashCode() + Boolean.hashCode(caseInsensitive)) + nameHash();
    }

    private int nameHash() {
        if (!caseInsensitive) { return name.hashCode(); }
        int hash = 0;
        for (int offset = 0; offset < name.length();) {
            final int codePoint = name.codePointAt(offset);
            hash = 31 * hash + Character.toLowerCase(Character.toUpperCase(codePoint));
            offset += Character.charCount(codePoint);
        }
        return hash;
    }

    @Override public String toString() { return escapeControlCharacters(domain) + ":" + escapeControlCharacters(name); }
}
