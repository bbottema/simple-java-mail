package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliGroupedConfigurationOptionsTest {

    @TempDir Path workingDirectory;

    @Test
    void groupedJavaSettingsRetainUniqueScalarCliOptionsOnInternalAdapters() {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotContain(
                "--mailer:withConnectionPoolConfig", "--mailer:withAsyncExecutionConfig", "--mailer:withProxyConfig",
                "--mailer:withSendingLimitsConfig", "--mailer:withConnectionConfig", "--mailer:withTlsConfig", "--mailer:withValidationConfig",
                "--email:withImageResolutionConfig", "--email:withContentEncodingConfig", "--email:withTemplateOptions",
                "--email:withSmtpDeliveryOptions", "--mailer:useConfiguredConnectionPoolConfig", "--mailer:useConfiguredAsyncExecutionConfig",
                "--mailer:useConfiguredProxyConfig", "--mailer:useConfiguredSendingLimitsConfig", "--mailer:useConfiguredConnectionConfig",
                "--mailer:useConfiguredTlsConfig", "--mailer:useConfiguredValidationConfig", "--email:useConfiguredImageResolutionConfig",
                "--email:useConfiguredContentEncodingConfig", "--email:useConfiguredTemplateOptions", "--email:useConfiguredSmtpDeliveryOptions",
                "--email:signWithDomainKey");
        final Map<String, List<String>> adapterOptions = Map.of(
                "CliMailerConfigurationBuilder", List.of("--mailer:withConnectionPoolMaxSize", "--mailer:withThreadPoolSize",
                        "--mailer:withAsyncQueueOverflowPolicy", "--mailer:withAsyncQueueWaitTimeoutMillis", "--mailer:withProxy",
                        "--mailer:withMessageRateLimit", "--mailer:withSessionTimeout",
                        "--mailer:withMaximumEmailSize"),
                "CliRegularMailerConfigurationBuilder", List.of("--mailer:withOpportunisticTLS", "--mailer:clearCustomSSLFactoryClass"),
                "CliEmailConfigurationBuilder", List.of("--email:withEmbeddedImageAutoResolutionForFiles",
                        "--email:withContentTransferEncoding", "--email:ignoringDefaultsYesNo", "--email:fixingEnvelopeId",
                        "--email:signWithSmime", "--email:encryptWithSmime"),
                "CliExactEmailConfigurationBuilder", List.of("--email:withEnvelopeDsnNotifyOptions",
                        "--email:withEnvelopeDsnReturnOption", "--email:fixingExactEnvelopeId"));
        adapterOptions.forEach((adapterName, names) -> names.forEach(name ->
                assertThat(options).filteredOn(option -> option.getName().equals(name)).singleElement().satisfies(option -> {
                    assertThat(option.getSourceMethod().getDeclaringClass().getName())
                            .isEqualTo("org.simplejavamail.api.internal.clisupport." + adapterName);
                    assertThat(option.getDescription()).anyMatch(description -> !description.isBlank());
                    assertThat(option.getPossibleOptionValues()).hasSize(option.getSourceMethod().getParameterCount());
                })));
    }

    @Test
    void scalarMailerOptionsStillConfigureEveryMovedGroupDuringACommand() {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank",
                    "--email:from", "sender@example.test", "--email:to", "receiver@example.test",
                    "--mailer:withTransportModeLoggingOnly", "true", "--mailer:withConnectionPoolMaxSize", "7",
                    "--mailer:withThreadPoolSize", "2", "--mailer:withAsyncQueueCapacity", "12",
                    "--mailer:withAsyncQueueOverflowPolicy", "WAIT_FOR_CAPACITY", "--mailer:withAsyncQueueWaitTimeoutMillis", "250",
                    "--mailer:withProxy", "proxy.example.test", "1081", "--mailer:withMessageRateLimit", "5", "PT1S",
                    "--mailer:withSessionTimeout", "1200", "--mailer:withSmtpClientHostname", "client.example.test",
                    "--mailer:withOpportunisticTLS", "false", "--mailer:withMaximumEmailSize", "2048");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer).isNotNull();
            assertThat(mailer.getOperationalConfig().getConnectionPoolMaxSize()).isEqualTo(7);
            assertThat(mailer.getOperationalConfig().getThreadPoolSize()).isEqualTo(2);
            assertThat(mailer.getOperationalConfig().getAsyncQueueConfig().getCapacity()).isEqualTo(12);
            assertThat(mailer.getOperationalConfig().getAsyncQueueConfig().getOverflowPolicy()).isEqualTo(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY);
            assertThat(mailer.getOperationalConfig().getAsyncQueueConfig().getWaitTimeout()).isEqualTo(Duration.ofMillis(250));
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example.test");
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1081);
            assertThat(mailer.getOperationalConfig().getMessageRateLimit().getCount()).isEqualTo(5);
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1200));
            assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isEqualTo("client.example.test");
            assertThat(mailer.getSession().getProperty("mail.smtp.starttls.enable")).isNull();
            assertThat(mailer.getEmailGovernance().getMaximumEmailSize()).isEqualTo(2048);
        }
    }

    @Test
    void composedEmailScalarOptionsStillConfigureTheMessagePassedToSend() {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank",
                    "--email:from", "sender@example.test", "--email:to", "receiver@example.test",
                    "--email:withEmbeddedImageAutoResolutionForFiles", "false",
                    "--email:withEmbeddedImageBaseDir", "unused-image-root", "--email:clearEmbeddedImageBaseDir",
                    "--email:withContentTransferEncoding", "BASE_64", "--email:ignoringDefaultsYesNo", "true",
                    "--email:withDeliveryStatusNotificationNotifyOptions", "failure", "--email:fixingEnvelopeId", "invoice-42",
                    "--email:withSendingToAcceptedRecipients", "true");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            assertThat(email.getValue().isIgnoreDefaults()).isTrue();
            assertThat(email.getValue().getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
            assertThat(email.getValue().getDeliveryStatusNotification().getNotifyOptions()).containsExactly(NotifyOption.FAILURE);
            assertThat(email.getValue().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("invoice-42");
            assertThat(email.getValue().getSendingToAcceptedRecipients()).isTrue();
            verify(lease).close();
        }
    }

    @Test
    void scalarSigningOptionReadsTheRequestRelativeStoreAndRetainsSigningChoices() throws Exception {
        // Key-store bytes are opaque until MIME signing; this test checks CLI configuration and request-relative file ownership.
        final byte[] storedBytes = {1, 2, 3};
        Files.write(workingDirectory.resolve("signing-store.p12"), storedBytes);
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
            final String[] arguments = {"send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "receiver@example.test", "--email:signWithSmime", "signing-store.p12",
                    "store-password", "signing-key", "key-password", "SHA256withRSA"};
            final CliExecutionResult result = CliSupport.execute(arguments, workingDirectory, UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            assertThat(email.getValue().getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData()).containsExactly(storedBytes);
            assertThat(email.getValue().getSmimeSigningConfig().getPkcs12Config().getKeyAlias()).isEqualTo("signing-key");
            assertThat(email.getValue().getSmimeSigningConfig().getSignatureAlgorithm()).isEqualTo("SHA256withRSA");
            verify(lease).close();
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
