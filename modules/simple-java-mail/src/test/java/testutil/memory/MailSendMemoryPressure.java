package testutil.memory;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Manual memory workbench entry point; the ordinary test suite runs only its small fixture and contract checks. */
public final class MailSendMemoryPressure {
    private MailSendMemoryPressure() { }

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length == 4 && arguments[0].equals("server")) {
            MemorySmtpSink.serve(arguments[1], Integer.parseInt(arguments[2]), Integer.parseInt(arguments[3]));
            return;
        }
        if (arguments.length != 0) {
            throw new IllegalArgumentException("Configure the client using sjm.memory.* JVM properties. See tools/memory/README.md.");
        }
        run(MemoryPressureOptions.fromSystemProperties());
    }

    static void run(final MemoryPressureOptions options) throws Exception {
        reserveOutputDirectory(options);
        MemoryPressureMeasurements.writeJson(options.output.resolve("memory-profile.json"), MemoryPressureMeasurements.profileMetadata(options));
        try (MemoryPressureRun workload = new MemoryPressureRun(options);
             MemoryPressureMeasurements measurements = new MemoryPressureMeasurements(options)) {
            workload.sendWarmup();
            measurements.beginAfterWarmup();
            measurements.drained("warmup", workload.counters, workload.servers());
            for (int sent = 0; sent < options.messages; sent += Math.min(options.batchSize, options.messages - sent)) {
                workload.beginMeasuredBatch();
                workload.sendBatch(Math.min(options.batchSize, options.messages - sent), () -> measurements.loaded(workload.counters, workload.servers()));
                workload.verifyDrainedBatch();
                measurements.drained("drained", workload.counters, workload.servers());
            }
            workload.closeMailers();
            measurements.drained("after-close", workload.counters, workload.servers());
            measurements.finishNativeMeasurements();
            workload.writeVerifiedSummary();
        }
    }

    private static void reserveOutputDirectory(final MemoryPressureOptions options) throws Exception {
        if (Files.exists(options.output)) {
            try (Stream<java.nio.file.Path> contents = Files.list(options.output)) {
                // The process controller creates only these launch artifacts before starting this JVM. Prior measurement artifacts are never reused.
                final Set<String> launchFiles = new HashSet<>(Arrays.asList("launch.json", "run.log", "error.log"));
                if (contents.anyMatch(path -> !launchFiles.contains(path.getFileName().toString()))) {
                    throw new IllegalArgumentException("Choose a fresh output directory for this memory profile: " + options.output);
                }
            }
        } else {
            Files.createDirectories(options.output);
        }
    }
}
