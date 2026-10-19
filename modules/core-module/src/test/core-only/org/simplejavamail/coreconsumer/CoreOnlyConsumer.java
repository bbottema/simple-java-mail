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
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.SimpleJavaMailConfig;

/** Compile-only application code: bootstrap supplies these core contracts; no main implementation or transport provider is on the compile path. */
public final class CoreOnlyConsumer {
    public static void inspectIncompleteConfiguration() {
        org.simplejavamail.api.mailer.config.MailSendRetryPolicy.builder().withMaximumAttempts(3).getRetryDelay();
        org.simplejavamail.api.mailer.config.FailoverPolicy.builder().getPrimaryMaximumRecheckInterval();
        org.simplejavamail.api.email.config.DkimConfig.builder().clearSigningAlgorithm().getSigningAlgorithm();
        org.simplejavamail.api.email.config.SmimeSigningConfig.builder().clearSignatureAlgorithm().getSignatureAlgorithm();
        org.simplejavamail.api.email.config.SmimeEncryptionConfig.builder().clearCipherAlgorithm().getCipherAlgorithm();
    }

    public static Integer inspectCommonTimeout(final org.simplejavamail.api.mailer.config.OperationalConfig configuration) {
        return configuration.getSessionTimeout();
    }

    public static java.util.Collection<Recipient> resetGroupPolicies(final IRecipientsBuilder group) {
        return group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients();
    }

    public static void fluentConversion(final ConfiguredEmailConverter converter, final Email email,
            final jakarta.mail.Session session, final jakarta.mail.internet.MimeMessage message) {
        converter.fromEml("From: sender@example.org\r\nTo: reader@example.org\r\n\r\nBody")
                .withFetchAttachmentData(false).toEmail();
        converter.fromEmail(email).withSession(session).toEmlBytes();
        converter.fromMimeMessage(message).toEml();
    }

    public static void sendInScopeAndClose(final Mailer mailer, final Email email) throws Exception {
        mailer.withOpenConnection(sender -> {
            sender.sendMail(email);
            final MailSubmissionReceipt receipt = sender.sendMail(email);
            receipt.getStatus();
        });
        final java.util.concurrent.CompletableFuture<Void> closing = mailer.closeAsync();
        closing.thenRun(() -> { });
    }

    public static String inspectDeliveryReport(final Email notification) {
        final org.simplejavamail.api.email.DeliveryStatusReport report = notification.getDeliveryStatusReport();
        if (report == null) return "No delivery report";
        final org.simplejavamail.api.email.ReportedMailServer server = report.getReportingServer();
        if (server != null) {
            server.getType();
            server.getName();
        }
        for (final org.simplejavamail.api.email.RecipientDeliveryReport recipient : report.getRecipients()) {
            final org.simplejavamail.api.email.ReportedRecipient address = recipient.getFinalRecipient();
            if (address != null) {
                address.getType();
                address.getAddress();
                final Recipient mailbox = address.getMailbox();
                if (mailbox != null) mailbox.getName();
            }
            final org.simplejavamail.api.email.EnhancedStatusCode status = recipient.getStatusCode();
            if (status != null) {
                status.getStatusClass();
                status.getSubject();
                status.getDetail();
                status.isSuccess();
                status.isTemporaryFailure();
                status.isPermanentFailure();
            }
            final org.simplejavamail.api.email.DeliveryDiagnostic diagnostic = recipient.getDiagnostic();
            if (diagnostic != null) {
                diagnostic.getType();
                diagnostic.getText();
                diagnostic.getSmtpResponse();
            }
            recipient.getAction();
            recipient.getRetryUntil();
        }
        return report.toString();
    }

    public static Mailer configureTelemetry(final MailerRegularBuilder<?> builder, final MailSendTelemetry telemetry) {
        builder.withMailSendTelemetry(telemetry);
        builder.withMailSendTelemetryEnabled(true);
        builder.withMailSendTelemetrySmtpEndpointAttributes(true);
        builder.isMailSendTelemetrySmtpEndpointAttributesEnabled();
        builder.resetMailSendTelemetrySmtpEndpointAttributes();
        builder.clearMailSendTelemetrySmtpEndpointAttributes();
        final Mailer mailer = builder.buildMailer();
        mailer.getOperationalConfig().isMailSendTelemetryEnabled();
        mailer.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled();
        return mailer;
    }

    public static MailSend<MailSubmissionReceipt> composeAndSend(final EmailStartingBuilder emails, final MailerRegularBuilder mailers,
            final IRecipientBuilder recipientBuilder, final IRecipientsBuilder groupBuilder) {
        final Recipient recipient = recipientBuilder.to("Reader", "reader@example.org");
        groupBuilder.withRecipient("archive@example.org", RecipientType.BCC).buildRecipients();
        final Email email = emails.startingBlank().from("sender@example.org").withRecipients(recipient)
                .dontApplyDefaultValueFor(EmailProperty.SUBJECT).withSubject("Core API consumer").buildEmail();
        final Mailer mailer = mailers.withMailSendResultObserver(CoreOnlyConsumer::inspect)
                .clearMailSendResultObserver()
                .withMailSendResultObserver(CoreOnlyConsumer::inspect)
                .withMailSendProgressObserver(CoreOnlyConsumer::inspectProgress)
                .clearMailSendProgressObserver()
                .withMailSendProgressObserver(CoreOnlyConsumer::inspectProgress)
                .buildMailer();
        return mailer.async().sendMail(email);
    }

    public static String inspect(final MailSendOutcome outcome) {
        final java.util.UUID observationId = outcome.getObservationId();
        outcome.getSubmissionReceipt().flatMap(MailSubmissionReceipt::getSmtpResponse)
                .flatMap(org.simplejavamail.api.mailer.SmtpServerResponse::getEnhancedStatusCode)
                .ifPresent(status -> {
                    status.isTemporaryFailure();
                    status.getSubject();
                    status.getDetail();
                });
        return outcome.getDiagnostics().map(CoreOnlyConsumer::inspect).orElse("No diagnostics");
    }

    private static void inspectProgress(final MailSendProgress progress) {
        progress.getObservationId().toString();
        progress.getObservedAt().toEpochMilli();
        progress.getElapsed().toNanos();
        progress.getStage().name();
        // A dashboard can label known details while still displaying future categories without an exhaustive switch.
        switch (progress.getStage()) {
            case WAITING_FOR_GREETING:
            case AUTHENTICATING:
            case SUBMITTING_RECIPIENTS:
                progress.getElapsed().toMillis();
                break;
            default:
                progress.getStage().toString();
        }
        progress.getBytesWritten().ifPresent(Object::toString);
        progress.getTotalBytes().ifPresent(Object::toString);
    }

    private static String inspect(final MailSendDiagnostics report) {
        report.getConnectionHistory().forEach(step -> step.getElapsed().ifPresent(Object::toString));
        report.getSubmissionHistory().forEach(step -> step.getSubmissionStatus().name());
        return report.getPreparation().getElapsed().map(Object::toString).orElseGet(() -> report.getPreparation().getUnavailableReason().orElse(""));
    }

    public static Email convert(final ConfiguredEmailConverter converter, final SimpleJavaMailConfig config) {
        config.getDiagnostics().getGroups().forEach(group -> config.getDiagnostics().getProperties(group));
        return converter.fromEml("From: sender@example.org\r\nTo: reader@example.org\r\n\r\nBody").toEmail();
    }
}
