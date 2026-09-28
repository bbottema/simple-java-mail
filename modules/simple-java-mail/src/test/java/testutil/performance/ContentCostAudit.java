package testutil.performance;

import com.sun.management.ThreadMXBean;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.email.internal.InternalEmail;

import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Amplifies individual content operations without SMTP waits so coarse Windows CPU counters remain useful.
 * Prepared MIME is reused here: these are isolated stage costs, not end-to-end send timings or additive accounting.
 */
public final class ContentCostAudit {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final BooleanSupplier NOT_STOPPED = () -> false;
    private static final String[] OMITTED_HEADERS = {"Bcc", "Content-Length"};
    private static volatile Object consumedResult;
    private static volatile long consumedSize;

    private ContentCostAudit() { }

    public static void main(final String[] arguments) throws Throwable {
        final Path directory = Path.of(arguments[0]);
        Files.createDirectories(directory);
        final int attachmentBytes = Integer.getInteger("sjm.audit.attachmentBytes", 256 * 1024);
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        // A Mailer reuses its Session; do not charge Session/provider discovery to every conversion in this isolated measurement.
        final Session session = Session.getInstance(new Properties());
        final MethodHandle inspection = inspectionHandle();
        final MethodHandle measurement = sizeHandle();
        THREADS.setThreadCpuTimeEnabled(true);
        THREADS.setThreadAllocatedMemoryEnabled(true);
        System.out.println("payload,operation,sample,iterations,wall_ns_per_op,cpu_ns_per_op,allocated_bytes_per_op");
        for (final String kind : new String[] {"small", "memory", "file", "exact", "dkim"}) {
            if (!kind.matches(System.getProperty("sjm.audit.payloads", ".*"))) {
                continue;
            }
            final AuditPayload payload = new AuditPayload(factory, kind, attachmentBytes, directory);
            final MimeMessage mime = EmailConverter.emailToMimeMessage(payload.email, session);
            final PreparedMail prepared = new PreparedMail(mime, mime.getAllRecipients(), new DeliveryEnvelope(null, null),
                    InternalEmail.requireInternalEmail(payload.email).determineContentRequirement());
            final CountingOutput counter = new CountingOutput();
            measure(kind, "render-and-protect", () -> consumedResult = EmailConverter.emailToMimeMessage(payload.email, session));
            measure(kind, "content-inspection", () -> consumedResult = (Object) inspection.invokeExact(prepared));
            measure(kind, "size-measurement", () -> consumedSize = (long) measurement.invokeExact(mime, NOT_STOPPED));
            measure(kind, "mime-output-to-counter", () -> {
                counter.count = 0;
                mime.writeTo(counter, OMITTED_HEADERS);
                consumedSize = counter.count;
            });
        }
    }

    private static MethodHandle inspectionHandle() throws Exception {
        final Class<?> type = Class.forName("org.simplejavamail.internal.mailprovider.angus.AngusContentNegotiation");
        final Method method = type.getDeclaredMethod("inspectSubmittedContent", PreparedMail.class);
        return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflect(method)
                .asType(MethodType.methodType(Object.class, PreparedMail.class));
    }

    private static MethodHandle sizeHandle() throws Exception {
        final Class<?> type = Class.forName("org.simplejavamail.internal.mailprovider.angus.AngusMessageSize");
        return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                .findStatic(type, "measure", MethodType.methodType(long.class, MimeMessage.class, BooleanSupplier.class));
    }

    private static void measure(final String payload, final String operation, final MeasuredAction action) throws Throwable {
        if (!operation.matches(System.getProperty("sjm.audit.operations", ".*"))) {
            return;
        }
        repeatFor(action, 300_000_000L);
        for (int sample = 1; sample <= 3; sample++) {
            final long thread = Thread.currentThread().getId();
            final long allocated = THREADS.getThreadAllocatedBytes(thread);
            final long cpu = THREADS.getCurrentThreadCpuTime();
            final long started = System.nanoTime();
            final long iterations = repeatFor(action, 500_000_000L);
            final long elapsed = System.nanoTime() - started;
            final long consumedCpu = THREADS.getCurrentThreadCpuTime() - cpu;
            final long allocatedBytes = THREADS.getThreadAllocatedBytes(thread) - allocated;
            System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%.3f%n", payload, operation, sample, iterations,
                    (double) elapsed / iterations, (double) consumedCpu / iterations, (double) allocatedBytes / iterations);
        }
    }

    private static long repeatFor(final MeasuredAction action, final long durationNanos) throws Throwable {
        final long until = System.nanoTime() + durationNanos;
        long iterations = 0;
        do {
            // Check the clock per small block, not on every operation; volatile consumption prevents dead-code elimination.
            for (int index = 0; index < 16; index++) { action.run(); }
            iterations += 16;
        } while (System.nanoTime() < until);
        return iterations;
    }

    @FunctionalInterface
    private interface MeasuredAction {
        void run() throws Throwable;
    }

    private static final class CountingOutput extends OutputStream {
        private long count;
        @Override public void write(final int value) { count++; }
        @Override public void write(final byte[] bytes, final int offset, final int length) { count += length; }
    }
}
