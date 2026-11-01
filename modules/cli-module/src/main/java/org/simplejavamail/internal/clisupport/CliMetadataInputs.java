package org.simplejavamail.internal.clisupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Content key for the resolved generation classpath, including Javadoc and the generator itself. */
final class CliMetadataInputs {
    private CliMetadataInputs() {
    }

    static List<Path> classpath() {
        final Set<Path> paths = new LinkedHashSet<>();
        final ClassLoader loader = CliMetadataInputs.class.getClassLoader();
        if (loader instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) loader).getURLs()) {
                if (!"file".equals(url.getProtocol())) throw new IllegalStateException("Unsupported build classpath URL: " + url);
                paths.add(Path.of(URI.create(url.toExternalForm())).toAbsolutePath().normalize());
            }
        } else {
            for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                paths.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(paths);
    }

    static String key(final List<Path> classpath, final List<Path> buildFiles) throws IOException {
        final MessageDigest digest = CliMetadataStore.sha256();
        update(digest, "sjm-cli-build-cache-1");
        update(digest, System.getProperty("java.specification.version"));
        for (Path entry : classpath) {
            update(digest, "classpath-entry");
            if (Files.isDirectory(entry)) {
                try (Stream<Path> files = Files.walk(entry)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(path -> relevant(entry.relativize(path).toString()))
                            .sorted(Comparator.comparing(path -> entry.relativize(path).toString().replace('\\', '/'))).toList()) {
                        update(digest, entry.relativize(file).toString().replace('\\', '/'));
                        try (InputStream input = Files.newInputStream(file)) { updateContents(digest, entry.relativize(file).toString(), input); }
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    final List<JarEntry> files = jar.stream().filter(file -> !file.isDirectory() && relevant(file.getName()))
                            .sorted(Comparator.comparing(JarEntry::getName)).toList();
                    for (JarEntry file : files) {
                        update(digest, file.getName());
                        try (InputStream input = jar.getInputStream(file)) { updateContents(digest, file.getName(), input); }
                    }
                }
            } else {
                throw new IOException("Missing generation classpath entry: " + entry);
            }
        }
        for (Path file : buildFiles) {
            update(digest, "build-file");
            try (InputStream input = Files.newInputStream(file)) { update(digest, input); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean relevant(final String name) {
        // Class contents and generated Javadoc are stable across clean builds; ZIP dates, manifests and our output are not inputs.
        return name.endsWith(".class") || name.endsWith("__Javadoc.json") || name.replace('\\', '/').endsWith("/pom.properties");
    }

    private static void update(final MessageDigest digest, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateContents(final MessageDigest digest, final String name, final InputStream input) throws IOException {
        if (name.replace('\\', '/').endsWith("/pom.properties")) {
            final Properties properties = new Properties();
            properties.load(input);
            for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
                update(digest, key);
                update(digest, properties.getProperty(key));
            }
        } else {
            update(digest, input);
        }
    }

    private static void update(final MessageDigest digest, final InputStream input) throws IOException {
        final MessageDigest content = CliMetadataStore.sha256();
        final byte[] buffer = new byte[16384];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (count > 0) content.update(buffer, 0, count);
        }
        digest.update(content.digest());
    }
}
