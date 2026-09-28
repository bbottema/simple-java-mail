package testutil.performance;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manually launched loopback audit, never a production throughput claim or a CI timing assertion.
 * See tools/performance/run-mail-send-audit.ps1 for isolated variants and reproducible launch settings.
 */
public final class MailSendPerformanceAudit {
    private static final int WORKERS = 8;
    private static final int OUTSTANDING_LIMIT = WORKERS * 4;
    private static final List<String> SCENARIOS = Arrays.asList("small-batch", "small-pooled", "memory-batch", "file-batch", "memory-pooled",
            "exact-batch", "exact-pooled", "dkim-batch", "limit-batch", "small-nosize", "memory-nosize", "exact-nosize");

    private MailSendPerformanceAudit() { }

    public static void main(final String[] arguments) throws Exception {
        final Path directory = Path.of(System.getProperty("sjm.audit.output", "target/mail-send-performance/manual")).toAbsolutePath();
        Files.createDirectories(directory);
        final String variant = System.getProperty("sjm.audit.variant", "baseline");
        final String scenarioPattern = System.getProperty("sjm.audit.scenarios", ".*");
        final int samples = Integer.getInteger("sjm.audit.samples", 3);
        final int warmup = Integer.getInteger("sjm.audit.warmup", 200);
        final boolean profile = Boolean.getBoolean("sjm.audit.profile");
        try (BufferedWriter output = Files.newBufferedWriter(directory.resolve("samples.csv"))) {
            output.write(AuditMeasurements.CSV_HEADER + "\n");
            for (final String scenario : SCENARIOS) {
                if (scenario.matches(scenarioPattern)) {
                    final boolean small = scenario.startsWith("small-");
                    final int count = Integer.getInteger(small ? "sjm.audit.messages" : "sjm.audit.largeMessages", small ? 2000 : 200);
                    auditScenario(directory, variant, scenario, samples, warmup, count,
                            Integer.getInteger("sjm.audit.attachmentBytes", 256 * 1024), profile, output);
                }
            }
        }
    }

    static void auditScenario(final Path directory, final String variant, final String scenario, final int samples,
            final int warmup, final int count, final int attachmentBytes, final boolean profile, final BufferedWriter output) throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final String kind = scenario.substring(0, scenario.indexOf('-'));
        final AuditPayload payload = new AuditPayload(factory, kind, attachmentBytes, directory);
        final boolean pooled = scenario.endsWith("-pooled");
        final boolean advertisedSize = !scenario.endsWith("-nosize");
        final AuditMeasurements measurements = new AuditMeasurements(variant, advertisedSize);
        try (AuditSmtpServer server = new AuditSmtpServer(advertisedSize);
             Mailer mailer = mailer(factory, server, scenario, measurements);
             Recording recording = profile ? createRecording(directory.resolve(scenario + ".jfr")) : null) {
            // Heat class loading, JIT and pooled connections before the measured batches or Flight Recorder.
            sendPooled(mailer, payload.email, warmup);
            if (recording != null) { recording.start(); }
            for (int sample = 1; sample <= samples; sample++) {
                if (pooled) {
                    measurements.begin(count, payload, server);
                    sendPooled(mailer, payload.email, count);
                } else {
                    sendWarmedBatch(mailer, payload, server, measurements, warmup, count);
                }
                final String row = measurements.finish(variant, scenario, sample, payload, server);
                output.write(row + "\n");
                output.flush();
                System.out.println(row);
            }
            if (recording != null) { recording.stop(); }
        }
    }

    private static Mailer mailer(final SimpleJavaMail factory, final AuditSmtpServer server, final String scenario,
            final AuditMeasurements measurements) {
        final MailerRegularBuilder<?> builder = factory.mailerBuilder().withSMTPServer("127.0.0.1", server.port())
                .withSmtpClientHostname("audit.example.test").withSessionTimeout(30000)
                .withThreadPoolSize(WORKERS).withThreadPoolKeepAliveTime(3600000).withAsyncQueueCapacity(64)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(WORKERS)
                .withConnectionPoolExpireAfterMillis(3600000)
                .withConnectionPoolClaimTimeoutMillis(30000).withMailSendObserver(measurements::observe);
        if (scenario.startsWith("limit-")) {
            builder.withMaximumEmailSize(AuditSmtpServer.MAXIMUM_MESSAGE_BYTES);
        }
        return builder.buildMailer();
    }

    private static void sendPooled(final Mailer mailer, final Email email, final int count) throws Exception {
        final Queue<CompletableFuture<MailSubmissionReceipt>> pending = new ArrayDeque<>();
        for (int index = 0; index < count; index++) {
            pending.add(mailer.async().sendMail(email).getCompletion());
            if (pending.size() == OUTSTANDING_LIMIT) {
                pending.remove().get(60, TimeUnit.SECONDS);
            }
        }
        while (!pending.isEmpty()) {
            pending.remove().get(60, TimeUnit.SECONDS);
        }
    }

    private static void sendWarmedBatch(final Mailer mailer, final AuditPayload payload, final AuditSmtpServer server,
            final AuditMeasurements measurements, final int warmup, final int count) {
        // Reset measurements inside the lazy iterable so warm-up and measurement share the very same SMTP connection.
        mailer.sync().sendMailsInSimpleBatch(() -> new Iterator<Email>() {
            private int next;

            @Override public boolean hasNext() { return next < warmup + count; }

            @Override
            public Email next() {
                if (next == warmup) { measurements.begin(count, payload, server); }
                next++;
                return payload.email;
            }
        });
    }

    private static Recording createRecording(final Path destination) throws Exception {
        final Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setDestination(destination);
        recording.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.SocketWrite").withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
        recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1));
        return recording;
    }
}
