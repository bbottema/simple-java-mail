package org.simplejavamail.coreconsumer;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
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
    /** Continuing application reads use invariant schema keys with core and its declared API dependencies only. */
    public static java.time.Duration inspectTypedConfiguration(final SimpleJavaMailConfig config) {
        final Integer port = config.get(org.simplejavamail.config.ConfigKeys.SMTP_PORT);
        final java.time.Duration timeout = config.get(org.simplejavamail.config.ConfigKeys.SESSION_TIMEOUT);
        final java.util.Map<String, String> extras = config.get(org.simplejavamail.config.ConfigKeys.EXTRA_PROPERTIES);
        final java.util.Map<String, SimpleJavaMailConfig> named = config.get(org.simplejavamail.config.ConfigKeys.NAMED_MAILERS);
        config.hasProperty(org.simplejavamail.config.ConfigKeys.SMTP_PORT);
        config.getPropertySource(org.simplejavamail.config.ConfigKeys.SESSION_TIMEOUT);
        return config.valueOrProperty(timeout, org.simplejavamail.config.ConfigKeys.SESSION_TIMEOUT, java.time.Duration.ofSeconds(30));
    }

    /** The public Mailer type supports ordinary scoped ownership without a broad throws Exception declaration. */
    public static void finishOwnedMailer(final Mailer mailer) throws java.util.concurrent.ExecutionException, InterruptedException {
        try (Mailer owned = mailer) {
            owned.getTransportStrategy();
        }
    }

    /** OAuth2 over implicit TLS uses existing core-owned configuration and provider contracts. */
    public static MailerRegularBuilder<?> implicitTlsOAuth2(final MailerRegularBuilder<?> builder,
            final org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider tokens) {
        return builder.withTransportStrategy(org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS_OAUTH2)
                .withOAuth2AccessTokenProvider(tokens);
    }

    /** Generic failure handling needs only core, including optional receipts and the typed classification. */
    public static org.simplejavamail.api.mailer.MailFailureKind sendAndInspectFailure(final Mailer mailer, final Email email) {
        try {
            final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
            receipt.isAcceptedForAtLeastOneRecipient();
            receipt.isFullyAcceptedForSubmission();
            return null;
        } catch (org.simplejavamail.MailException failure) {
            final java.util.Optional<MailSubmissionReceipt> receipt = failure.getSubmissionReceipt();
            receipt.ifPresent(facts -> facts.isFullyAcceptedForSubmission());
            failure.getCause();
            return failure.getKind();
        }
    }

    public static Mailer inspectFailover(final MailerRegularBuilder<?> builder) {
        final org.simplejavamail.api.mailer.config.FailoverPolicy policy = builder.getFailoverPolicy();
        if (policy != null) {
            policy.getPrimaryRecheckInterval();
        }
        return builder.getFailoverMailer();
    }

    public static void inspectIncompleteConfiguration() {
        org.simplejavamail.api.mailer.config.MailSendRetryPolicy.builder().withMaximumAttempts(3).getRetryDelay();
        org.simplejavamail.api.mailer.config.FailoverPolicy.builder().getPrimaryMaximumRecheckInterval();
        org.simplejavamail.api.email.config.DkimConfig.builder().clearSigningAlgorithm().getSigningAlgorithm();
        org.simplejavamail.api.email.config.SmimeSigningConfig.builder().clearSignatureAlgorithm().getSignatureAlgorithm();
        org.simplejavamail.api.email.config.SmimeEncryptionConfig.builder().clearCipherAlgorithm().getCipherAlgorithm();
        org.simplejavamail.api.email.config.DeliveryStatusNotification.builder().clearNotifyOptions().getNotifyOptions();
        org.simplejavamail.api.email.config.DeliveryStatusNotification.builder().clearReturnOption().getReturnOption();
        org.simplejavamail.api.email.config.DeliveryStatusNotification.builder().clearEnvelopeId().getEnvelopeId();
        org.simplejavamail.api.mailer.config.Pkcs12Config.builder().getPkcs12StoreData();
        org.simplejavamail.api.mailer.config.Pkcs12Config.builder().getStorePassword();
        org.simplejavamail.api.mailer.config.Pkcs12Config.builder().getKeyAlias();
        org.simplejavamail.api.mailer.config.Pkcs12Config.builder().getKeyPassword();
        org.simplejavamail.api.email.config.OpenPgpSigningConfig.builder().getSecretKeyRing();
        org.simplejavamail.api.email.config.OpenPgpSigningConfig.builder().getPassphrase();
        org.simplejavamail.api.email.config.OpenPgpSigningConfig.builder().getSigningKeyId();
        org.simplejavamail.api.email.config.OpenPgpSigningConfig.builder().getHashAlgorithm();
        org.simplejavamail.api.email.config.OpenPgpEncryptionConfig.builder().clearRecipientPublicKeyRings().getRecipientPublicKeyRings();
        org.simplejavamail.api.email.config.OpenPgpEncryptionConfig.builder().getSymmetricAlgorithm();
        org.simplejavamail.api.email.config.OpenPgpReceiveConfig.builder().clearVerificationKeyRings().getVerificationKeyRings();
        org.simplejavamail.api.email.config.OpenPgpReceiveConfig.builder().clearDecryptionKeyRings().getDecryptionKeyRings();
        org.simplejavamail.api.email.config.OpenPgpReceiveConfig.builder().clearPassphraseProvider().getPassphraseProvider();
    }

    public static java.time.Duration inspectCommonTimeout(final org.simplejavamail.api.mailer.config.OperationalConfig configuration) {
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
        final MailSendTelemetryConfig configuration = MailSendTelemetryConfig.builder()
                .withIntegration(telemetry).withEnabled(true).withSmtpEndpointAttributes(true).build();
        builder.withTelemetryConfig(configuration);
        builder.getTelemetryConfig().getSmtpEndpointAttributes();
        builder.withTelemetryConfig(configuration.toBuilder().resetSmtpEndpointAttributes().useConfiguredSmtpEndpointAttributes().build());
        final Mailer mailer = builder.buildMailer();
        mailer.getOperationalConfig().getTelemetryConfig().getEnabled();
        mailer.getOperationalConfig().getTelemetryConfig().getSmtpEndpointAttributes();
        return mailer;
    }

    public static MailSend<MailSubmissionReceipt> composeAndSend(final EmailStartingBuilder emails, final MailerRegularBuilder mailers,
            final IRecipientBuilder recipientBuilder, final IRecipientsBuilder groupBuilder) {
        final Recipient recipient = recipientBuilder.to("Reader", "reader@example.org");
        groupBuilder.withRecipient("archive@example.org", RecipientType.BCC).buildRecipients();
        final Email email = emails.startingBlank().from("sender@example.org").withRecipients(recipient)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(EmailProperty.SUBJECT).build()).withSubject("Core API consumer").buildEmail();
        final Mailer mailer = mailers.withSendHandlers(MailSendHandlers.builder()
                .withResultObserver(CoreOnlyConsumer::inspect).clearResultObserver().withResultObserver(CoreOnlyConsumer::inspect)
                .withProgressObserver(CoreOnlyConsumer::inspectProgress).clearProgressObserver().withProgressObserver(CoreOnlyConsumer::inspectProgress).build())
                .withSendIdGenerator(java.util.UUID::randomUUID)
                .withSendHandlersFactory(id -> MailSendHandlers.builder().withResultObserver(outcome -> {
                    if (!id.equals(outcome.getObservationId())) { throw new AssertionError("Send identity changed"); }
                }).build())
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
        final Long written = progress.getBytesWritten();
        final Long total = progress.getTotalBytes();
        if (written != null && total != null) {
            written.compareTo(total);
        }
    }

    private static String inspect(final MailSendDiagnostics report) {
        report.getConnectionHistory().forEach(step -> {
            final java.time.Duration elapsed = step.getElapsed();
            if (elapsed != null) {
                elapsed.toNanos();
            }
        });
        report.getSubmissionHistory().forEach(step -> step.getSubmissionStatus().name());
        final java.time.Duration elapsed = report.getPreparation().getElapsed();
        return elapsed != null ? elapsed.toString() : report.getPreparation().getUnavailableReason();
    }

    public static Email convert(final ConfiguredEmailConverter converter, final SimpleJavaMailConfig config) {
        config.getDiagnostics().getGroups().forEach(group -> config.getDiagnostics().getProperties(group));
        return converter.fromEml("From: sender@example.org\r\nTo: reader@example.org\r\n\r\nBody").toEmail();
    }
}
