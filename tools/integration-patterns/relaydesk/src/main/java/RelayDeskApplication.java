import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.examples.support.ExampleHttp;
import org.simplejavamail.examples.support.SqlStore;
import org.simplejavamail.examples.support.ResourceCleanup;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Give each customer an independently operated mail route while keeping unsent jobs in PostgreSQL.
 * Follow {@link #dispatchNext(TenantRoute)} for one job and {@link TenantRoute#replace(String)} for a credential change.
 * The unchanged article helpers supply SJM calls; the local tokens and approved hosts are only demonstration security.
 */
public final class RelayDeskApplication implements AutoCloseable {
    private final SqlStore database = new SqlStore();
    private final ExampleHttp http = new ExampleHttp();
    private final ScheduledExecutorService polling = Executors.newScheduledThreadPool(2);
    private final ThreadPoolExecutor observers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, TenantRoute> routes;

    public RelayDeskApplication() throws Exception {
        initializeStorage();
        routes = Map.of("a", new TenantRoute("a", "smtp-a"), "b", new TenantRoute("b", "smtp-b"));
        registerEndpoints();
    }

    public static void main(String[] args) throws Exception {
        RelayDeskApplication application = new RelayDeskApplication();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.start();
    }

    private void start() {
        for (TenantRoute route : routes.values()) {
            polling.scheduleWithFixedDelay(() -> dispatchNext(route), 0, 50, TimeUnit.MILLISECONDS);
        }
        http.start();
    }

    private void initializeStorage() {
        database.update("CREATE TABLE IF NOT EXISTS rd_jobs (id TEXT PRIMARY KEY, tenant TEXT NOT NULL, scenario TEXT NOT NULL, "
                + "state TEXT NOT NULL DEFAULT 'READY', revision INTEGER, message_id TEXT, submission TEXT, observation_id TEXT, "
                + "created_at TIMESTAMPTZ DEFAULT clock_timestamp())");
        // Restart does not prove an in-flight message was unsent. One process owns this demo registry.
        database.update("UPDATE rd_jobs SET state='HELD_AFTER_RESTART' WHERE state='SENDING'");
    }

    private void registerEndpoints() {
        http.route("/jobs", request -> {
            TenantRoute route = authorizedRoute(request);
            if (request.method().equals("POST")) {
                return enqueue(route, request);
            }
            return database.rows("SELECT * FROM rd_jobs WHERE tenant=? ORDER BY created_at", route.tenant);
        });
        http.route("/routes", request -> {
            requireOperator(request);
            if (request.method().equals("POST")) {
                String tenant = request.parameters().get("tenant");
                TenantRoute route = routes.get(tenant);
                if (route == null) {
                    throw new ExampleHttp.Rejection(400, "Choose configured tenant a or b");
                }
                route.replace(String.valueOf(request.json().get("password")));
            }
            return routes.values().stream().map(TenantRoute::evidence).toList();
        });
        http.route("/reports", request -> {
            TenantRoute route = authorizedRoute(request);
            Email report = route.mail.converter()
                    .fromEml(new java.io.ByteArrayInputStream(request.body()))
                    .toEmail();
            var facts = report.getDeliveryStatusReport();
            if (facts == null || facts.getEnvelopeId() == null) {
                return Map.of("result", "UNCORRELATED");
            }
            // A guessed token cannot update another tenant. Ingress trust and recipient verification are also required in production.
            return Map.of("result", database.rows("SELECT id FROM rd_jobs WHERE tenant=? AND message_id=?", route.tenant,
                    "<" + facts.getEnvelopeId() + "@" + route.tenant + ".example.test>").isEmpty() ? "UNCORRELATED" : "CORRELATED");
        });
    }

    private TenantRoute authorizedRoute(ExampleHttp.Request request) {
        String token = request.header("X-Demo-Tenant-Token");
        TenantRoute route = routes.values().stream().filter(candidate -> token.equals("tenant-" + candidate.tenant + "-local"))
                .findFirst().orElseThrow(() -> new ExampleHttp.Rejection(403, "Supply this local demo's tenant token"));
        String selected = request.parameters().getOrDefault("tenant", route.tenant);
        if (!selected.equals(route.tenant)) {
            throw new ExampleHttp.Rejection(403, "This token cannot select another tenant's route");
        }
        return route;
    }

    private void requireOperator(ExampleHttp.Request request) {
        if (!request.header("X-Demo-Operator").equals("local-operator")) {
            throw new ExampleHttp.Rejection(403, "This local route-management operation needs the demo operator token");
        }
    }

    private Object enqueue(TenantRoute route, ExampleHttp.Request request) {
        String id = request.parameters().getOrDefault("id", UUID.randomUUID().toString());
        String scenario = request.parameters().getOrDefault("scenario", "happy");
        if (!id.matches("[A-Za-z0-9_-]{1,100}") || !List.of("happy", "partial", "unknown").contains(scenario)) {
            throw new ExampleHttp.Rejection(400, "Use a short job ID and happy, partial or unknown scenario");
        }
        database.update("INSERT INTO rd_jobs(id,tenant,scenario) VALUES (?,?,?) ON CONFLICT DO NOTHING", id, route.tenant, scenario);
        var rows = database.rows("SELECT * FROM rd_jobs WHERE id=? AND tenant=?", id, route.tenant);
        if (rows.isEmpty()) {
            throw new ExampleHttp.Rejection(403, "That job does not belong to this tenant");
        }
        return rows.getFirst();
    }

    private void dispatchNext(TenantRoute route) {
        if (!route.lock.readLock().tryLock()) {
            return; // Replacement pauses only this tenant, not the other tenant's dispatcher.
        }
        try {
            if (route.paused) {
                return;
            }
            ClaimedJob job = claimNextReadyJob(route);
            if (job != null) {
                submit(route, job);
            }
        } catch (RuntimeException failed) {
            System.err.println("RelayDesk polling needs attention: " + failed.getClass().getSimpleName());
        } finally {
            route.lock.readLock().unlock();
        }
    }

    private ClaimedJob claimNextReadyJob(TenantRoute route) {
        var waiting = database.rows("SELECT * FROM rd_jobs WHERE tenant=? AND state='READY' ORDER BY created_at LIMIT 1", route.tenant);
        if (waiting.isEmpty()) {
            return null;
        }
        var saved = waiting.getFirst();
        String id = saved.get("id").toString();
        UUID attempt = UUID.randomUUID();
        String messageId = "<" + attempt + "@" + route.tenant + ".example.test>";
        // Save the attempt identity before asynchronous admission. A process restart must not treat an unresolved attempt as unsent.
        if (database.update("UPDATE rd_jobs SET state='SENDING',revision=?,message_id=? WHERE id=? AND state='READY'",
                route.revision, messageId, id) != 1) {
            return null;
        }
        return new ClaimedJob(saved.get("scenario").toString(), attempt, messageId);
    }

    private void submit(TenantRoute route, ClaimedJob job) {
        final Email reply;
        try {
            reply = composeReply(route, job);
        } catch (RuntimeException preparationFailed) {
            // Composition happened before SJM invocation, so there can be no terminal result callback for this failure.
            database.update("UPDATE rd_jobs SET state='HELD_PREPARATION',submission='NO_RECEIPT' WHERE message_id=?", job.messageId());
            return;
        }
        try {
            RelayDeskMailExamples.submitReply(route.mailer, reply);
        } catch (MailSendRejectedException rejected) {
            if (rejected.getReason() == AsyncQueueRejectionReason.QUEUE_FULL) {
                database.update("UPDATE rd_jobs SET state='READY' WHERE message_id=?", job.messageId());
            } else {
                database.update("UPDATE rd_jobs SET state='HELD',submission='NOT_ADMITTED' WHERE message_id=?", job.messageId());
            }
        }
    }

    private Email composeReply(TenantRoute route, ClaimedJob job) {
        String scenario = job.scenario();
        var builder = route.mail.emailBuilder().startingBlank()
                .withRecipients(route.mail.recipientBuilder().withAddress("unknown".equals(scenario) ? "lost@example.test" : "reader@example.test")
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .withSubject("Synthetic tenant " + route.tenant)
                .withPlainText("Prepared by the RelayDesk Docker application")
                .fixingMessageId(job.messageId())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder()
                        .fixingEnvelopeId(job.attempt().toString()).build());
        if ("partial".equals(scenario)) {
            builder.withRecipients(route.mail.recipientBuilder().withAddress("reject@example.test")
                            .withType(jakarta.mail.Message.RecipientType.TO).build())
                    .withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder()
                            .withSendingToAcceptedRecipients(true).build());
        }
        return builder.buildEmail();
    }

    private void recordOutcome(String messageId, MailSendOutcome outcome) {
        if (outcome.getFailure().filter(failure -> failure instanceof MailSendRejectedException rejected
                && rejected.getReason() == AsyncQueueRejectionReason.QUEUE_FULL).isPresent()) {
            database.update("UPDATE rd_jobs SET state='READY' WHERE message_id=?", messageId);
            return;
        }
        String status = outcome.getSubmissionReceipt().map(receipt -> receipt.getStatus().name()).orElse("NO_RECEIPT");
        UUID observation = outcome.getObservationId();
        database.update("UPDATE rd_jobs SET state=?,submission=?,observation_id=? WHERE message_id=?",
                outcome.isSuccessful() ? "SUBMITTED" : "HELD", status,
                observation == null ? null : observation.toString(), messageId);
    }

    @Override
    public void close() {
        try {
            ResourceCleanup.closeAll(http, this::stopDispatch, this::closeTenantMailers, this::stopResultStorage, database);
        } catch (Exception failed) {
            throw new IllegalStateException("Tenant resources did not close cleanly", failed);
        }
    }

    private void stopDispatch() throws InterruptedException {
        polling.shutdown();
        if (!polling.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Tenant dispatch has not stopped");
        }
    }

    private void closeTenantMailers() throws Exception {
        ResourceCleanup.closeAll(routes.values().stream().map(route -> route.mailer).toArray(Mailer[]::new));
    }

    private void stopResultStorage() throws InterruptedException {
        observers.shutdown();
        if (!observers.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Result storage has not drained");
        }
    }

    private record ClaimedJob(String scenario, UUID attempt, String messageId) {
    }

    private final class TenantRoute {
        final String tenant;
        final String host;
        final SimpleJavaMail mail;
        final UUID cluster = UUID.randomUUID();
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        volatile Mailer mailer;
        volatile boolean paused;
        volatile int revision = 1;
        volatile int retired;

        TenantRoute(String tenant, String host) {
            this.tenant = tenant;
            this.host = host;
            Properties defaults = new Properties();
            defaults.setProperty("simplejavamail.defaults.from.address", "support@" + tenant + ".example.test");
            mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties("approved tenant " + tenant, defaults).load());
            mailer = build("local-demo-only");
        }

        private Mailer build(String password) {
            int connectionsPerTenant = 1;
            int executingSendsPerTenant = 1;
            int queuedSendsPerTenant = 1;
            int recipientsPerPeriod = 2;
            Duration sendingPeriod = Duration.ofSeconds(1);
            return RelayDeskMailExamples.createMailer(mail, cluster, host, "tenant-" + tenant, password,
                    connectionsPerTenant, executingSendsPerTenant, queuedSendsPerTenant, "tenant-" + tenant, recipientsPerPeriod, sendingPeriod,
                    RelayDeskMailExamples.observerFor(RelayDeskApplication.this::recordOutcome), observers);
        }

        void replace(String password) throws Exception {
            lock.writeLock().lock();
            try {
                paused = true;
                mailer.close(); // Wait for admitted sends before retiring this route, not while holding an SMTP/registry monitor.
                retired++;
                Mailer replacement = build(password);
                try {
                    RelayDeskMailExamples.checkCredentials(replacement);
                    mailer = replacement;
                    revision++;
                    paused = false;
                } catch (Exception failed) {
                    // Probe reports are bounded/redacted by SJM; operators need the unsuccessful check, not a silent paused route.
                    System.err.println("Replacement credential check did not pass: " + failed.getMessage());
                    replacement.close();
                    throw failed; // Remain paused; do not fall back to obsolete credentials.
                }
            } finally {
                lock.writeLock().unlock();
            }
        }

        Map<String, Object> evidence() {
            return Map.of("tenant", tenant, "revision", revision, "paused", paused, "retired", retired);
        }
    }
}
