package org.simplejavamail.examples.patterns;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.DeliveryStatusReport;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.RecipientDeliveryReport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Accepts local demo jobs and supplied notification files. These trusted-input endpoints are not a public mail-receiving service. */
@RestController
public class PatternEndpoints {
    private final SimpleJavaMail mail;
    private final JobStore jobs;
    private final PatternPorts.WorkTrigger trigger;
    private final ObjectProvider<PatternPorts.FeedbackSink> feedback;

    public PatternEndpoints(SimpleJavaMail mail, JobStore jobs, PatternPorts.WorkTrigger trigger, ObjectProvider<PatternPorts.FeedbackSink> feedback) {
        this.mail = mail;
        this.jobs = jobs;
        this.trigger = trigger;
        this.feedback = feedback;
    }

    @PostMapping("/jobs/{id}")
    public Map<String, Object> submit(@PathVariable("id") String id, @RequestParam(name = "scenario", defaultValue = "happy") String scenario) {
        Map<String, Object> intent = jobs.create(id, scenario);
        if ("READY".equals(intent.get("state"))) {
            trigger.submit(id);
        }
        return jobs.get(id);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable("id") String id) {
        return jobs.get(id);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> all() {
        return jobs.all();
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return jobs.events();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidInput(IllegalArgumentException failure) {
        return Map.of("error", failure.getMessage());
    }

    @PostMapping(value = "/reports", consumes = {"message/rfc822", "application/octet-stream"})
    public Map<String, Object> report(@RequestBody byte[] bytes) {
        DeliveryStatusReport report = readReport(bytes);
        if (report == null) {
            return Map.of("result", "NO_ACCESSIBLE_REPORT");
        }
        if (!canProjectOneRecipient(report)) {
            jobs.event(null, "REPORT_NEEDS_REVIEW", report.toString());
            return Map.of("result", "REVIEW", "warnings", report.getWarnings());
        }
        // This example's business projection expects one recipient. SJM still preserves every occurrence in the report model.
        RecipientDeliveryReport recipient = report.getRecipients().getFirst();
        String status = recipient.getStatusCode() == null ? null : recipient.getStatusCode().toString();
        JobStore.FeedbackResult recorded = jobs.recordFeedback(contentHash(bytes), report.getEnvelopeId(),
                recipient.getAction().name(), status);
        if (recorded.needsHandoff() && !handOffRecordedFeedback(recorded.jobId(), recipient.getAction().name())) {
            return Map.of("result", "RECORDED_HANDOFF_FAILED", "summary", report.toString());
        }
        return Map.of("result", recorded.result(), "summary", report.toString());
    }

    private DeliveryStatusReport readReport(byte[] bytes) {
        Email notification = mail.converter()
                .fromEml(new ByteArrayInputStream(bytes))
                .toEmail();
        return notification.getDeliveryStatusReport();
    }

    private boolean canProjectOneRecipient(DeliveryStatusReport report) {
        return report.getEnvelopeId() != null && report.getWarnings().isEmpty() && report.getRecipients().size() == 1;
    }

    private boolean handOffRecordedFeedback(String id, String action) {
        try {
            // SQL evidence is already committed. Signaling the workflow or HTTP integration is a separate operation,
            // not an atomic transaction. Reposting identical EML is deduplicated, so a failed handoff needs reconciliation.
            feedback.ifAvailable(sink -> sink.accept(id, action));
            return true;
        } catch (RuntimeException handoffFailure) {
            jobs.event(id, "FEEDBACK_HANDOFF_FAILED", "Report retained, but downstream handoff failed; reconcile before publishing again");
            return false;
        }
    }

    private String contentHash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("This JVM has no required SHA-256 implementation", impossible);
        }
    }
}
