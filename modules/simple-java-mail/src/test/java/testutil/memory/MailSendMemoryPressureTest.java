package testutil.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import jdk.jfr.FlightRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks workload truth and process ownership; long retention trends remain manual workbench runs. */
@Timeout(120)
class MailSendMemoryPressureTest {
    @TempDir Path temporary;

    @Test
    void validatesProfileAndArtifactBoundsBeforeStartingProcesses() throws Exception {
        try (Settings properties = new Settings(temporary, "unknown")) {
            assertThatIllegalArgumentException().isThrownBy(MemoryPressureOptions::fromSystemProperties).withMessageContaining("Unknown memory profile");
            properties.set("profile", "sync");
            properties.set("messages", "1000000");
            properties.set("batchSize", "1");
            assertThatIllegalArgumentException().isThrownBy(MemoryPressureOptions::fromSystemProperties).withMessageContaining("checkpoint sets");
            properties.set("messages", "4");
            properties.set("inflight", "0");
            assertThatIllegalArgumentException().isThrownBy(MemoryPressureOptions::fromSystemProperties).withMessageContaining("inflight");
        }
    }

    @Test
    void attachmentAndChurnPressureProfilesObserveBothProgressAndFinalResults() throws Exception {
        for (final String profile : new String[] { "attachment-memory", "attachment-file", "churn" }) {
            try (Settings properties = new Settings(temporary, profile)) {
                final MemoryPressureOptions options = MemoryPressureOptions.fromSystemProperties();
                assertThat(options.hasResultObserver()).as("result observer for %s", profile).isTrue();
                assertThat(options.hasProgressObserver()).as("progress observer for %s", profile).isTrue();
            }
        }
    }

    @Test
    void measurementsAcceptControllerLaunchFilesButRejectPriorRuns() throws Exception {
        final Path output = temporary.resolve("measured");
        Files.createDirectory(output);
        Files.writeString(output.resolve("launch.json"), "{}");
        Files.writeString(output.resolve("run.log"), "");
        Files.writeString(output.resolve("error.log"), "");
        try (Settings properties = new Settings(output, "observers")) {
            properties.set("messages", "2");
            MailSendMemoryPressure.run(MemoryPressureOptions.fromSystemProperties());
            assertThat(Files.readString(output.resolve("samples.csv"))).contains("live_heap_bytes", ",loaded,", ",drained,", ",after-close,");
            assertThat(Files.readString(output.resolve("summary.json"))).contains("\"completed\": 3", "\"serverAccepted\": 3");
            assertThatIllegalArgumentException().isThrownBy(() -> MailSendMemoryPressure.run(MemoryPressureOptions.fromSystemProperties()))
                    .withMessageContaining("fresh output directory");
            assertThat(Files.readString(output.resolve("launch.json"))).isEqualTo("{}");
        }
    }

