package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/** Persistent build cache. All paths are supplied by Maven; runtime readers never use this store. */
final class CliMetadataStore {
    private static final int MAX_BYTES = 9 * 1024 * 1024;
    private final Path cacheRoot;
    private final Path outputDirectory;

    CliMetadataStore(final Path cacheRoot, final Path outputDirectory) {
        this.cacheRoot = cacheRoot;
        this.outputDirectory = outputDirectory;
    }

    @FunctionalInterface
    interface Generator {
        CliGeneratedMetadata generate() throws IOException;
    }

    @SuppressWarnings("try")
    boolean ensure(final String key, final boolean force, final Generator generator) throws IOException {
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid CLI metadata input key.");
        Files.createDirectories(cacheRoot);
        final Path entry = cacheRoot.resolve(key);
        try (FileChannel channel = FileChannel.open(cacheRoot.resolve(key + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            CliGeneratedMetadata pair = force ? null : readEntry(entry, key);
            final boolean generated = pair == null;
            if (generated) {
                pair = generator.generate();
                validate(pair);
                final Path staging = Files.createTempDirectory(cacheRoot, ".generation-");
                try {
                    Files.write(staging.resolve("cli.data"), pair.getOptions());
                    Files.write(staging.resolve("therapi.data"), pair.getJavadoc());
                    Files.writeString(staging.resolve("manifest.properties"), manifest(key, pair), StandardCharsets.UTF_8);
                    Files.createDirectories(entry);
                    replace(staging.resolve("cli.data"), entry.resolve("cli.data"));
                    replace(staging.resolve("therapi.data"), entry.resolve("therapi.data"));
                    // Readers hold the same lock. If a build is interrupted, the old manifest cannot validate a partial new pair.
                    replace(staging.resolve("manifest.properties"), entry.resolve("manifest.properties"));
                } finally {
                    Files.deleteIfExists(staging.resolve("cli.data"));
                    Files.deleteIfExists(staging.resolve("therapi.data"));
                    Files.deleteIfExists(staging.resolve("manifest.properties"));
                    Files.deleteIfExists(staging);
                }
            }
            Files.createDirectories(outputDirectory);
            writeOutput("cli.data", pair.getOptions());
            writeOutput("therapi.data", pair.getJavadoc());
            return generated;
        }
    }

    private static CliGeneratedMetadata readEntry(final Path entry, final String key) {
        try {
            final Path manifest = entry.resolve("manifest.properties");
            if (!Files.isRegularFile(manifest) || Files.size(manifest) > 4096) return null;
            final Properties properties = new Properties();
            properties.load(new StringReader(Files.readString(manifest, StandardCharsets.UTF_8)));
            if (!"1".equals(properties.getProperty("format")) || !key.equals(properties.getProperty("input"))) return null;
            final CliGeneratedMetadata pair = CliGeneratedMetadata.builder()
                    .options(readBounded(entry.resolve("cli.data"))).javadoc(readBounded(entry.resolve("therapi.data"))).build();
            if (!digest(pair.getOptions()).equals(properties.getProperty("cli.sha256"))
                    || !digest(pair.getJavadoc()).equals(properties.getProperty("therapi.sha256"))) return null;
            validate(pair);
            return pair;
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    static void validate(final CliGeneratedMetadata pair) {
        final byte[] fingerprint = CliSourceApiFingerprint.calculate();
        CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, pair.getOptions());
        CliMetadataCache.unwrap(CliMetadataCache.Kind.THERAPI_JAVADOC, fingerprint, pair.getJavadoc());
    }

    static byte[] readBounded(final Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("CLI metadata exceeds its size limit: " + file.getFileName());
            return bytes;
        }
    }

    private static String manifest(final String key, final CliGeneratedMetadata pair) {
        return "format=1\ninput=" + key + "\ncli.sha256=" + digest(pair.getOptions())
                + "\ntherapi.sha256=" + digest(pair.getJavadoc()) + "\n";
    }

    private void writeOutput(final String name, final byte[] bytes) throws IOException {
        final Path destination = outputDirectory.resolve(name);
        if (Files.isRegularFile(destination) && MessageDigest.isEqual(readBounded(destination), bytes)) return;
        final Path temporary = Files.createTempFile(outputDirectory, name + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            replace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(final Path source, final Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    static String digest(final byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }
}
