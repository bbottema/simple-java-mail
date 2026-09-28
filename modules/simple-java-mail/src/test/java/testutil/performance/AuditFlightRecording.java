package testutil.performance;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Prints sampled attribution separately from the unprofiled timing CSV; event samples are not exact method timings. */
public final class AuditFlightRecording {
    private AuditFlightRecording() { }

    public static void main(final String[] recordings) throws Exception {
        for (final String path : recordings) {
            summarize(Path.of(path));
        }
    }

    private static void summarize(final Path path) throws Exception {
        final Map<String, Long> categories = new HashMap<>();
        final Map<String, Long> methods = new HashMap<>();
        final Map<String, Long> nativeMethods = new HashMap<>();
        final Map<String, Long> allocations = new HashMap<>();
        final Map<String, Long> allocationSites = new HashMap<>();
        final Map<String, Long> discoveryOrigins = new HashMap<>();
        final Map<String, Long> waits = new HashMap<>();
        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                final RecordedEvent event = recording.readEvent();
                final String type = event.getEventType().getName();
                final String category = category(event);
                if (type.equals("jdk.ExecutionSample")) {
                    categories.merge(category, 1L, Long::sum);
                    if (!category.equals("fixture") && event.getStackTrace() != null && !event.getStackTrace().getFrames().isEmpty()) {
                        methods.merge(methodName(event.getStackTrace().getFrames().get(0)), 1L, Long::sum);
                    }
                } else if (type.equals("jdk.NativeMethodSample")) {
                    if (!category.equals("fixture") && event.getStackTrace() != null && !event.getStackTrace().getFrames().isEmpty()) {
                        nativeMethods.merge(methodName(event.getStackTrace().getFrames().get(0)), 1L, Long::sum);
                    }
                } else if (type.equals("jdk.ObjectAllocationSample")) {
                    allocations.merge(category, event.getLong("weight"), Long::sum);
                    if (!category.equals("fixture") && event.getStackTrace() != null) {
                        final String site = event.getStackTrace().getFrames().stream().limit(8).map(AuditFlightRecording::methodName)
                                .collect(Collectors.joining(" <- "));
                        allocationSites.merge(site, event.getLong("weight"), Long::sum);
                        final List<String> stack = event.getStackTrace().getFrames().stream()
                                .map(AuditFlightRecording::methodName).collect(Collectors.toList());
                        if (stack.stream().anyMatch(name -> name.contains("java.util.ServiceLoader"))) {
                            final String origin = stack.stream().filter(name -> name.startsWith("jakarta.") || name.startsWith("org.simplejavamail."))
                                    .limit(6).collect(Collectors.joining(" <- "));
                            discoveryOrigins.merge(origin.isEmpty() ? "caller outside recorded stack" : origin, 1L, Long::sum);
                        }
                    }
                } else if (type.equals("jdk.SocketRead") || type.equals("jdk.SocketWrite")
                        || type.equals("jdk.ThreadPark") || type.equals("jdk.JavaMonitorEnter")) {
                    waits.merge(type + "/" + category, event.getDuration().toNanos(), Long::sum);
                }
            }
        }
        System.out.println("Recording: " + path);
        printLargest("Execution samples (count)", categories, 20);
        printLargest("Top sampled leaf methods (count; fixture excluded)", methods, 20);
        printLargest("Native sampled leaf methods (count; fixture excluded)", nativeMethods, 20);
        printLargest("Sampled allocation weights (bytes; estimates)", allocations, 20);
        printLargest("Top allocation stacks (bytes; sampled estimates, fixture excluded)", allocationSites, 12);
        printLargest("Service discovery callers (allocation sample count, not byte share)", discoveryOrigins, 12);
        printLargest("Recorded waits >=1ms (nanoseconds; overlapping thread totals)", waits, 25);
    }

    private static String category(final RecordedEvent event) {
        final RecordedThread thread = event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
        if (thread != null && thread.getJavaName() != null && thread.getJavaName().startsWith("audit-smtp")) { return "fixture"; }
        if (event.getStackTrace() == null) { return "other"; }
        final List<String> frames = event.getStackTrace().getFrames().stream().map(AuditFlightRecording::methodName).collect(Collectors.toList());
        if (frames.stream().anyMatch(name -> name.contains("org.subethamail."))) { return "fixture"; }
        if (frames.stream().anyMatch(name -> name.contains("AngusMessageSize"))) { return "size"; }
        if (frames.stream().anyMatch(name -> name.contains("AngusContentNegotiation"))) { return "content-inspection"; }
        if (frames.stream().anyMatch(name -> name.contains("ServiceLoader"))) { return "provider-discovery"; }
        if (frames.stream().anyMatch(name -> name.contains("SessionLogger"))) { return "session-logging"; }
        if (frames.stream().anyMatch(name -> name.contains("dkim") || name.contains("bouncycastle"))) { return "protection"; }
        if (frames.stream().anyMatch(name -> name.contains("calculateEmailSize"))) { return "application-size-limit"; }
        if (frames.stream().anyMatch(name -> name.contains("writeTo"))) { return "mime-output"; }
        if (frames.stream().anyMatch(name -> name.contains("pool") || name.contains("Pool"))) { return "pool-or-executor"; }
        return "other";
    }

    private static String methodName(final RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static void printLargest(final String heading, final Map<String, Long> values, final int limit) {
        System.out.println(heading + ":");
        values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .forEach(entry -> System.out.println("  " + entry.getValue() + " " + entry.getKey()));
    }
}
