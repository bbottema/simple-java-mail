package org.simplejavamail.internal.util;

import jakarta.activation.MailcapRegistry;
import jakarta.activation.spi.MailcapRegistryProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal Jakarta Activation mailcap registry for provider-neutral MIME conversion. */
public final class ProviderNeutralMailcapRegistryProvider implements MailcapRegistryProvider {
    @Override
    public MailcapRegistry getByFileName(final String name) throws IOException {
        if (name == null || !new File(name).isFile()) return null;
        try (InputStream input = new FileInputStream(name)) {
            return new LocalMailcapRegistry(input);
        }
    }

    @Override
    public MailcapRegistry getByInputStream(final InputStream inputStream) throws IOException {
		return new LocalMailcapRegistry(inputStream);
    }

    @Override
    public MailcapRegistry getInMemory() {
		return new LocalMailcapRegistry();
    }

    private static final class LocalMailcapRegistry implements MailcapRegistry {
        private final Map<String, Map<String, List<String>>> commands = new LinkedHashMap<>();
        private final Map<String, Map<String, List<String>>> fallbackCommands = new LinkedHashMap<>();
        private final Map<String, List<String>> nativeCommands = new LinkedHashMap<>();

        private LocalMailcapRegistry() {
        }

        private LocalMailcapRegistry(final InputStream input) throws IOException {
            appendToMailcap(read(input));
        }

        @Override
        public Map<String, List<String>> getMailcapList(final String mimeType) {
            return lookup(commands, mimeType);
        }

        @Override
        public Map<String, List<String>> getMailcapFallbackList(final String mimeType) {
            return lookup(fallbackCommands, mimeType);
        }

        @Override
        public String[] getMimeTypes() {
            final Set<String> types = new LinkedHashSet<>(commands.keySet());
            types.addAll(fallbackCommands.keySet());
            types.addAll(nativeCommands.keySet());
            return types.toArray(new String[0]);
        }

        @Override
        public String[] getNativeCommands(final String mimeType) {
            final List<String> values = nativeCommands.get(normalize(mimeType));
            return values == null ? null : values.toArray(new String[0]);
        }

        @Override
        public void appendToMailcap(final String mailcap) {
            if (mailcap == null) return;
            for (String logicalLine : logicalLines(mailcap)) parse(logicalLine);
        }

        private void parse(final String line) {
            final String uncommented = stripComment(line).trim();
            if (uncommented.isEmpty()) return;
            final List<String> parts = splitSemicolon(uncommented);
            if (parts.isEmpty()) return;
            final String mimeType = normalize(parts.get(0));
            if (!mimeType.contains("/")) return;
            boolean fallback = false;
            for (String part : parts) {
                if (part.trim().equalsIgnoreCase("x-java-fallback-entry=true")) fallback = true;
            }
            final Map<String, Map<String, List<String>>> target = fallback ? fallbackCommands : commands;
            for (int i = 1; i < parts.size(); i++) {
                final String part = parts.get(i).trim();
                final int equals = part.indexOf('=');
                if (equals < 0) {
                    if (!part.isEmpty()) nativeCommands.computeIfAbsent(mimeType, ignored -> new ArrayList<>()).add(uncommented);
                    continue;
                }
                String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (!key.startsWith("x-java-") || key.equals("x-java-fallback-entry")) continue;
                key = key.substring("x-java-".length());
                final String value = unquote(part.substring(equals + 1).trim());
                target.computeIfAbsent(mimeType, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }

        private static Map<String, List<String>> lookup(
                final Map<String, Map<String, List<String>>> source, final String requestedType) {
            final String type = normalize(requestedType);
            Map<String, List<String>> result = source.get(type);
            if (result == null) {
                final int slash = type.indexOf('/');
                if (slash > 0) result = source.get(type.substring(0, slash) + "/*");
            }
            if (result == null) return null;
            final Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : result.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    private static String read(final InputStream input) throws IOException {
        final StringBuilder text = new StringBuilder();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
        String line;
        while ((line = reader.readLine()) != null) text.append(line).append('\n');
        return text.toString();
    }

    private static List<String> logicalLines(final String text) {
        final List<String> lines = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            final String trimmed = line.trim();
            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1);
            } else {
                current.append(line);
                lines.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private static List<String> splitSemicolon(final String line) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder part = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char value = line.charAt(i);
            if (value == '"') quoted = !quoted;
            if (value == ';' && !quoted) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(value);
            }
        }
        parts.add(part.toString());
        return parts;
    }

    private static String stripComment(final String line) {
        final int comment = line.indexOf('#');
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static String normalize(final String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static String unquote(final String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
