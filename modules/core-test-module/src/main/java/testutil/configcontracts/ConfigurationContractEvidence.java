package testutil.configcontracts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/** Plain immutable TSV evidence shared by test witnesses and the final completeness gate. */
public final class ConfigurationContractEvidence {
    private static final List<String> INVENTORY_FILES = List.of("configuration-api-routes.tsv", "configuration-extra-api-routes.tsv",
            "configuration-contract-obligations.tsv");

    private ConfigurationContractEvidence() { }

    /** Binds evidence to the exact named inventory bytes; no directory traversal, normalization or process cache can hide a change. */
    public static String fingerprint(final Path inventoryDirectory) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
        for (String name : INVENTORY_FILES) {
            final byte[] contents = Files.readAllBytes(inventoryDirectory.resolve(name));
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(contents.length).array());
            digest.update(contents);
        }
        final StringBuilder encoded = new StringBuilder();
        for (byte value : digest.digest()) {
            encoded.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        }
        return encoded.toString();
    }

    /** Reads only this directory's completed invocation files; earlier run directories are never consulted. */
    public static List<Entry> read(final Path directory) throws IOException {
        if (!Files.exists(directory)) { return Collections.emptyList(); }
        final List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.filter(path -> path.getFileName().toString().endsWith(".tsv")).sorted().collect(Collectors.toList());
        }
        final List<Entry> evidence = new ArrayList<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String[] fields = line.split("\t", -1);
                if (fields.length != 5) { throw new IllegalArgumentException("Invalid configuration-contract evidence row in " + file); }
                evidence.add(new Entry(fields[0], fields[1], fields[2], fields[3], fields[4]));
            }
        }
        return Collections.unmodifiableList(evidence);
    }

    static void publish(final Path directory, final List<Entry> evidence) {
        Path pending = null;
        IOException publicationFailure = null;
        try {
            Files.createDirectories(directory);
            pending = Files.createTempFile(directory, "invocation-", ".pending");
            final List<String> rows = evidence.stream().map(Entry::row).collect(Collectors.toList());
            Files.write(pending, rows, StandardCharsets.UTF_8);
            Files.move(pending, directory.resolve(UUID.randomUUID() + ".tsv"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            publicationFailure = failure;
            throw new IllegalStateException("Could not publish configuration-contract invocation evidence", failure);
        } finally {
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (IOException failure) {
                    if (publicationFailure != null) { publicationFailure.addSuppressed(failure); }
                    else { throw new IllegalStateException("Could not retire pending configuration-contract evidence", failure); }
                }
            }
        }
    }

    static String identifier(final String value, final String field) {
        requireNonNull(value, field);
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Configuration-contract " + field + " must be nonblank text without control characters");
        }
        return value;
    }

    public static final class Entry {
        private final String kind;
        private final String id;
        private final String test;
        private final String invocationId;
        private final String inventoryFingerprint;

        Entry(final String kind, final String id, final String test, final String invocationId) {
            this(kind, id, test, invocationId, "unbound");
        }

        public Entry(final String kind, final String id, final String test, final String invocationId, final String inventoryFingerprint) {
            this.kind = identifier(kind, "kind");
            this.id = identifier(id, "id");
            this.test = identifier(test, "test");
            this.invocationId = identifier(invocationId, "invocationId");
            if (!"unbound".equals(inventoryFingerprint) && (inventoryFingerprint == null || !inventoryFingerprint.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Configuration-contract inventory fingerprint must be a SHA-256 value or unbound");
            }
            this.inventoryFingerprint = inventoryFingerprint;
        }

        public String getKind() { return kind; }
        public String getId() { return id; }
        public String getTest() { return test; }
        public String getInvocationId() { return invocationId; }
        public String getInventoryFingerprint() { return inventoryFingerprint; }

        private String row() { return kind + "\t" + id + "\t" + test + "\t" + invocationId + "\t" + inventoryFingerprint; }
    }
}
