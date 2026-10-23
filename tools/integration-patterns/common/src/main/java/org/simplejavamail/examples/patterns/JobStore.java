package org.simplejavamail.examples.patterns;

import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps the application's mail jobs and evidence in SQL, separately from the framework's execution history. */
@Repository
public class JobStore {
    private final JdbcTemplate database;

    public JobStore(JdbcTemplate database) {
        this.database = database;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void holdInterruptedSubmissions() {
        // These six small demos run one application instance. Only then does startup mean the previous worker has stopped.
        // A multi-replica application needs worker-owned claims, as the Polar Meridian example demonstrates; do not scale this guard as-is.
        // A process can die after SMTP accepted but before our commit. Hold that uncertainty rather than authorizing another SMTP call.
        database.update("UPDATE mail_jobs SET state='HELD_AFTER_RESTART' WHERE state='SENDING'");
    }

    @Transactional
    public Map<String, Object> create(String id, String scenario) {
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Use a job ID of 1 to 100 letters, numbers, underscores or hyphens");
        }
        if (!List.of("happy", "partial", "unknown", "delayed").contains(scenario)) {
            throw new IllegalArgumentException("Choose happy, partial, unknown or delayed");
        }
        database.update("INSERT INTO mail_jobs(id,attempt_id,scenario) VALUES (?,?,?) ON CONFLICT(id) DO NOTHING", id, UUID.randomUUID().toString(), scenario);
        return get(id);
    }

    public Map<String, Object> get(String id) {
        return database.queryForMap("SELECT * FROM mail_jobs WHERE id=?", id);
    }

    public List<Map<String, Object>> all() {
        return database.queryForList("SELECT * FROM mail_jobs ORDER BY id");
    }

    public List<String> ready() {
        return database.queryForList("SELECT id FROM mail_jobs WHERE state='READY' ORDER BY id", String.class);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginSubmission(String id) {
        // Commit intent before SMTP even when a batch step has its own transaction. Rollback must not make an accepted mail look unsent.
        return database.update("UPDATE mail_jobs SET state='SENDING',updated_at=CURRENT_TIMESTAMP WHERE id=? AND state='READY'", id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMailOutcome(MailSendOutcome outcome) {
        String id = jobIdFromMessageId(outcome.getInitialMessageId());
        MailSubmissionReceipt receipt = outcome.getSubmissionReceipt().orElse(null);
        String state = outcome.isSuccessful() ? "SUBMITTED" : "HELD";
        String status = receipt == null ? "NO_RELIABLE_RECEIPT" : receipt.getStatus().name();
        String accepted = receipt == null ? "" : String.join(",", receipt.getAcceptedRecipients());

        // Acceptance and operation success are different facts: cleanup can fail after the server accepted the message.
        // A failed SQL write must leave the earlier SENDING guard intact, not downgrade acceptance in a second catch block.
        database.update("UPDATE mail_jobs SET state=?,submission_status=?,accepted=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                state, status, accepted, id);
    }

    private String jobIdFromMessageId(String messageId) {
        if (messageId == null || !messageId.matches("<[A-Za-z0-9_-]{1,100}@patterns\\.example\\.test>")) {
            throw new IllegalArgumentException("This example only records results for the Message-IDs assigned by MailWork");
        }
        return messageId.substring(1, messageId.indexOf('@'));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPreparationFailure(String id) {
        // Email construction failed before calling the Mailer, so no result-observer callback can record this case.
        database.update("UPDATE mail_jobs SET state='HELD',submission_status='NO_RELIABLE_RECEIPT',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        event(id, "PREPARATION_NEEDS_REVIEW", "Email construction failed before submission; inspect the job instead of replaying it blindly");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FeedbackResult recordFeedback(String contentHash, String envelopeId, String action, String status) {
        if (!retainNewReport(contentHash, envelopeId, action, status)) {
            return new FeedbackResult("DUPLICATE", null);
        }

        String id = findJobForAttempt(envelopeId);
        if (id == null) {
            event(null, "UNCORRELATED_REPORT", "No matching attempt; no customer state was changed");
            return new FeedbackResult("UNCORRELATED", null);
        }

        Map<String, Object> current = database.queryForMap("SELECT * FROM mail_jobs WHERE id=? FOR UPDATE", id);
        String previous = (String) current.get("delivery_action");
        if (wouldReplaceFinalDeliveryResult(previous, action)) {
            event(id, "FEEDBACK_NEEDS_REVIEW", "A final delivery state already exists; retained the new report without overwriting it");
            return new FeedbackResult("IGNORED_OUT_OF_ORDER", id);
        }

        database.update("UPDATE mail_jobs SET delivery_action=?,delivery_status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", action, status, id);
        return new FeedbackResult("RECORDED", id);
    }

    private boolean retainNewReport(String contentHash, String envelopeId, String action, String status) {
        return database.update("INSERT INTO received_reports(content_hash,envelope_id,action,status) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                contentHash, envelopeId, action, status) == 1;
    }

    private String findJobForAttempt(String envelopeId) {
        List<String> ids = database.queryForList("SELECT id FROM mail_jobs WHERE attempt_id=?", String.class, envelopeId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private boolean wouldReplaceFinalDeliveryResult(String previous, String incoming) {
        boolean alreadyFinal = "FAILED".equals(previous) || "DELIVERED".equals(previous);
        return alreadyFinal && !incoming.equals(previous);
    }

    public void event(String id, String kind, String detail) {
        database.update("INSERT INTO application_events(job_id,kind,detail) VALUES (?,?,?)", id, kind, detail);
    }

    public List<Map<String, Object>> events() {
        return database.queryForList("SELECT * FROM application_events ORDER BY id");
    }

    /** A job ID is present only when this report was correlated; duplicate and unrelated reports have none. */
    public record FeedbackResult(String result, String jobId) {
        public boolean needsHandoff() {
            return "RECORDED".equals(result);
        }
    }
}
