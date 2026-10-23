import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.examples.support.ArchiveCipher;
import org.simplejavamail.examples.support.ProtectedContent;
import org.simplejavamail.examples.support.SqlStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Let two dispatcher processes share jobs without both submitting the same email.
 * Follow {@link #claimNextDue} to see how a job gets an owner and a fresh token; later updates must match both.
 * Unresolved claims stay held for review rather than expiring into an automatic resend.
 */
final class PolarStorage implements PolarMeridianDispatcher.MailOutbox, PolarMeridianDispatcher.AttemptArchive {
    final SqlStore database;
    final AtomicBoolean failOutcomeWrites = new AtomicBoolean();
    private final ArchiveCipher cipher = new ArchiveCipher();
    private final String instance = UUID.randomUUID().toString();

    PolarStorage(SqlStore database) {
        this.database = database;
        initializeSchema();
    }

    private void initializeSchema() {
        database.transaction(connection -> {
            // Both instances can start together; serialize schema creation, not dispatch or network IO.
            SqlStore.rows(connection, "SELECT pg_advisory_xact_lock(764)");
            SqlStore.update(connection, "CREATE TABLE IF NOT EXISTS pm_jobs (id TEXT PRIMARY KEY, workload TEXT NOT NULL, scenario TEXT NOT NULL, "
                    + "state TEXT NOT NULL DEFAULT 'READY', claim_token TEXT, owner TEXT, not_before TIMESTAMPTZ DEFAULT clock_timestamp(), "
                    + "expires_at TIMESTAMPTZ DEFAULT clock_timestamp()+INTERVAL '1 hour', created_at TIMESTAMPTZ DEFAULT clock_timestamp(), submission TEXT)");
            SqlStore.update(connection, "CREATE TABLE IF NOT EXISTS pm_attempts (id TEXT PRIMARY KEY, request_id TEXT NOT NULL, envelope TEXT NOT NULL, "
                    + "protected_eml BYTEA, sha256 TEXT, submission TEXT, observation TEXT, diagnostics TEXT)");
            SqlStore.update(connection, "CREATE TABLE IF NOT EXISTS pm_events (id BIGSERIAL PRIMARY KEY, kind TEXT NOT NULL, detail TEXT NOT NULL)");
            if (SqlStore.rows(connection, "SELECT to_regclass('mail_dispatch_limit') AS existing").getFirst().get("existing") == null) {
                try (var statement = connection.createStatement()) {
                    statement.execute(Files.readString(Path.of("/opt/demo/dispatch-limits.sql")));
                }
            }
            return null;
        });
    }

    @Override
    public Optional<PolarMeridianDispatcher.Job> claimNextDue(PolarMeridianDispatcher.Workload workload, Instant now) {
        return database.transaction(connection -> {
            var rows = SqlStore.rows(connection, "SELECT id FROM pm_jobs WHERE workload=? AND state='READY' "
                    + "AND not_before<=clock_timestamp() AND expires_at>clock_timestamp() ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1", workload.name());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            String id = rows.getFirst().get("id").toString();
            String token = UUID.randomUUID().toString();
            SqlStore.update(connection, "UPDATE pm_jobs SET state='CLAIMED',claim_token=?,owner=? WHERE id=?", token, instance, id);
            return Optional.of(new PolarMeridianDispatcher.Job(id, token, workload));
        });
    }

    @Override
    public void defer(PolarMeridianDispatcher.Job job, Instant notBefore) {
        updateClaim(job, "READY", null, java.sql.Timestamp.from(notBefore));
    }

    @Override
    public void markSubmitted(PolarMeridianDispatcher.Job job, MailSubmissionReceipt receipt) {
        updateClaim(job, "SUBMITTED", receipt.getStatus().name(), java.sql.Timestamp.from(Instant.now()));
    }

    @Override
    public void holdForReview(PolarMeridianDispatcher.Job job, Throwable reason) {
        updateClaim(job, "HELD", reason.getClass().getSimpleName(), java.sql.Timestamp.from(Instant.now()));
    }

    private void updateClaim(PolarMeridianDispatcher.Job job, String state, String result, java.sql.Timestamp notBefore) {
        if (database.update("UPDATE pm_jobs SET state=?,submission=?,not_before=? WHERE id=? AND claim_token=? AND owner=?",
                state, result, notBefore, job.requestId, job.claimToken, instance) != 1) {
            throw new IllegalStateException("This dispatcher no longer owns the claim; do not submit it again");
        }
    }

    @Override
    public void insertAttempt(String attemptId, String requestId, PolarMeridianDispatcher.AttemptEvidence evidence) {
        byte[] eml = evidence.getEmlBytes().orElse(null);
        String envelope = evidence.recipients.stream().map(recipient -> recipient.getAddress()).reduce((left, right) -> left + "," + right).orElse("");
        database.update("INSERT INTO pm_attempts(id,request_id,envelope,protected_eml,sha256) VALUES (?,?,?,?,?)", attemptId, requestId,
                envelope, eml == null ? null : cipher.encrypt(eml), eml == null ? null : ProtectedContent.sha256(eml));
    }

    @Override
    public void recordOutcome(String attemptId, MailSendOutcome outcome) {
        if (failOutcomeWrites.get()) {
            recordInjectedResultFailure(attemptId);
            throw new IllegalStateException("Synthetic archive outcome write failure");
        }
        UUID observation = outcome.getObservationId();
        database.update("UPDATE pm_attempts SET submission=?,observation=?,diagnostics=? WHERE id=?",
                outcome.getSubmissionReceipt().map(receipt -> receipt.getStatus().name()).orElse("NO_RECEIPT"),
                observation == null ? null : observation.toString(),
                outcome.getDiagnostics().map(Object::toString).orElse("unavailable"), attemptId);
    }

    private void recordInjectedResultFailure(String attemptId) {
        var saved = database.rows("SELECT request_id FROM pm_attempts WHERE id=?", attemptId);
        String requestId = saved.isEmpty() ? "UNKNOWN" : saved.getFirst().get("request_id").toString();
        event("RESULT_WRITE_FAILED", "requestId=" + requestId + "; attemptId=" + attemptId
                + "; The injected archive failure needs reconciliation; it must not resend SMTP");
    }

    void enqueue(String id, String workload, String scenario) {
        database.update("INSERT INTO pm_jobs(id,workload,scenario) VALUES (?,?,?) ON CONFLICT DO NOTHING", id, workload, scenario);
    }

    Map<String, Object> job(String id) {
        return database.rows("SELECT * FROM pm_jobs WHERE id=?", id).getFirst();
    }

    byte[] archived(String requestId) {
        return cipher.decrypt((byte[]) database.rows("SELECT protected_eml FROM pm_attempts WHERE request_id=? AND protected_eml IS NOT NULL", requestId)
                .getFirst().get("protected_eml"));
    }

    List<Map<String, Object>> attempts() {
        return database.rows("SELECT id,request_id,envelope,sha256,submission,observation,diagnostics,protected_eml IS NOT NULL AS retained FROM pm_attempts");
    }

    void event(String kind, String detail) {
        database.update("INSERT INTO pm_events(kind,detail) VALUES (?,?)", kind, detail);
    }
}
