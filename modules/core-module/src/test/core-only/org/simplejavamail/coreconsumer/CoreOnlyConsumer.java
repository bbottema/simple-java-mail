package org.simplejavamail.coreconsumer;

import jakarta.mail.Message.RecipientType;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.SimpleJavaMailConfig;

/** Compile-only application code: bootstrap supplies these core contracts; no main implementation or transport provider is on the compile path. */
public final class CoreOnlyConsumer {
    public static MailSend<MailSubmissionReceipt> composeAndSend(final EmailStartingBuilder emails, final MailerRegularBuilder mailers,
            final IRecipientBuilder recipientBuilder, final IRecipientsBuilder groupBuilder) {
        final Recipient recipient = recipientBuilder.withName("Reader").withAddress("reader@example.org").withType(RecipientType.TO).build();
        groupBuilder.withRecipient("archive@example.org", RecipientType.BCC).buildRecipients();
        final Email email = emails.startingBlank().from("sender@example.org").withRecipients(recipient)
                .dontApplyDefaultValueFor(EmailProperty.SUBJECT).withSubject("Core API consumer").buildEmail();
        final Mailer mailer = mailers.withMailSendObserver(CoreOnlyConsumer::inspect).buildMailer();
        return mailer.async().sendMail(email);
    }

    public static String inspect(final MailSendOutcome outcome) {
        return outcome.getDiagnostics().map(CoreOnlyConsumer::inspect).orElse("No diagnostics");
    }

    private static String inspect(final MailSendDiagnostics report) {
        report.getConnectionHistory().forEach(step -> step.getElapsed().ifPresent(Object::toString));
        report.getSubmissionHistory().forEach(step -> step.getSubmissionStatus().name());
        return report.getPreparation().getElapsed().map(Object::toString).orElseGet(() -> report.getPreparation().getUnavailableReason().orElse(""));
    }

    public static Email convert(final ConfiguredEmailConverter converter, final SimpleJavaMailConfig config) {
        config.getDiagnostics().getGroups().forEach(group -> config.getDiagnostics().getProperties(group));
        return converter.emlToEmailBuilder(new java.io.ByteArrayInputStream(
                "From: sender@example.org\r\nTo: reader@example.org\r\n\r\nBody".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).buildEmail();
    }
}
