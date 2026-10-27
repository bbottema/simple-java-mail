package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import testutil.configcontracts.ConfigurationContractCoverage;

import javax.net.ssl.SSLSocketFactory;
import java.io.PrintStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Copy routes carry prior supplied state into new drafts, including explicit removal and borrowed identity. */
class ConfigurationCopyRouteContractTest {
    @Test
    void resourceAndEncodingCopiesRetainSourcesAndIndependentMetadata() throws Exception {
        final DataSource source = mock(DataSource.class);
        final AttachmentResource original = AttachmentResource.builder().withName("original.txt").withDescription("description")
                .withContentId("resource-id").withDataSource(source).withContentTransferEncoding(ContentTransferEncoding.BASE_64).build();
        final AttachmentResource copied = copy(original, AttachmentResource.Builder.class).withName("copy.txt").build();
        assertThat(copied.getDataSource()).isSameAs(source);
        assertThat(copied.getContentId()).isEqualTo("resource-id");
        assertThat(copied.getDescription()).isEqualTo("description");
        assertThat(copied.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(copied.getName()).isEqualTo("copy.txt");
        assertThat(original.getName()).isEqualTo("original.txt");
        verify(source, never()).getInputStream();

        final ContentEncodingConfig encoding = ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BIT8)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64).build();
        final ContentEncodingConfig edited = copy(encoding, ContentEncodingConfig.Builder.class)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build();
        assertThat(edited.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT8);
        assertThat(edited.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        assertThat(edited.getHTMLTextContentTransferEncoding()).isNull();
        assertThat(encoding.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        copiedCase("resource-and-encoding");
    }

    @Test
    void emailOptionCopiesRetainSuppressionAbsenceAndEnvelopeChoices() throws Exception {
        final DeliveryStatusNotification notification = DeliveryStatusNotification.builder().returnOption(HEADERS_ONLY)
                .notifyOptions(FAILURE, DELAY).envelopeId("original-id").build();
        final DeliveryStatusNotification copied = copy(notification, DeliveryStatusNotification.DeliveryStatusNotificationBuilder.class)
                .notifyOptions(NEVER).envelopeId("copy-id").build();
        assertThat(copied.getReturnOption()).isEqualTo(HEADERS_ONLY);
        assertThat(copied.getNotifyOptions()).containsExactly(NEVER);
        assertThat(copied.getEnvelopeId()).isEqualTo("copy-id");
        assertThat(notification.getNotifyOptions()).containsExactlyInAnyOrder(FAILURE, DELAY);
        assertThat(notification.getEnvelopeId()).isEqualTo("original-id");

        final EmailTemplateOptions template = EmailTemplateOptions.builder().ignoringDefaults()
                .dontApplyOverrideValueFor(EmailProperty.SUBJECT, EmailProperty.BODY_TEXT).build();
        final EmailTemplateOptions allowed = copy(template, EmailTemplateOptions.Builder.class)
                .allowOverrideValueFor(EmailProperty.SUBJECT).build();
        assertThat(allowed.isIgnoreDefaults()).isTrue();
        assertThat(allowed.getPropertiesNotToApplyOverrideValueFor()).containsExactly(EmailProperty.BODY_TEXT);
        assertThat(template.getPropertiesNotToApplyOverrideValueFor()).containsExactlyInAnyOrder(EmailProperty.SUBJECT, EmailProperty.BODY_TEXT);

        final ImageResolutionConfig image = ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(false)
                .clearEmbeddedImageBaseDir().build();
        final ImageResolutionConfig imageCopy = copy(image, ImageResolutionConfig.Builder.class).withEmbeddedImageBaseDir("images").build();
        assertThat(imageCopy.getEmbeddedImageAutoResolutionForFiles()).isFalse();
        assertThat(imageCopy.getEmbeddedImageAutoResolutionForURLs()).isNull();
        assertThat(imageCopy.getEmbeddedImageBaseDir()).isEqualTo("images");
        assertThat(image.isEmbeddedImageBaseDirConfigured()).isTrue();
        assertThat(image.getEmbeddedImageBaseDir()).isNull();
        assertThat(copy(image, ImageResolutionConfig.Builder.class).build().isEmbeddedImageBaseDirConfigured()).isTrue();

        final SmtpDeliveryOptions delivery = SmtpDeliveryOptions.builder().withDeliveryStatusNotification(notification)
                .withSendingToAcceptedRecipients(false).withTlsRequiredForOnwardDelivery().build();
        final SmtpDeliveryOptions deliveryCopy = copy(delivery, SmtpDeliveryOptions.Builder.class).fixingEnvelopeId("changed-id").build();
        assertThat(deliveryCopy.getSendingToAcceptedRecipients()).isFalse();
        assertThat(deliveryCopy.isTlsRequiredForOnwardDelivery()).isTrue();
        assertThat(deliveryCopy.getDeliveryStatusNotification().getNotifyOptions()).containsExactlyInAnyOrder(FAILURE, DELAY);
        assertThat(deliveryCopy.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("changed-id");
        assertThat(delivery.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("original-id");
        copiedCase("email-options");
    }

    @Test
    void connectionCopiesKeepExplicitRemovalFlagsAndBorrowedFactories() throws Exception {
        final ConnectionConfig connection = ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).clearLocalBindAddress()
                .withLocalBindPort(456).build();
        final ConnectionConfig connectionCopy = copy(connection, ConnectionConfig.Builder.class).withLocalBindAddress("127.0.0.1").build();
        assertThat(connectionCopy.getReadTimeout()).isEqualTo(Duration.ofMillis(123));
        assertThat(connectionCopy.getConnectTimeout()).isNull();
        assertThat(connectionCopy.getLocalBindPort()).isEqualTo(456);
        assertThat(connectionCopy.isLocalBindAddressCleared()).isFalse();
        assertThat(connection.isLocalBindAddressCleared()).isTrue();
        assertThat(copy(connection, ConnectionConfig.Builder.class).build().isLocalBindAddressCleared()).isTrue();

        final SocksProxyConfig proxy = SocksProxyConfig.builder().withHost("proxy.example.test").withUsername("user")
                .clearPort().clearPassword().build();
        final SocksProxyConfig proxyCopy = copy(proxy, SocksProxyConfig.Builder.class).withPort(1081).build();
        assertThat(proxyCopy.getHost()).isEqualTo("proxy.example.test");
        assertThat(proxyCopy.getUsername()).isEqualTo("user");
        assertThat(proxyCopy.isPasswordCleared()).isTrue();
        assertThat(proxyCopy.isPortCleared()).isFalse();
        assertThat(proxy.isPortCleared()).isTrue();

        final SSLSocketFactory factory = mock(SSLSocketFactory.class);
        final TlsConfig tls = TlsConfig.builder().trustingAllHosts(false).trustingSSLHosts("first.example.test")
                .withCustomSSLFactoryInstance(factory).clearCustomSSLFactoryClass().build();
        final TlsConfig tlsCopy = copy(tls, TlsConfig.Builder.class).trustingSSLHosts("second.example.test").build();
        assertThat(tlsCopy.getCustomSSLFactoryInstance()).isSameAs(factory);
        assertThat(tlsCopy.isCustomSSLFactoryClassCleared()).isTrue();
        assertThat(tlsCopy.getTrustAllHosts()).isFalse();
        assertThat(tlsCopy.getSslHostsToTrust()).containsExactly("second.example.test");
        assertThat(tls.getSslHostsToTrust()).containsExactly("first.example.test");
        verifyNoInteractions(factory);
        copiedCase("connection-options");
    }

    @Test
    void operationalCopiesKeepDisabledChoicesAndCallerOwnedExecutors() throws Exception {
        final UUID cluster = UUID.randomUUID();
        final ConnectionPoolConfig pool = ConnectionPoolConfig.builder().withCoreSize(2).withMaxSize(4)
                .withClusterKey(cluster).clearExpireAfterCreation().build();
        final ConnectionPoolConfig poolCopy = copy(pool, ConnectionPoolConfig.Builder.class).withExpireAfterCreation(Duration.ofSeconds(1)).build();
        assertThat(poolCopy.getCoreSize()).isEqualTo(2);
        assertThat(poolCopy.getMaxSize()).isEqualTo(4);
        assertThat(poolCopy.getClusterKey()).isEqualTo(cluster);
        assertThat(poolCopy.isExpireAfterCreationCleared()).isFalse();
        assertThat(pool.isExpireAfterCreationCleared()).isTrue();
        assertThat(copy(pool, ConnectionPoolConfig.Builder.class).build().isExpireAfterCreationCleared()).isTrue();

        final ExecutorService executor = mock(ExecutorService.class);
        final AsyncExecutionConfig execution = AsyncExecutionConfig.builder().withThreadPoolSize(4).withExecutorService(executor).build();
        final AsyncExecutionConfig.Builder executionCopy = copy(execution, AsyncExecutionConfig.Builder.class);
        assertThat(executionCopy.getExecutorService()).isSameAs(executor);
        final AsyncExecutionConfig cleared = executionCopy.clearExecutorService().build();
        assertThat(cleared.getThreadPoolSize()).isEqualTo(4);
        assertThat(cleared.isExecutorServiceCleared()).isTrue();
        assertThat(copy(cleared, AsyncExecutionConfig.Builder.class).build().isExecutorServiceCleared()).isTrue();
        assertThat(execution.getExecutorService()).isSameAs(executor);
        verifyNoInteractions(executor);

        final SendingLimitsConfig limits = SendingLimitsConfig.builder().clearMessageRateLimit()
                .withRecipientRateLimit(7, Duration.ofMinutes(1)).clearRateLimitGroup().withRateLimitBurstsAllowed(false).build();
        final SendingLimitsConfig limitsCopy = copy(limits, SendingLimitsConfig.Builder.class).withMessageRateLimit(9, Duration.ofMinutes(1)).build();
        assertThat(limitsCopy.getRecipientRateLimit().getCount()).isEqualTo(7);
        assertThat(limitsCopy.isRateLimitGroupCleared()).isTrue();
        assertThat(limitsCopy.getRateLimitBurstsAllowed()).isFalse();
        assertThat(limitsCopy.isMessageRateLimitCleared()).isFalse();
        assertThat(limits.isMessageRateLimitCleared()).isTrue();
        copiedCase("operational-options");
    }

    @Test
    void callbackCopiesKeepBorrowedIdentitiesAndDoNotInvokeThem() throws Exception {
        final PrintStream printer = mock(PrintStream.class);
        final SessionDebugConfig debug = SessionDebugConfig.builder().withEnabled(false).withPrinter(printer).build();
        final SessionDebugConfig.Builder debugCopy = copy(debug, SessionDebugConfig.Builder.class);
        assertThat(debugCopy.getPrinter()).isSameAs(printer);
        assertThat(debugCopy.withOutput(SessionDebugOutput.STDERR).build().getEnabled()).isFalse();
        assertThat(debug.getPrinter()).isSameAs(printer);

        final MailSendTelemetry integration = mock(MailSendTelemetry.class);
        final MailSendTelemetryConfig telemetry = MailSendTelemetryConfig.builder().withEnabled(false)
                .withSmtpEndpointAttributes(true).withIntegration(integration).build();
        final MailSendTelemetryConfig.Builder telemetryCopy = copy(telemetry, MailSendTelemetryConfig.Builder.class);
        assertThat(telemetryCopy.getIntegration()).isSameAs(integration);
        final MailSendTelemetryConfig removed = telemetryCopy.clearIntegration().build();
        assertThat(removed.getEnabled()).isFalse();
        assertThat(removed.getSmtpEndpointAttributes()).isTrue();
        assertThat(telemetry.getIntegration()).isSameAs(integration);

        final EmailValidator validator = mock(EmailValidator.class);
        final ValidationConfig validation = ValidationConfig.builder().withEmailValidator(validator).withMaximumEmailSize(123)
                .withClientValidationWarningsOnly(true).build();
        final ValidationConfig.Builder validationCopy = copy(validation, ValidationConfig.Builder.class);
        assertThat(validationCopy.getEmailValidator()).isSameAs(validator);
        final ValidationConfig cleared = validationCopy.clearEmailValidator().build();
        assertThat(cleared.isEmailValidatorCleared()).isTrue();
        assertThat(cleared.getMaximumEmailSize()).isEqualTo(123);
        assertThat(cleared.getClientValidationWarningsOnly()).isTrue();
        assertThat(validation.getEmailValidator()).isSameAs(validator);

        final MailSendResultObserver result = mock(MailSendResultObserver.class);
        final MailSendProgressObserver progress = mock(MailSendProgressObserver.class);
        final Executor executor = mock(Executor.class);
        final MailSendHandlers handlers = MailSendHandlers.builder().withResultObserver(result, executor).withProgressObserver(progress, executor).build();
        final MailSendHandlers.Builder handlersCopy = copy(handlers, MailSendHandlers.Builder.class);
        assertThat(handlersCopy.getResultObserver()).isSameAs(result);
        assertThat(handlersCopy.getResultObserverExecutor()).isSameAs(executor);
        final MailSendHandlers removedResult = handlersCopy.clearResultObserver().build();
        assertThat(removedResult.getResultObserver()).isNull();
        assertThat(removedResult.getProgressObserver()).isSameAs(progress);
        assertThat(removedResult.getProgressObserverExecutor()).isSameAs(executor);
        assertThat(handlers.getResultObserver()).isSameAs(result);
        verifyNoInteractions(printer, integration, validator, result, progress, executor);
        copiedCase("borrowed-callbacks");
    }

    @Test
    void retryAndFailoverCopiesRetainTheirDifferentClearAndDefaultContracts() throws Exception {
        final MailSendRetryPolicy retry = MailSendRetryPolicy.builder().clearRetrySchedule().withWaitingCapacity(13)
                .withMaximumMessageSize(77).withMemoryLimit(99).build();
        final MailSendRetryPolicy.Builder retryCopy = copy(retry, MailSendRetryPolicy.Builder.class);
        assertThat(retryCopy.build().isMaximumAttemptsCleared()).isTrue();
        assertThat(retryCopy.build().isRetryDelayCleared()).isTrue();
        final MailSendRetryPolicy enabled = retryCopy.withMaximumAttempts(3).withRetryDelay(Duration.ofMillis(33)).build();
        assertThat(enabled.isMaximumAttemptsCleared()).isFalse();
        assertThat(enabled.getWaitingCapacity()).isEqualTo(13);
        assertThat(enabled.getMaximumMessageSize()).isEqualTo(77);
        assertThat(enabled.getMemoryLimit()).isEqualTo(99);
        assertThat(retry.isRetryDelayCleared()).isTrue();

        final FailoverPolicy failover = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
                .withPrimaryConnectionRetryDelay(Duration.ofMillis(223)).withFailoverOnAuthenticationFailure(true).build();
        final FailoverPolicy changed = copy(failover, FailoverPolicy.Builder.class).withPrimaryConnectionAttempts(5).build();
        assertThat(changed.getPrimaryConnectionAttempts()).isEqualTo(5);
        assertThat(changed.getPrimaryConnectionRetryDelay()).isEqualTo(Duration.ofMillis(223));
        assertThat(changed.isFailoverOnAuthenticationFailure()).isTrue();
        assertThat(failover.getPrimaryConnectionAttempts()).isEqualTo(3);
        copiedCase("retry-failover");
    }

    private static <T> T copy(final Object value, final Class<T> builderType) throws Exception {
        final Object draft = ConfigurationContractCoverage.invoke(value, value.getClass().getMethod("toBuilder"));
        assertThat(draft).isInstanceOf(builderType);
        return builderType.cast(draft);
    }

    private static void copiedCase(final String category) {
        record("rule", "configuration.copy.state-and-identity");
        record("case", "configuration.copy." + category);
    }
}
