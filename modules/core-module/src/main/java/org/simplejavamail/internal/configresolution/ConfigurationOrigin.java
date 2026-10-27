package org.simplejavamail.internal.configresolution;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Source categories carry facts, not a global precedence: each feature supplies its own ordering. */
@ApiStatus.Internal
public final class ConfigurationOrigin {
    public enum Kind { FACTORY, FACTORY_RAW, SESSION, JAVA_RAW, JAVA, BUILT_IN, DERIVED, OVERRIDE, SUBMITTED, DEFAULT }

    private final Kind kind;
    private final String property;
    @Nullable private final String source;

    private ConfigurationOrigin(final Kind kind, final String property, @Nullable final String source) {
        this.kind = requireNonNull(kind, "kind");
        this.property = requireNonNull(property, "property");
        this.source = source;
    }

    public static ConfigurationOrigin of(final Kind kind, final String property, @Nullable final String source) {
        return new ConfigurationOrigin(kind, property, source);
    }

    public Kind getKind() { return kind; }
    public String getProperty() { return property; }
    public boolean isExplicit() { return kind != Kind.BUILT_IN && kind != Kind.DERIVED; }
    public String description() {
        return escapeControlCharacters(property) + (source == null ? "" : " from " + escapeControlCharacters(source));
    }
    @Override public String toString() { return description(); }
}
