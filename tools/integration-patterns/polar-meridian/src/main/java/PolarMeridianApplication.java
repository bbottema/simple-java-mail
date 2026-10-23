import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.examples.support.ExampleHttp;
import org.simplejavamail.examples.support.ProtectedContent;
import org.simplejavamail.examples.support.SqlStore;
import org.simplejavamail.examples.support.ResourceCleanup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Run the article's shared mail service with two processes, separate urgent/bulk routes and a retained protected archive.
 * The unchanged Dispatcher owns the job flow. Follow {@link #preparation()} for this application's composition and checks,
 * then {@link PolarStorage} for claims and evidence. None of that application orchestration is hidden inside SJM.
 */
public final class PolarMeridianApplication implements AutoCloseable {
    private final SqlStore database = new SqlStore();
    private final PolarStorage storage = new PolarStorage(database);
    private final SimpleJavaMail mail = PolarMeridianMailers.productionFactory(ConfigLoader.builder());
    private final ExampleHttp http = new ExampleHttp();
    private final ThreadPoolExecutor observations = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
    private final PolarMeridianMailers.Mailers mailers;
    private final PolarMeridianDispatcher dispatcher;
    private final PolarTelemetry telemetry = new PolarTelemetry();

    public PolarMeridianApplication() throws Exception {
        mailers = PolarMeridianMailers.build(builder("urgent-a"), builder("urgent-b"), builder("bulk"),
                FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(1)).build(),
                outcome -> {
                    storage.recordOutcome(outcome.getInitialMessageId(), outcome);
                    PolarMeridianMailers.recordTimings(outcome, (stage, duration) -> storage.event("MEASURED_" + stage, duration.toString()));
                }, observations);
        dispatcher = new PolarMeridianDispatcher(mail, storage, preparation(), new JdbcDispatchLimits(database.dataSource()),
                storage, observations, failure -> storage.event("DISPATCH_NEEDS_ATTENTION", failure.getClass().getSimpleName()), Clock.systemUTC());
        registerEndpoints();
    }

    public static void main(String[] args) throws Exception {
        PolarMeridianApplication application = new PolarMeridianApplication();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.dispatcher.start();
        application.http.start();
    }

    private MailerRegularBuilder<?> builder(String host) {
        var builder = mail.mailerBuilder()
                .withSMTPServer(host, 587, "platform", "local-demo-only")
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(1).withQueueCapacity(1)
                        .rejectWhenFull().build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1)
                        .withClaimTimeout(Duration.ofMillis(2000)).build())
                .withMailSendTimeout(Duration.ofSeconds(12));
        telemetry.configure(builder);
        return builder;
    }

    private PolarMeridianDispatcher.MessagePreparation preparation() {
        return new PolarMeridianDispatcher.MessagePreparation() {
            public void checkPermissions(PolarMeridianDispatcher.Job job) {
                // Every job in this local fixture is trusted. A real adapter must authorize the requesting application and its workload here.
                if (!List.of("URGENT", "ROUTINE", "BULK").contains(job.workload.name())) {
                    throw new IllegalArgumentException("Unapproved workload");
                }
            }

            public PolarMeridianDispatcher.ComposedMail compose(PolarMeridianDispatcher.Job job) {
                return composeForWorkload(job);
            }

            public void checkFinalMessage(PolarMeridianDispatcher.Job job, Mailer selected, MailRehearsal rehearsal) {
                if (selected.getOperationalConfig().isTransportModeLoggingOnly()) {
                    throw new IllegalArgumentException("Real work cannot use logging-only transport");
                }
                if (rehearsal.getEnvelopeRecipients().stream().anyMatch(address -> !address.endsWith("@example.test"))) {
                    throw new IllegalArgumentException("This local demonstration accepts only its synthetic recipients");
                }
            }
        };
    }

    private PolarMeridianDispatcher.ComposedMail composeForWorkload(PolarMeridianDispatcher.Job job) {
        String scenario = storage.job(job.requestId).get("scenario").toString();
        var email = composeScenarioEmail(job.requestId, scenario);
        Mailer selected = job.workload == PolarMeridianDispatcher.Workload.BULK ? mailers.bulk : mailers.urgent;
        var retention = "protected".equals(scenario)
                ? PolarMeridianDispatcher.Retention.EXACT_EML
                : PolarMeridianDispatcher.Retention.METADATA_ONLY;
        return new PolarMeridianDispatcher.ComposedMail(selected, "eu-application-mail", email, retention);
    }

    private org.simplejavamail.api.email.Email composeScenarioEmail(String requestId, String scenario) {
        var builder = mail.emailBuilder().startingBlank()
                .from("orders@example.test")
                .withBounceTo("bounce@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("unknown".equals(scenario) ? "lost@example.test" : "reader@example.test")
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .withSubject("Synthetic request " + requestId)
                .withPlainText("Prepared by Polar Meridian's real Dispatcher");
        if ("partial".equals(scenario)) {
            builder.withRecipients(mail.recipientBuilder().withAddress("reject@example.test")
                            .withType(jakarta.mail.Message.RecipientType.TO).build())
                    .withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder()
                            .withSendingToAcceptedRecipients(true).build());
        }
        if ("protected".equals(scenario)) {
            builder.signWithSmime(ProtectedContent.signing())
                    .encryptWithSmime(ProtectedContent.encryption());
        }
        return builder.buildEmail();
    }

    private void registerEndpoints() {
        http.route("/jobs", request -> {
            var query = request.parameters();
            if (request.method().equals("POST")) {
                String id = query.get("id");
                String workload = query.getOrDefault("workload", "URGENT");
                String scenario = query.getOrDefault("scenario", "happy");
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}") || !List.of("URGENT", "ROUTINE", "BULK").contains(workload)
                        || !List.of("happy", "protected", "partial", "unknown").contains(scenario)) {
                    throw new ExampleHttp.Rejection(400, "Choose a known workload/scenario and a short job ID");
                }
                storage.enqueue(id, workload, scenario);
            }
            return database.rows("SELECT * FROM pm_jobs ORDER BY created_at");
        });
        http.route("/attempts", request -> storage.attempts());
        http.route("/events", request -> database.rows("SELECT * FROM pm_events ORDER BY id"));
        http.route("/limits", request -> {
            if (request.method().equals("POST")) {
                requireOperator(request);
                Map<String, Object> input = request.json();
                int perSecond = ((Number) input.get("perSecond")).intValue();
                int perDay = ((Number) input.get("perDay")).intValue();
                if (perSecond < 0 || perDay < 0) {
                    throw new ExampleHttp.Rejection(400, "Use nonnegative synthetic allocations");
                }
                database.update("UPDATE mail_dispatch_limit SET per_second=?,per_24_hours=? WHERE sending_quota_group='eu-application-mail' AND workload=?",
                        perSecond, perDay, String.valueOf(input.get("workload")));
            }
            return database.rows("SELECT workload,recipients,attempt_id FROM mail_dispatch_charge ORDER BY granted_at");
        });
        http.route("/observer-failure", request -> {
            requireOperator(request);
            storage.failOutcomeWrites.set(Boolean.TRUE.equals(request.json().get("enabled")));
            return Map.of("enabled", storage.failOutcomeWrites.get());
        });
        http.route("/archive-proof", request -> archiveProof(request.parameters().get("id")));
    }

    private void requireOperator(ExampleHttp.Request request) {
        if (!request.header("X-Demo-Operator").equals("local-operator")) {
            throw new ExampleHttp.Rejection(403, "Demo operator token required");
        }
    }

    private Object archiveProof(String id) throws Exception {
        var attempt = storage.attempts().stream().filter(row -> id.equals(row.get("request_id"))).findFirst().orElseThrow();
        byte[] retained = storage.archived(id);
        byte[] captured = null;
        String path = URLEncoder.encode(attempt.get("id").toString(), StandardCharsets.UTF_8);
        for (String relay : List.of("urgent-a", "urgent-b", "bulk")) {
            try {
                captured = ExampleHttp.getBytes("http://" + relay + ":8025/messages/" + path);
                break;
            } catch (java.io.IOException absentHere) {
                // The selected workload route, including its approved failover, may own the captured copy.
            }
        }
        if (captured == null) {
            throw new IllegalStateException("No captured submission is available");
        }
        var parsed = ProtectedContent.readProtected(captured);
        return Map.of("sameBytes", java.util.Arrays.equals(retained, captured), "sha256", ProtectedContent.sha256(retained),
                "verification", parsed.getOriginalSmimeDetails().getVerificationStatus().name(), "readable", parsed.getPlainText() != null);
    }

    @Override
    public void close() {
        try {
            ResourceCleanup.closeAll(http, dispatcher::stopAndAwait, this::closeMailers, this::stopResultStorage, telemetry, database);
        } catch (Exception failed) {
            throw new IllegalStateException("Dispatcher resources did not close cleanly", failed);
        }
    }

    private void closeMailers() throws Exception {
        ResourceCleanup.closeAll(mailers.all().toArray(Mailer[]::new));
    }

    private void stopResultStorage() throws InterruptedException {
        observations.shutdown();
        if (!observations.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Observation storage has not drained");
        }
    }
}
