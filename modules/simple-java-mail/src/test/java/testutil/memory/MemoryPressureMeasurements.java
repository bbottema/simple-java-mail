package testutil.memory;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Measures the same client JVM over successive load/drain cycles; it deliberately does not interpret allocation as retained memory. */
final class MemoryPressureMeasurements implements AutoCloseable {
    private static final Pattern HISTOGRAM_TOTAL = Pattern.compile("Total\\s+\\d+\\s+(\\d+)\\s*");
    private static final String CSV_HEADER = "sample,label,completed,heap_used_bytes,live_heap_bytes,live_threads,loaded_classes,total_loaded_classes,"
            + "committed_heap_bytes,non_heap_used_bytes,result_callbacks,progress_callbacks,expected_failures,server_accepted,server_refused,connections";
    private final Path output;
    private final BufferedWriter samples;
    private final Recording recording;
    private int sample;
    private boolean nativeTracking;

    MemoryPressureMeasurements(final MemoryPressureOptions options) throws Exception {
        output = options.output;
        samples = Files.newBufferedWriter(output.resolve("samples.csv"));
        try {
            samples.write(CSV_HEADER + '\n');
            samples.flush();
            recording = options.profileRecording ? createRecording(output.resolve("client.jfr")) : null;
        } catch (Exception failure) {
            try {
                samples.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void beginAfterWarmup() throws Exception {
        if (recording != null) {
            recording.start();
        }
        diagnostic("native-baseline.txt", "VM.native_memory", "baseline");
        nativeTracking = !Files.readString(output.resolve("native-baseline.txt")).contains("Native memory tracking is not enabled");
    }

    void loaded(final MemoryPressureRun.Counters counters, final List<MemorySmtpProcess> servers) throws Exception {
        sample("loaded", counters, servers, null);
    }

    void drained(final String label, final MemoryPressureRun.Counters counters, final List<MemorySmtpProcess> servers) throws Exception {
        // GC.class_histogram performs a collection before its live histogram. Heap readings below therefore have a defined retention checkpoint.
        final String histogram = "heap-" + sample + ".txt";
        diagnostic(histogram, "GC.class_histogram");
        final long liveHeap = liveHeapBytes(output.resolve(histogram));
        if (nativeTracking) {
            diagnostic("native-" + sample + ".txt", "VM.native_memory", "summary.diff", "scale=KB");
        }
        sample(label, counters, servers, liveHeap);
    }

    private void sample(final String label, final MemoryPressureRun.Counters counters, final List<MemorySmtpProcess> servers,
            final Long liveHeapBytes) throws Exception {
        long accepted = 0;
        long refused = 0;
        long connections = 0;
        for (final MemorySmtpProcess server : servers) {
            final MemorySmtpProcess.Statistics statistics = server.statistics();
            accepted += statistics.accepted;
            refused += statistics.refused;
            connections += statistics.connections;
        }
        final String row = sample++ + "," + label + ',' + counters.completed + ','
                + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() + ','
                + (liveHeapBytes == null ? "" : liveHeapBytes) + ','
                + ManagementFactory.getThreadMXBean().getThreadCount() + ','
                + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount() + ','
                + ManagementFactory.getClassLoadingMXBean().getTotalLoadedClassCount() + ','
                + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted() + ','
                + ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + ','
                + counters.results.get() + ',' + counters.progress.get() + ',' + counters.expectedFailures + ','
                + accepted + ',' + refused + ',' + connections;
        samples.write(row + '\n');
        samples.flush();
        System.out.println("MEMORY_SAMPLE," + row);
    }

    private static long liveHeapBytes(final Path histogram) throws IOException {
        try (java.io.BufferedReader lines = Files.newBufferedReader(histogram)) {
            String line;
            while ((line = lines.readLine()) != null) {
                final Matcher total = HISTOGRAM_TOTAL.matcher(line.strip());
                if (total.matches()) {
                    return Long.parseLong(total.group(1));
                }
            }
        }
        throw new IOException("The JDK histogram did not contain its live-object total: " + histogram);
    }

    void finishNativeMeasurements() throws Exception {
        if (nativeTracking) {
            diagnostic("native-diff.txt", "VM.native_memory", "summary.diff", "scale=KB");
        }
    }

    private void diagnostic(final String filename, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(MemorySmtpProcess.jdkExecutable("jcmd"));
        command.add(Long.toString(ProcessHandle.current().pid()));
        command.addAll(Arrays.asList(arguments));
        final Process diagnostic = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(filename).toFile()).start();
        try {
            if (!diagnostic.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("JDK diagnostic exceeded thirty seconds: " + filename);
            }
            if (diagnostic.exitValue() != 0) {
                throw new IOException("JDK diagnostic failed. See " + output.resolve(filename));
            }
        } finally {
            if (diagnostic.isAlive()) {
                diagnostic.destroyForcibly();
                if (!diagnostic.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("The JDK diagnostic process did not terminate: " + diagnostic.pid());
                }
            }
        }
    }

    private static Recording createRecording(final Path destination) throws Exception {
        final Recording recording = new Recording(Configuration.getConfiguration("profile"));
        try {
            recording.setDestination(destination);
            recording.setMaxSize(64L * 1024 * 1024);
            recording.setMaxAge(Duration.ofMinutes(30));
            recording.enable("jdk.OldObjectSample").withStackTrace();
            return recording;
        } catch (Exception failure) {
            try {
                recording.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static void writeJson(final Path path, final Map<String, ?> values) throws IOException {
        final List<String> entries = new ArrayList<>();
        values.forEach((key, value) -> entries.add("  " + quote(key) + ": "
                + (value instanceof Number || value instanceof Boolean || value instanceof List ? value : quote(String.valueOf(value)))));
        Files.writeString(path, "{\n" + String.join(",\n", entries) + "\n}\n");
    }

    private static String quote(final String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    static Map<String, Object> profileMetadata(final MemoryPressureOptions options) {
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("profile", options.profile);
        metadata.put("messages", options.messages);
        metadata.put("warmup", options.warmup);
        metadata.put("batchSize", options.batchSize);
        metadata.put("concurrency", options.concurrency);
        metadata.put("inflight", options.inflight);
        metadata.put("attachmentBytes", options.attachmentBytes);
        metadata.put("textBytes", options.textBytes);
        metadata.put("observerDelayMillis", options.observerDelayMillis);
        metadata.put("profileRecording", options.profileRecording);
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("javaVendor", System.getProperty("java.vendor"));
        metadata.put("clientPid", ProcessHandle.current().pid());
        metadata.put("maximumHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("resultObserver", options.hasResultObserver());
        metadata.put("progressObserver", options.hasProgressObserver());
        metadata.put("artificialRetention", options.profile.equals("positive-control"));
        return metadata;
    }

    @Override
    public void close() throws IOException {
        try {
            if (recording != null) {
                recording.close();
            }
        } finally {
            samples.close();
        }
    }
}