    @Test
    void retryCancellationFailoverAndChurnExerciseActualSmtpWithoutRetainedLedgers() throws Exception {
        for (final String profile : new String[] { "retry", "cancellation", "failover", "churn", "positive-control" }) {
            final Path output = temporary.resolve(profile);
            Files.createDirectory(output);
            final List<Long> children = new ArrayList<>();
            try (Settings properties = new Settings(output, profile)) {
                final MemoryPressureOptions options = MemoryPressureOptions.fromSystemProperties();
                try (MemoryPressureRun workload = new MemoryPressureRun(options)) {
                    workload.servers().forEach(server -> children.add(server.pid()));
                    workload.sendWarmup();
                    workload.beginMeasuredBatch();
                    workload.sendBatch(2, () -> { });
                    workload.verifyDrainedBatch();
                    workload.beginMeasuredBatch();
                    workload.sendBatch(2, () -> { });
                    workload.verifyDrainedBatch();
                    workload.closeMailers();
                    workload.writeVerifiedSummary();
                }
            }
            children.forEach(pid -> assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("SMTP child %s", pid).isFalse());
            final String summary = Files.readString(output.resolve("summary.json"));
            assertThat(summary).contains("\"completed\": 5");
            if (profile.equals("retry")) {
                assertThat(summary).contains("\"serverAccepted\": 5", "\"serverRefused\": 5");
            } else if (profile.equals("cancellation")) {
                assertThat(summary).contains("\"expectedFailures\": 5", "\"serverAccepted\": 0", "\"serverRefused\": 5");
            } else if (profile.equals("failover")) {
                assertThat(summary).contains("\"scenarioTransitions\": 2", "\"recoveries\": 1");
            } else if (profile.equals("positive-control")) {
                assertThat(summary).contains("\"positiveControlRetainedEmails\": 4");
            }
        }
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.isAlive() && thread.getName().equals("memory-smtp-control"));
    }

    @Test
    void aFailedCheckpointStillReapsTheOwnedSmtpProcess() throws Exception {
        final Path output = temporary.resolve("failed-checkpoint");
        Files.createDirectory(output);
        final List<Long> children = new ArrayList<>();
        try (Settings properties = new Settings(output, "async")) {
            assertThatThrownBy(() -> {
                try (MemoryPressureRun workload = new MemoryPressureRun(MemoryPressureOptions.fromSystemProperties())) {
                    workload.servers().forEach(server -> children.add(server.pid()));
                    workload.sendBatch(2, () -> { throw new IOException("deliberate checkpoint failure"); });
                }
            }).isInstanceOf(IOException.class).hasMessage("deliberate checkpoint failure");
        }
        children.forEach(pid -> assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse());
    }

    @Test
    void failedJfrSetupClosesItsRecordingAndMeasurementsFile() throws Exception {
        final Path output = temporary.resolve("failed-recording");
        Files.createDirectories(output.resolve("client.jfr"));
        final int existingRecordings = FlightRecorder.getFlightRecorder().getRecordings().size();
        try (Settings properties = new Settings(output, "sync")) {
            properties.set("profileRecording", "true");
            assertThatThrownBy(() -> {
                try (MemoryPressureMeasurements ignored = new MemoryPressureMeasurements(MemoryPressureOptions.fromSystemProperties())) {
                    throw new AssertionError("JFR unexpectedly accepted a directory as its destination");
                }
            }).isInstanceOf(IOException.class);
        }
        assertThat(FlightRecorder.getFlightRecorder().getRecordings()).hasSize(existingRecordings);
        Files.delete(output.resolve("samples.csv"));
    }

    @Test
    void pendingRetriesDoNotMultiplyTheDiscardServersPhysicalWorkerLimit() throws Exception {
        final Path output = temporary.resolve("many-pending-few-workers");
        Files.createDirectory(output);
        try (Settings properties = new Settings(output, "retry")) {
            properties.set("concurrency", "1");
            properties.set("inflight", "4096");
            final MemoryPressureOptions options = MemoryPressureOptions.fromSystemProperties();
            assertThat(MemorySmtpProcess.maximumPhysicalConnections(options)).isEqualTo(6);
            assertThat(MemorySmtpProcess.maximumPendingRetryKeys(options)).isEqualTo(4098);
            try (MemoryPressureRun workload = new MemoryPressureRun(options)) {
                workload.sendWarmup();
                workload.sendBatch(4, () -> { });
                workload.verifyDrainedBatch();
                final MemorySmtpProcess.Statistics server = workload.servers().get(0).statistics();
                assertThat(server.accepted).isEqualTo(5);
                assertThat(server.refused).isEqualTo(5);
                assertThat(server.pendingRetryKeys).isZero();
            }
        }
    }

    @Test
    void cancellationKeepsMultipleActualRetryWaitersLiveBeforeCancellingThemTogether() throws Exception {
        final Path output = temporary.resolve("concurrent-cancellation");
        Files.createDirectory(output);
        try (Settings properties = new Settings(output, "cancellation")) {
            properties.set("warmup", "0");
            try (MemoryPressureRun workload = new MemoryPressureRun(MemoryPressureOptions.fromSystemProperties())) {
                workload.sendBatch(4, () -> {
                    assertThat(workload.counters.completed).isZero();
                    assertThat(workload.counters.maximumCancellationConcurrency).isEqualTo(4);
                    final MemorySmtpProcess.Statistics server = workload.servers().get(0).statistics();
                    assertThat(server.refused).isEqualTo(4);
                    assertThat(server.accepted).isZero();
                });
                workload.verifyDrainedBatch();
                assertThat(workload.counters.completed).isEqualTo(4);
                assertThat(workload.counters.expectedFailures).isEqualTo(4);
            }
        }
    }

    @Test
    void aFailedCancellationCheckpointCancelsEverySubmittedRetryBeforeShutdown() throws Exception {
        final Path output = temporary.resolve("failed-cancellation-checkpoint");
        Files.createDirectory(output);
        final List<Long> children = new ArrayList<>();
        try (Settings properties = new Settings(output, "cancellation")) {
            properties.set("warmup", "0");
            assertThatThrownBy(() -> {
                try (MemoryPressureRun workload = new MemoryPressureRun(MemoryPressureOptions.fromSystemProperties())) {
                    workload.servers().forEach(server -> children.add(server.pid()));
                    workload.sendBatch(4, () -> { throw new IOException("deliberate cancellation checkpoint failure"); });
                }
            }).isInstanceOf(IOException.class).hasMessage("deliberate cancellation checkpoint failure");
        }
        children.forEach(pid -> assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse());
    }

    private static final class Settings implements AutoCloseable {
        private final Map<String, String> previous = new LinkedHashMap<>();

        private Settings(final Path output, final String profile) {
            set("output", output.toString());
            set("profile", profile);
            set("messages", "4");
            set("warmup", "1");
            set("batchSize", "2");
            set("concurrency", "2");
            set("inflight", "4");
            set("textBytes", "128");
            set("attachmentBytes", "4096");
            set("profileRecording", "false");
        }

        private void set(final String tail, final String value) {
            final String key = "sjm.memory." + tail;
            if (!previous.containsKey(key)) {
                previous.put(key, System.getProperty(key));
            }
            System.setProperty(key, value);
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }
}
