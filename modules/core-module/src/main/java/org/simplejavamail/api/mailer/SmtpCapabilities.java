package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;
import org.simplejavamail.internal.util.SmtpSizeSupport;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Immutable advertisements from one successful EHLO, not promises about what a message used.
 * Keys are uppercase and sorted; repeated advertisements retain their separate parameter values.
 * Parameters are escaped for display without individual truncation; probe reports apply a shared budget to the complete snapshot.
 * Absence of this whole snapshot means discovery was unavailable, not that the server supports no extensions.
 */
public final class SmtpCapabilities implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<String, List<String>> extensions;
    private final SmtpSizeSupport sizeSupport;

    /** Copies provider facts; parameterless extensions have an empty-string parameter, not an absent map entry. */
    public SmtpCapabilities(@NotNull final Map<String, List<String>> extensions) {
        this(extensions, null);
    }

    private SmtpCapabilities(final Map<String, List<String>> extensions, @Nullable final SmtpSizeSupport retainedSizeSupport) {
        sizeSupport = retainedSizeSupport == null ? resolveSizeSupport(extensions) : retainedSizeSupport;
        final Map<String, List<String>> copy = new TreeMap<>();
        extensions.forEach((name, parameters) -> {
            final String canonicalName = name.toUpperCase(Locale.ROOT);
            if (!canonicalName.matches("[A-Z0-9][A-Z0-9-]*")) {
                throw new IllegalArgumentException("SMTP extension names must be ASCII letters, digits or hyphens, starting with a letter or digit.");
            }
            if (parameters.isEmpty()) {
                throw new IllegalArgumentException("Supply an empty-string parameter for a parameterless SMTP extension.");
            }
            final List<String> values = copy.computeIfAbsent(canonicalName, unused -> new ArrayList<>());
            parameters.forEach(value -> values.add(SmtpDiagnosticText.escape(value)));
        });
        copy.replaceAll((name, values) -> List.copyOf(values));
        this.extensions = Collections.unmodifiableMap(copy);
    }

    /** Returns every advertised extension, including unknown extensions and duplicates, in stable name order. */
    @NotNull
    public Map<String, List<String>> getExtensions() {
        return extensions;
    }

    /** Case-insensitive advertisement lookup, for example {@code supports("DSN")}. */
    public boolean supports(@NotNull final String extension) {
        return extensions.containsKey(extension.toUpperCase(Locale.ROOT));
    }

    /**
     * Returns a positive SIZE limit only when every SIZE advertisement supplies the same valid integer.
     * Empty also covers SIZE without a limit, zero (no fixed maximum), malformed/overflowing limits and contradictory duplicates.
     * Use {@link #supports(String)} to distinguish those from SIZE not being advertised at all.
     */
    @NotNull
    public OptionalLong getMaximumMessageSize() {
        final Long maximum = sizeSupport.getMaximumMessageSize();
        return maximum == null ? OptionalLong.empty() : OptionalLong.of(maximum);
    }

    private static SmtpSizeSupport resolveSizeSupport(final Map<String, List<String>> extensions) {
        SmtpSizeSupport support = SmtpSizeSupport.unadvertised();
        for (final Map.Entry<String, List<String>> extension : extensions.entrySet()) {
            if (extension.getKey().equalsIgnoreCase("SIZE")) {
                for (final String parameter : extension.getValue()) {
                    support = support.withAdvertisement(parameter);
                }
            }
        }
        return support;
    }

    @Override
    public String toString() {
        return extensions.toString();
    }

    /** Restore defensive-copy/display guarantees without replacing typed facts with interpretations of display text. */
    private Object readResolve() {
        // Older snapshots have no typed field; derive only what their retained parameter text can establish.
        return new SmtpCapabilities(extensions, sizeSupport);
    }
}
