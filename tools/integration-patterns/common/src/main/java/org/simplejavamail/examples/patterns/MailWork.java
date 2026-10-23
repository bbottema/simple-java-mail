package org.simplejavamail.examples.patterns;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Sends one claimed job. Repeated framework deliveries do not become permission to send the email again. */
@Service
public class MailWork {
    private final SimpleJavaMail mail;
    private final Mailer mailer;
    private final JobStore jobs;

    public MailWork(SimpleJavaMail mail, Mailer mailer, JobStore jobs) {
        this.mail = mail;
        this.mailer = mailer;
        this.jobs = jobs;
    }

    public void deliver(String id) {
        deliver(id, null);
    }

    public void deliver(String id, SmimeSigningConfig signing) {
        if (!jobs.beginSubmission(id)) {
            return;
        }

        // Commit the guard before preparing or submitting anything. A crash must not make an uncertain send look untouched.
        final Email email;
        try {
            email = prepareEmail(id, jobs.get(id), signing);
        } catch (RuntimeException preparationFailure) {
            jobs.recordPreparationFailure(id);
            return;
        }

        submitClaimedEmail(id, email);
    }

    private void submitClaimedEmail(String id, Email email) {
        try {
            mailer.sync().sendMail(email);
        } catch (RuntimeException failure) {
            // PatternApplication registers JobStore.recordMailOutcome as an inline result observer. Its receipt is authoritative,
            // even if sending throws because cleanup failed after acceptance. Never replace it with a receipt guessed here.
            // If that SQL write itself failed, the durable SENDING guard remains available for reconciliation instead of replay.
            jobs.event(id, "SUBMISSION_NEEDS_REVIEW", "Inspect the stored result; a failed operation may still have SMTP acceptance");
        }
    }

    private Email prepareEmail(String id, Map<String, Object> intent, SmimeSigningConfig signing) {
        EmailPopulatingBuilder builder = mail.emailBuilder()
                .startingBlank()
                .from("sender@example.test")
                .withSubject("Local integration example: " + id)
                .withPlainText("Synthetic example; no Internet delivery")
                // This application-owned correlation is for its result observer, not a provider deduplication guarantee.
                .fixingMessageId("<" + id + "@patterns.example.test>")
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder()
                        .fixingEnvelopeId(intent.get("attempt_id").toString()).build());
        String recipient = "unknown".equals(intent.get("scenario")) ? "lost@example.test" : "reader@example.test";
        builder.withRecipients(mail.recipientBuilder()
                .withAddress(recipient)
                .withType(jakarta.mail.Message.RecipientType.TO)
                .build());
        if ("partial".equals(intent.get("scenario"))) {
            builder.withSmtpDeliveryOptions(builder.getSmtpDeliveryOptions().toBuilder()
                    .withSendingToAcceptedRecipients(true).build());
            builder.withRecipients(mail.recipientBuilder()
                    .withAddress("reject@example.test")
                    .withType(jakarta.mail.Message.RecipientType.TO)
                    .build());
        }
        if (signing != null) {
            builder.signWithSmime(signing);
        }
        return builder.buildEmail();
    }
}
