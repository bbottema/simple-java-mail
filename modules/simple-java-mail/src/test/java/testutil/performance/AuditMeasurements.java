package testutil.performance;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Counts all attempts for correctness; latency recording is enabled only for the measured part of a batch. */
final class AuditMeasurements {
    static final String CSV_HEADER = "variant,scenario,sample,messages,wall_ms,throughput_per_s,p50_ms,p95_ms,p99_ms,mean_queue_ms,"
            + "sender_cpu_ms,fixture_cpu_ms,process_cpu_ms,sender_allocated_bytes,gc_ms,attachment_opens,attachment_read_bytes,received_bytes";

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean PROCESS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final boolean expectSize;
    private final boolean advertisedSize;
    private final Set<Long> senderThreadIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final AtomicInteger position = new AtomicInteger();
    private long[] latencies;
    private long[] queueTimes;
    private volatile boolean recording;
    private Snapshot before;
    private long started;
    private long openedBefore;
    private long readBefore;
    private long messagesBefore;
    private long receivedBefore;

    AuditMeasurements(final String variant, final boolean advertisedSize) {
        expectSize = !variant.equals("no-size") && !variant.equals("neither");
        this.advertisedSize = advertisedSize;
        if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This audit requires per-thread CPU and allocation counters");
        }
        THREADS.setThreadCpuTimeEnabled(true);
        THREADS.setThreadAllocatedMemoryEnabled(true);
        senderThreadIds.add(Thread.currentThread().getId());
    }

    void observe(final MailSendOutcome outcome) {
        senderThreadIds.add(Thread.currentThread().getId());
        final MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElse(null);
        if (!outcome.isSuccessful() || receipt == null || receipt.getStatus() != MailSubmissionStatus.ACCEPTED) {
            failure.compareAndSet(null, "A synthetic send did not succeed: " + outcome.getFailure());
        } else if ((receipt.getMessageSize() != null) != expectSize) {
            failure.compareAndSet(null, "The SIZE overlay did not have its expected effect");
        } else if (advertisedSize != (receipt.getServerMaximumMessageSize() != null)) {
            failure.compareAndSet(null, "The server SIZE advertisement was not represented correctly");
        }
        if (recording) {
            final int index = position.getAndIncrement();
            latencies[index] = Duration.between(outcome.getRequestedAt(), outcome.getCompletedAt()).toNanos();
            queueTimes[index] = Duration.between(outcome.getReadyAt().orElseThrow(), outcome.getStartedAt().orElseThrow()).toNanos();
        }
    }

    void begin(final int messages, final AuditPayload payload, final AuditSmtpServer server) {
        latencies = new long[messages];
        queueTimes = new long[messages];
        position.set(0);
        openedBefore = payload.openedStreams.sum();
        readBefore = payload.readBytes.sum();
        messagesBefore = server.messages.get();
        receivedBefore = server.bytes.get();
        before = Snapshot.capture();
        started = System.nanoTime();
        recording = true;
    }

    String finish(final String variant, final String scenario, final int sample, final AuditPayload payload, final AuditSmtpServer server) {
        final long elapsed = System.nanoTime() - started;
        recording = false;
        final Snapshot after = Snapshot.capture();
        verifyCounts(server);
        Arrays.sort(latencies);
        final double wallMillis = elapsed / 1_000_000.0;
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d",
                variant, scenario, sample, latencies.length, wallMillis, latencies.length * 1000.0 / wallMillis,
                percentile(0.50), percentile(0.95), percentile(0.99), Arrays.stream(queueTimes).average().orElse(0) / 1_000_000.0,
                after.cpuSince(before, senderThreadIds) / 1_000_000.0, after.cpuSince(before, server.threadIds) / 1_000_000.0,
                (after.processCpu - before.processCpu) / 1_000_000.0, after.allocationsSince(before, senderThreadIds),
                after.gcMillis - before.gcMillis, payload.openedStreams.sum() - openedBefore, payload.readBytes.sum() - readBefore,
                server.bytes.get() - receivedBefore);
    }

    private void verifyCounts(final AuditSmtpServer server) {
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        if (position.get() != latencies.length || server.messages.get() - messagesBefore != latencies.length) {
            throw new AssertionError("Observer/server counts differ from the requested measured workload");
        }
    }

    private double percentile(final double quantile) {
        return latencies[(int) Math.ceil(latencies.length * quantile) - 1] / 1_000_000.0;
    }

    /** Warmed workers remain alive until after capture, so their CPU/allocation counters do not disappear between snapshots. */
    private static final class Snapshot {
        private final Map<Long, Long> cpu = new HashMap<>();
        private final Map<Long, Long> allocated = new HashMap<>();
        private long processCpu;
        private long gcMillis;

        private static Snapshot capture() {
            final Snapshot snapshot = new Snapshot();
            for (final long id : THREADS.getAllThreadIds()) {
                snapshot.cpu.put(id, Math.max(0, THREADS.getThreadCpuTime(id)));
                snapshot.allocated.put(id, Math.max(0, THREADS.getThreadAllocatedBytes(id)));
            }
            snapshot.processCpu = PROCESS.getProcessCpuTime();
            snapshot.gcMillis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(collector -> Math.max(0, collector.getCollectionTime())).sum();
            return snapshot;
        }

        private long cpuSince(final Snapshot earlier, final Set<Long> ids) {
            return difference(cpu, earlier.cpu, ids);
        }

        private long allocationsSince(final Snapshot earlier, final Set<Long> ids) {
            return difference(allocated, earlier.allocated, ids);
        }

        private static long difference(final Map<Long, Long> later, final Map<Long, Long> earlier, final Set<Long> ids) {
            return ids.stream().mapToLong(id -> later.getOrDefault(id, 0L) - earlier.getOrDefault(id, 0L)).sum();
        }
    }
}
