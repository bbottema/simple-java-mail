import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the published HTML, not Lombok's compiled accessors or an older test-phase report. */
public final class PublicJavadocContractCheck {
    private final Path documentation;

    private PublicJavadocContractCheck(final Path documentation) {
        this.documentation = documentation;
    }

    public static void main(final String[] arguments) throws IOException {
        new PublicJavadocContractCheck(Path.of(arguments[0])).verifyPublishedContracts();
    }

    private void verifyPublishedContracts() throws IOException {
        requireText("../config/ConfigKey", "applications cannot construct");
        requireText("../config/ConfigKeys", "SESSION_TIMEOUT");
        requireText("../config/SimpleJavaMailConfig", "id=\"get(org.simplejavamail.config.ConfigKey)\"");
        requireText("../config/SimpleJavaMailConfig", "id=\"hasProperty(org.simplejavamail.config.ConfigKey)\"");
        requireText("../config/SimpleJavaMailConfig", "id=\"getPropertySource(org.simplejavamail.config.ConfigKey)\"");
        requireText("../config/SimpleJavaMailConfig", "id=\"valueOrProperty(java.lang.Object,org.simplejavamail.config.ConfigKey,java.lang.Object)\"");
        requireGetters("email/Recipient", "getName", "getAddress", "getType", "getSmimeCertificate",
                "getDeliveryStatusNotificationNotifyOptions");
        requireGetters("email/Recipients", "getRecipients", "getDefaultName", "getOverridingName");
        requireGetters("email/ContentTransferEncoding", "getEncoder");
        requireGetters("mailer/MailSendRejectedException", "getReason");
        requireGetters("../MailException", "getKind", "getSubmissionReceipt");
        requireGetters("mailer/MailSubmissionReceipt", "isAcceptedForAtLeastOneRecipient", "isFullyAcceptedForSubmission");
        requireShutdownExceptions();
        requireText("mailer/MailFailureKind", "CAPACITY");
        requireText("mailer/config/TransportStrategy", "SMTPS_OAUTH2");
        requireText("mailer/config/TransportStrategy", "mail.smtps.auth.mechanisms=XOAUTH2");
        requireText("mailer/MailerRegularBuilder", "SMTPS_OAUTH2");
        requireText("mailer/MailerGenericBuilder", "mail.smtps.auth.mechanisms=XOAUTH2");
        requireGetters("mailer/MailRecipientResult", "getOriginalAddress", "getDisposition");
        requireNullableScalars("mailer/MailSendOutcome", "getReadyAt", "getStartedAt");
        requireNullableScalars("mailer/MailSendDiagnostics", "getSmtpHost", "getSmtpPort");
        requireNullableScalars("mailer/MailSendMeasurement", "getElapsed", "getUnavailableReason");
        requireNullableScalars("mailer/MailSendConnectionStep", "getConfiguredName", "getSmtpHost", "getSmtpPort", "getElapsed", "getExplanation");
        requireNullableScalars("mailer/MailSendSubmissionStep", "getSmtpReplyCode", "getRetryDelay", "getExplanation");
        requireNullableScalars("mailer/MailSendProgress", "getBytesWritten", "getTotalBytes");
        requireNullableScalars("mailer/MailRecipientResult", "getEnvelopeAddress", "getRcptAttempted");
        requireNullableScalars("mailer/SmtpConnectionReport", "getGreeting", "getAuthenticationMechanism", "getFailurePhase", "getFailureDescription", "getPort");
        requireNullableScalars("mailer/SmtpCapabilities", "getMaximumMessageSize");
        requireNullableScalars("mailer/SmtpTlsDetails", "getCustomHostnameVerifierAccepted");
        requireGetters("mailer/SmtpTlsDetails", "getProtocol", "getCipherSuite", "getPeerIdentities", "isServerIdentityCheckEnabled");
        requireGetters("mailer/spi/DeliveryRecipient", "getAddress", "getNotifyOptions");
        requireGetters("mailer/config/OperationalConfig", "getSessionTimeout");
        requireGetters("email/IRecipientsBuilder", "resetSmimeCertificatePolicy", "resetDeliveryStatusNotificationNotifyPolicy");
        requireText("mailer/config/ConnectionConfig.Builder", "a caller-supplied Session");
        requireText("email/Recipient", "IRecipientBuilder.html#withName(java.lang.String)");
        requireText("email/Recipients", "IRecipientsBuilder.html#withRecipient(org.simplejavamail.api.email.Recipient)");
        requireLinkedCompanions("mailer/config/FailoverPolicy.Builder", "getPrimaryConnectionAttempts", "getPrimaryMaximumRecheckInterval",
                "resetPrimaryRecheckBackoff");
        requireLinkedCompanions("mailer/config/MailSendRetryPolicy.Builder", "getMaximumAttempts", "getRetryDelay", "getMemoryLimit");
        requireLinkedCompanions("email/config/DkimConfig.DkimConfigBuilder", "getDkimPrivateKeyData", "clearSigningAlgorithm",
                "clearExcludedHeadersFromDkimDefaultSigningList");
        requireLinkedCompanions("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "getPkcs12Config", "clearSignatureAlgorithm");
        requireLinkedCompanions("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "getX509Certificate", "clearCipherAlgorithm",
                "clearKeyEncapsulationAlgorithm");
        requireLinkedCompanions("email/config/DeliveryStatusNotification.DeliveryStatusNotificationBuilder", "getReturnOption", "getNotifyOptions",
                "getEnvelopeId", "clearNotifyOptions", "clearEnvelopeId");
        requireLinkedCompanions("mailer/config/Pkcs12Config.Pkcs12ConfigBuilder", "getPkcs12StoreData", "getStorePassword", "getKeyAlias", "getKeyPassword");
        requireLinkedCompanions("email/config/OpenPgpSigningConfig.OpenPgpSigningConfigBuilder", "getSecretKeyRing", "getPassphrase",
                "getSigningKeyId", "getHashAlgorithm");
        requireLinkedCompanions("email/config/OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder", "getRecipientPublicKeyRings",
                "clearRecipientPublicKeyRings", "getSymmetricAlgorithm");
        requireText("email/config/OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder", "rejected before changing any stored choice");
        requireText("email/config/OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder", "requires at least one key ring and rejects every");
        requireLinkedCompanions("email/config/OpenPgpReceiveConfig.OpenPgpReceiveConfigBuilder", "getVerificationKeyRings", "clearVerificationKeyRings",
                "getDecryptionKeyRings", "clearDecryptionKeyRings", "getPassphraseProvider", "clearPassphraseProvider");
        requireText("email/config/OpenPgpSigningConfig", "serialization retains the secret key ring");
        requireText("email/config/OpenPgpEncryptionConfig", "contains no secret keys or passphrase");
        requireText("email/Email", "OpenPGP secret key rings and passphrases");
        requireText("email/Email", "not protected again on restoration");
        requireText("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "SHA384withRSAandMGF1");
        requireText("email/config/SmimeSigningConfig.SmimeSigningConfigBuilder", "examples, not a whitelist");
        requireText("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "RSA_OAEP_SHA512");
        requireText("email/config/SmimeEncryptionConfig.SmimeEncryptionConfigBuilder", "DES_EDE3_CBC");
        requireText("email/config/DkimConfig.DkimConfigBuilder", "SHA256_WITH_ED25519");
        requireLinkedCompanions("mailer/MailerGenericBuilder", "getDebugConfig", "useConfiguredDebugConfig",
                "getTelemetryConfig", "useConfiguredTelemetryConfig", "getSendHandlers", "clearSendHandlers",
                "getSendHandlersFactory", "clearSendHandlersFactory", "getSendIdGenerator", "resetSendIdGenerator");
        requireLinkedCompanions("mailer/config/SessionDebugConfig.Builder", "getEnabled", "resetEnabled", "useConfiguredEnabled",
                "getOutput", "resetOutput", "useConfiguredOutput", "getPrinter", "clearPrinter");
        requireLinkedCompanions("mailer/MailerGenericBuilder", "getConnectionPoolConfig", "useConfiguredConnectionPoolConfig",
                "getAsyncExecutionConfig", "useConfiguredAsyncExecutionConfig", "getProxyConfig", "useConfiguredProxyConfig",
                "getSendingLimitsConfig", "useConfiguredSendingLimitsConfig", "getConnectionConfig", "useConfiguredConnectionConfig",
                "getTlsConfig", "useConfiguredTlsConfig", "getValidationConfig", "useConfiguredValidationConfig");
        requireLinkedCompanions("mailer/config/ConnectionPoolConfig.Builder", "getMaxSize", "resetMaxSize", "useConfiguredMaxSize");
        requireLinkedCompanions("mailer/config/AsyncExecutionConfig.Builder", "getQueueCapacity", "resetQueue", "getCapacityWait");
        requireLinkedCompanions("mailer/config/SocksProxyConfig.Builder", "getHost", "clearProxy", "useConfiguredHost");
        requireLinkedCompanions("mailer/config/SendingLimitsConfig.Builder", "getMessageRateLimit", "clearMessageRateLimit");
        requireLinkedCompanions("mailer/config/ConnectionConfig.Builder", "getSessionTimeout", "clearLocalBindAddress");
        requireLinkedCompanions("mailer/config/TlsConfig.Builder", "getTrustAllHosts", "clearCustomSSLFactoryClass");
        requireLinkedCompanions("mailer/config/ValidationConfig.Builder", "getEmailValidator", "clearEmailValidator", "resetEmailValidator");
        requireLinkedCompanions("email/config/ImageResolutionConfig.Builder", "getEmbeddedImageBaseDir", "clearEmbeddedImageBaseDir");
        requireLinkedCompanions("email/config/ContentEncodingConfig.Builder", "getContentTransferEncoding");
        requireLinkedCompanions("email/config/EmailTemplateOptions.Builder", "getPropertiesNotToApplyDefaultValueFor");
        requireGetters("email/config/SmtpDeliveryOptions", "getDeliveryStatusNotification");
        requireText("email/config/SmtpDeliveryOptions", "SmtpDeliveryOptions.Builder.html#withDeliveryStatusNotification");
        requireLinkedCompanions("email/config/SmtpDeliveryOptions.Builder", "getDeliveryStatusNotificationNotifyOptions",
                "getDeliveryStatusNotificationReturnOption", "getEnvelopeId", "clearEnvelopeId");
        if (read("email/config/SmtpDeliveryOptions.Builder").contains("id=\"getDeliveryStatusNotification()\"")) {
            throw new IllegalStateException("Unfinished SMTP choices must expose passive DSN components, not a validating aggregate getter (ADR 0031)");
        }
        requireLinkedCompanions("mailer/config/MailSendTelemetryConfig.Builder", "getEnabled", "resetEnabled", "useConfiguredEnabled",
                "getIntegration", "clearIntegration", "getSmtpEndpointAttributes", "resetSmtpEndpointAttributes", "useConfiguredSmtpEndpointAttributes");
        requireLinkedCompanions("mailer/MailSendHandlers.Builder", "getResultObserver", "getResultObserverExecutor", "clearResultObserver",
                "getProgressObserver", "getProgressObserverExecutor", "clearProgressObserver");
        requireText("mailer/MailSendException", "MailException.html#getSubmissionReceipt()");
        requireLinkedCompanions("mailer/MailerGenericBuilder", "getFailoverMailer", "getFailoverPolicy");
        if (read("email/Recipients").contains("id=\"&lt;init&gt;()\"")) {
            throw new IllegalStateException("Recipients Javadoc must not advertise a nonexistent no-argument constructor");
        }
        if (Files.exists(documentation.resolve("org/simplejavamail/api/email/OpenPgpDetails.html"))) {
            throw new IllegalStateException("OpenPGP result assembly must not appear in the application API");
        }
        if (read("email/Email").contains("&lt;init&gt;(")) {
            throw new IllegalStateException("Email Javadoc must direct applications to buildEmail(), not internal construction (ADR 0031)");
        }
        if (Files.exists(documentation.resolve("org/simplejavamail/api/email/EmailWithDefaultsAndOverridesApplied.html"))
                || read("email/EmailPopulatingBuilder").contains("EmailWithDefaultsAndOverridesApplied")) {
            throw new IllegalStateException("Internal defaults/overrides bookkeeping must not appear in the application API (ADR 0031)");
        }
    }

    private void requireGetters(final String type, final String... methods) throws IOException {
        final String html = read(type);
        for (final String method : methods) {
            final String anchor = "id=\"" + method + "()\"";
            final int start = html.indexOf(anchor);
            if (start < 0) {
                throw new IllegalStateException(type + " is missing published getter " + method + " (ADR 0031)");
            }
            // JDK 11 uses anchor/list markup; newer JDKs use sections. Both publish the same method IDs.
            final Matcher nextMethod = Pattern.compile("id=\"[^\"]+\\([^\"]*\\)\"").matcher(html);
            final int end = nextMethod.find(start + anchor.length()) ? nextMethod.start() : html.length();
            if (!html.substring(start, end).contains("class=\"block\"")) {
                throw new IllegalStateException(type + "#" + method + " needs a documented getter contract (ADR 0031)");
            }
        }
    }

    private void requireText(final String type, final String text) throws IOException {
        if (!read(type).contains(text)) {
            throw new IllegalStateException(type + " is missing documented guidance or its source-of-truth link: " + text);
        }
    }

    private void requireNullableScalars(final String type, final String... methods) throws IOException {
        final String html = read(type);
        for (final String method : methods) {
            final int start = html.indexOf("id=\"" + method + "()\"");
            if (start < 0) {
                throw new IllegalStateException(type + " is missing published getter " + method + " (ADR 0009)");
            }
            final Matcher nextMethod = Pattern.compile("id=\"[^\"]+\\([^\"]*\\)\"").matcher(html);
            final int end = nextMethod.find(start + 4) ? nextMethod.start() : html.length();
            final String methodBody = html.substring(start, end);
            final Matcher signature = Pattern.compile("(?s)<(?:pre|div) class=\"(?:methodSignature|member-signature)\"[^>]*>(.*?)</(?:pre|div)>")
                    .matcher(methodBody);
            if (!signature.find() || !signature.group(1).contains("@Nullable")
                    || Pattern.compile("\\bOptional(?:Long)?\\b").matcher(signature.group(1).replaceAll("<[^>]+>", "")).find()
                    || !methodBody.contains("class=\"block\"") && !methodBody.contains("Returns:")) {
                throw new IllegalStateException(type + "#" + method + " must publish the nullable scalar contract (ADR 0009)");
            }
        }
    }

    private void requireShutdownExceptions() throws IOException {
        final String html = read("mailer/Mailer");
        final int start = html.indexOf("id=\"close()\"");
        if (start < 0) {
            throw new IllegalStateException("Mailer must publish its close contract");
        }
        final Matcher signature = Pattern.compile("(?s)<(?:pre|div) class=\"(?:methodSignature|member-signature)\"[^>]*>(.*?)</(?:pre|div)>")
                .matcher(html.substring(start));
        if (!signature.find() || !signature.group(1).replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replaceAll("\\s+", " ")
                .matches(".*\\bclose\\(\\) throws ExecutionException, InterruptedException.*")) {
            throw new IllegalStateException("Mailer.close must publish only ExecutionException and InterruptedException (ADR 0016)");
        }
    }

    private void requireLinkedCompanions(final String type, final String... methods) throws IOException {
        final String html = read(type);
        for (final String method : methods) {
            final String anchor = "id=\"" + method + "()\"";
            final int start = html.indexOf(anchor);
            if (start < 0) {
                throw new IllegalStateException(type + " is missing published companion " + method + " (ADR 0031)");
            }
            final Matcher nextMethod = Pattern.compile("id=\"[^\"]+\\([^\"]*\\)\"").matcher(html);
            final int end = nextMethod.find(start + anchor.length()) ? nextMethod.start() : html.length();
            if (!html.substring(start, end).contains("href=\"#")) {
                throw new IllegalStateException(type + "#" + method + " needs a link to its setting contract (ADR 0031)");
            }
        }
    }

    private String read(final String type) throws IOException {
        return Files.readString(documentation.resolve("org/simplejavamail/api/" + type + ".html"));
    }
}
