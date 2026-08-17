package org.simplejavamail.internal.util;

import jakarta.activation.MimeTypeEntry;
import jakarta.activation.MimeTypeRegistry;
import jakarta.activation.spi.MimeTypeRegistryProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal Jakarta Activation MIME-type registry for provider-neutral MIME conversion. */
public final class ProviderNeutralMimeTypeRegistryProvider implements MimeTypeRegistryProvider {
    @Override
    public MimeTypeRegistry getByFileName(final String name) throws IOException {
        if (name == null || !new File(name).isFile()) return null;
        try (InputStream input = new FileInputStream(name)) {
            return new LocalMimeTypeRegistry(input);
        }
    }

    @Override
    public MimeTypeRegistry getByInputStream(final InputStream inputStream) throws IOException {
		return new LocalMimeTypeRegistry(inputStream);
    }

    @Override
    public MimeTypeRegistry getInMemory() {
		return new LocalMimeTypeRegistry();
    }

    private static final class LocalMimeTypeRegistry implements MimeTypeRegistry {
        private final Map<String, MimeTypeEntry> entries = new LinkedHashMap<>();

        private LocalMimeTypeRegistry() {
        }

        private LocalMimeTypeRegistry(final InputStream input) throws IOException {
            final StringBuilder text = new StringBuilder();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
            appendToRegistry(text.toString());
        }

        @Override
        public MimeTypeEntry getMimeTypeEntry(final String extension) {
            return entries.get(normalizeExtension(extension));
        }

        @Override
        public void appendToRegistry(final String mimeTypes) {
            if (mimeTypes == null) return;
            for (String rawLine : mimeTypes.split("\\r?\\n")) {
                final String line = stripComment(rawLine).trim();
                if (line.isEmpty()) continue;
                if (line.contains("type=") && line.contains("exts=")) {
                    final String type = attribute(line, "type");
                    final String extensions = attribute(line, "exts");
                    if (type != null && extensions != null) {
                        for (String extension : extensions.split(",")) add(type, extension);
                    }
                } else {
                    final String[] tokens = line.split("\\s+");
                    if (tokens.length < 2) continue;
                    for (int i = 1; i < tokens.length; i++) add(tokens[0], tokens[i]);
                }
            }
        }

        private void add(final String type, final String extension) {
            final String normalized = normalizeExtension(extension);
            if (!normalized.isEmpty()) entries.put(normalized, new MimeTypeEntry(type.trim(), normalized));
        }

        private static String attribute(final String line, final String name) {
            final String prefix = name + "=";
            final int start = line.indexOf(prefix);
            if (start < 0) return null;
            final int valueStart = start + prefix.length();
            final int end = line.indexOf(' ', valueStart);
            return line.substring(valueStart, end < 0 ? line.length() : end).trim();
        }
    }

    private static String normalizeExtension(final String extension) {
        if (extension == null) return "";
        final String trimmed = extension.trim();
        return (trimmed.startsWith(".") ? trimmed.substring(1) : trimmed).toLowerCase(Locale.ROOT);
    }

    private static String stripComment(final String line) {
        final int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }
}
