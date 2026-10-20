package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A library-defined configuration property whose Java value type is fixed by its schema binding.
 * Obtain keys from {@link ConfigKeys}; applications cannot construct or extend them.
 * <p>
 * Use {@link SimpleJavaMailConfig#get(ConfigKey)} for ordinary reads. The key selects both the parser and Java projection,
 * so a numeric port cannot be requested as a String. Absence remains null, including for aggregate configuration maps.
 * This is the captured input snapshot, not a built Mailer's resolved defaults or runtime state.
 * <p>
 * The key name is safe to display; values read through a key can contain credentials. Use
 * {@link SimpleJavaMailConfig#getDiagnostics()} for safe diagnostic output and provenance.
 *
 * @param <T> The schema-defined Java value type, including the complete generic type of an aggregate map.
 */
public final class ConfigKey<T> {
    private final PropertySchema.Binding<?, T> binding;

    ConfigKey(final PropertySchema.Binding<?, T> binding) {
        this.binding = binding;
    }

    /**
     * Returns the unchanged textual property name, or wildcard namespace for an aggregate key.
     * A scalar name can be used with {@link ConfigLoader#withMap(java.util.Map)}; configure individual child names for
     * an aggregate namespace rather than assigning its wildcard name. Dynamic inputs retain their existing textual
     * units and parser types, even when a Java read projects the value to {@link java.time.Duration}.
     *
     * @return The canonical property name, such as {@code simplejavamail.smtp.port}.
     */
    @NotNull public String key() { return binding.property.key(); }

    ConfigLoader.Property property() { return binding.property; }

    @Nullable T read(@Nullable final Object stored) { return stored == null ? null : binding.read(stored); }

    /** @return The canonical property name, without any configured value or credential. */
    @Override public String toString() { return key(); }
}
