package testutil.memory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps accidental workload and artifact multiplication visible before any SMTP processes are started. */
final class MemoryPressureOptions {
    static final Set<String> PROFILES = new HashSet<>(Arrays.asList("sync", "async", "result-only", "progress-only", "observers", "slow-progress",
            "retry", "cancellation", "failover", "churn", "attachment-memory", "attachment-file", "positive-control"));
    private static final int MAXIMUM_CHECKPOINTS = 62;

    final String profile;
    final Path output;
    final int messages;
    final int warmup;
    final int batchSize;
    final int concurrency;
    final int inflight;
    final int attachmentBytes;
    final int textBytes;
    final int observerDelayMillis;
    final boolean profileRecording;

    private MemoryPressureOptions() {
        profile = System.getProperty("sjm.memory.profile", "observers");
        output = Path.of(System.getProperty("sjm.memory.output", "target/mail-send-memory/manual")).toAbsolutePath().normalize();
        messages = integer("messages", 10000, 1, 100_000_000);
        warmup = integer("warmup", 1000, 0, 1_000_000);
        batchSize = integer("batchSize", 1000, 1, 100_000_000);
        concurrency = integer("concurrency", 4, 1, 64);
        inflight = integer("inflight", 16, 1, 4096);
        attachmentBytes = integer("attachmentBytes", 5 * 1024 * 1024, 1, 64 * 1024 * 1024);
        textBytes = integer("textBytes", 4096, 1, 1024 * 1024);
        observerDelayMillis = integer("observerDelayMillis", 5, 1, 30000);
        profileRecording = booleanProperty("profileRecording", false);
        if (!PROFILES.contains(profile)) {
            throw new IllegalArgumentException("Unknown memory profile '" + profile + "'. Choose one of " + PROFILES + '.');
        }
        if ((messages + (long) batchSize - 1) / batchSize > MAXIMUM_CHECKPOINTS) {
            throw new IllegalArgumentException("This run would create more than " + MAXIMUM_CHECKPOINTS
                    + " checkpoint sets. Increase sjm.memory.batchSize to bound histogram and native-memory artifacts.");
        }
    }

    static MemoryPressureOptions fromSystemProperties() {
        return new MemoryPressureOptions();
    }

    boolean hasResultObserver() {
        return Arrays.asList("result-only", "observers", "slow-progress", "retry", "cancellation", "failover", "churn",
                        "attachment-memory", "attachment-file", "positive-control")
                .contains(profile);
    }

    boolean hasProgressObserver() {
        return Arrays.asList("progress-only", "observers", "slow-progress", "retry", "cancellation", "failover", "churn",
                "attachment-memory", "attachment-file", "positive-control").contains(profile);
    }

    boolean sendsSynchronously() {
        return profile.equals("sync") || profile.equals("churn");
    }

    private static int integer(final String tail, final int fallback, final int minimum, final int maximum) {
        final String name = "sjm.memory." + tail;
        final int value;
        try {
            value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be a whole number from " + minimum + " through " + maximum + '.', invalid);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be from " + minimum + " through " + maximum + '.');
        }
        return value;
    }

    private static boolean booleanProperty(final String tail, final boolean fallback) {
        final String name = "sjm.memory." + tail;
        final String value = System.getProperty(name, Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(name + " must be true or false.");
        }
        return Boolean.parseBoolean(value);
    }
}
