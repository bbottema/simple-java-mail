package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.smtp.SMTPSSLTransport;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.internal.clisupport.CliMailerConfigurationBuilder;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.mailprovider.angus.ManagedAngusTransport;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplejavamail.internal.util.FinalizedMimeMessage.ProtectionState.FINAL_WIRE_SIGNED;

class CliPreparationContractTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void governedSecurityPreparationRunsOnceWithoutSendingResourcesOrAuthentication() throws Exception {
        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger signingCalls = new AtomicInteger();
        final AtomicReference<MimeMessage> signed = new AtomicReference<>();
        final DKIMModule dkim = signingBoundary(signingCalls, signed);
        final BatchModule batch = mock(BatchModule.class);
        final AuthenticatedSocksModule socks = mock(AuthenticatedSocksModule.class);
        final Email defaults = MAIL.emailBuilder().startingBlank().from("defaults@example.org")
                .withSubject("Default subject").signWithDomainKey(dkim()).buildEmail();
        final Email overrides = MAIL.emailBuilder().startingBlank().withSubject("Forced subject").buildEmail();
        final Email draft = MAIL.emailBuilder().startingBlank().to("reader@example.org").withPlainText("Body").buildEmail();
        final MailerGenericBuilder<?> builder = MAIL.mailerBuilder().withSMTPServer("smtp.example.test", 587, "smtp-user", null)
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2)
                .withOAuth2AccessTokenProvider(() -> { tokenCalls.incrementAndGet(); return "unused-token"; })
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(2).build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("proxy.example.test").withPort(1080)
                        .withUsername("proxy-user").withPassword("proxy-password").build())
                .withEmailDefaults(defaults).withEmailOverrides(overrides);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
             MockedConstruction<MailSendExecutor> workers = mockConstruction(MailSendExecutor.class);
             MockedConstruction<SMTPTransport> smtp = mockConstruction(SMTPTransport.class);
             MockedConstruction<SMTPSSLTransport> smtps = mockConstruction(SMTPSSLTransport.class);
             MockedConstruction<ManagedAngusTransport> managed = mockConstruction(ManagedAngusTransport.class)) {
            modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
            modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(socks);
            validate(builder, draft);
            assertThat(workers.constructed()).isEmpty();
            assertThat(smtp.constructed()).isEmpty();
            assertThat(smtps.constructed()).isEmpty();
            assertThat(managed.constructed()).isEmpty();
            verify(batch, never()).registerToCluster(any(), any(), any(), any(), any(), any());
            verify(socks, never()).createAnonymousSocks5Server(any());
        }
        assertThat(tokenCalls).hasValue(0);
        assertThat(signingCalls).hasValue(1);
        assertThat(signed.get().getSubject()).isEqualTo("Forced subject");
        assertThat(signed.get().getFrom()[0].toString()).isEqualTo("defaults@example.org");
        assertThat(draft.getFromRecipient()).isNull();
        assertThat(draft.getSubject()).isNull();
        assertThat(draft.getId()).isNull();
    }

    @ParameterizedTest
    @MethodSource("mimeChoices")
    void preparationKeepsOwnedEncodingDefaultsAndSuppliedPropertyVisibility(final boolean supplied, final Boolean utf8,
            final boolean inherited) throws Exception {
        final Properties defaults = new Properties();
        if (utf8 != null && inherited) { defaults.setProperty("mail.mime.allowutf8", utf8.toString()); }
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        if (utf8 != null && !inherited) { properties.put("mail.mime.allowutf8", utf8); }
        final Session original = Session.getInstance(properties);
        final Properties before = (Properties) properties.clone();
        final MailerGenericBuilder<?> builder = supplied ? MAIL.mailerBuilder(original)
                : MAIL.mailerBuilder().withSMTPServer("smtp.example.test", 25).withProperties(properties);
        final AtomicReference<MimeMessage> signed = new AtomicReference<>();
        final DKIMModule dkim = signingBoundary(new AtomicInteger(), signed);
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Body").signWithDomainKey(dkim()).buildEmail();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
            validate(builder, email);
        }
        final Session mime = signed.get().getSession();
        if (utf8 == null) {
            assertThat(mime.getProperty("mail.mime.allowutf8")).isEqualTo(supplied ? null : "true");
        } else if (inherited) {
            assertThat(mime.getProperty("mail.mime.allowutf8")).isEqualTo(utf8.toString());
            assertThat(mime.getProperties().containsKey("mail.mime.allowutf8")).isFalse();
        } else {
            assertThat(mime.getProperties().get("mail.mime.allowutf8")).isSameAs(utf8);
        }
        assertThat(mime.getProperties().containsKey(MailerImpl.SESSION_OWNER)).isFalse();
        assertThat(mime.getProperties().containsKey(SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY)).isFalse();
        assertThat(mime.getDebug()).isFalse();
        assertThat(properties).isEqualTo(before);
    }

    private static Stream<Arguments> mimeChoices() {
        return Stream.of(Arguments.of(false, null, false), Arguments.of(false, false, false), Arguments.of(false, true, false),
                Arguments.of(true, null, false), Arguments.of(true, false, false), Arguments.of(true, false, true),
                Arguments.of(true, true, true));
    }

    @Test
    void preparationDoesNotReserveOrEstablishPrimaryOrChildAllowanceRules() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "backup", "simplejavamail.mailers.backup.smtp.host", "backup.example.test",
                "simplejavamail.mailers.backup.smtp.ratelimit.group", "child", "simplejavamail.mailers.backup.smtp.ratelimit.messages.limit", 1,
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.period", "PT1M")).load());
        final MailerGenericBuilder<?> builder = mail.mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withSendingLimitsConfig(SendingLimitsConfig.builder().withRateLimitGroup("primary")
                        .withMessageRateLimit(1, Duration.ofMinutes(1)).build());
        final FactorySendingLimits registry = ((MailerGenericBuilderImpl<?>) builder).getFactorySendingLimits();
        final DKIMModule dkim = mock(DKIMModule.class);
        final AtomicInteger checks = new AtomicInteger();
        when(dkim.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
                .thenAnswer(invocation -> {
                    try (FactorySendingLimits.Reservation primary = registry.reserve(null, "primary", new SendingRateLimit(2, Duration.ofMinutes(1)), null, false);
                         FactorySendingLimits.Reservation child = registry.reserve(null, "child", new SendingRateLimit(2, Duration.ofMinutes(1)), null, false)) {
                        checks.incrementAndGet();
                    }
                    return FinalizedMimeMessage.finalizeMessage(invocation.getArgument(1), FINAL_WIRE_SIGNED);
                });
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Body").signWithDomainKey(dkim()).buildEmail();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
            validate(builder, email);
        }
        assertThat(checks).hasValue(1);
        try (FactorySendingLimits.Reservation primary = registry.reserve(null, "primary", new SendingRateLimit(3, Duration.ofMinutes(1)), null, false);
             FactorySendingLimits.Reservation child = registry.reserve(null, "child", new SendingRateLimit(3, Duration.ofMinutes(1)), null, false)) {
            // Neither the primary nor its privately configured child acquired or established sending rules.
        }
    }

    @Test
    void fullPreparationRetainsSizeAndExactEmlBoundaries() {
        final byte[] source = "Subject: Exact\r\n\r\nAuthoritative body\r\n".getBytes(StandardCharsets.US_ASCII);
        final Email exact = MAIL.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();
        validate(builder().withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(source.length).build()), exact);
        assertThatThrownBy(() -> validate(builder().withValidationConfig(ValidationConfig.builder()
                .withMaximumEmailSize(source.length - 1).build()), exact)).isInstanceOf(EmailTooBigException.class);
        assertThat(source).containsExactly("Subject: Exact\r\n\r\nAuthoritative body\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(exact.getId()).isNull();
    }

    @Test
    void purePoolConstraintsStillApplyWithoutActivatingThePool() {
        try (MockedConstruction<MailSendExecutor> workers = mockConstruction(MailSendExecutor.class)) {
            assertThatThrownBy(() -> validate(builder().withConnectionPoolConfig(ConnectionPoolConfig.builder()
                    .withCoreSize(2).withMaxSize(1).build()), validEmail())).isInstanceOf(IllegalArgumentException.class);
            assertThat(workers.constructed()).isEmpty();
        }
    }

    @Test
    void absentBatchSupportKeepsItsUnusedPoolSettingsInactive() {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            modules.when(ModuleLoader::loadBatchModule).thenThrow(new AssertionError("Unavailable batch support was requested"));
            validate(builder().withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(2).withMaxSize(1).build()), validEmail());
        }
    }

    @Test
    void acquiredContentStreamsAreClosedAndOriginalFailuresRemainVisible() {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final DataSource readable = new DataSource() {
            @Override public InputStream getInputStream() {
                opened.incrementAndGet();
                return new FilterInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})) {
                    private boolean finished;
                    @Override public void close() throws IOException {
                        if (!finished) { finished = true; closed.incrementAndGet(); }
                        super.close();
                    }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "content.bin"; }
        };
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Body").withAttachment("content.bin", readable).buildEmail();
        validate(builder(), email);
        assertThat(opened.get()).isPositive();
        assertThat(closed).hasValue(opened.get());
        final IOException unavailable = new IOException("content unavailable");
        final DataSource failing = new DataSource() {
            @Override public InputStream getInputStream() throws IOException { throw unavailable; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "missing.bin"; }
        };
        final Email unreadable = MAIL.emailBuilder().copying(email).clearAttachments().withAttachment("missing.bin", failing).buildEmail();
        assertThatThrownBy(() -> validate(builder(), unreadable)).hasRootCause(unavailable);
    }

    private static MailerGenericBuilder<?> builder() {
        return MAIL.mailerBuilder().withSMTPServer("smtp.example.test", 25);
    }

    private static Email validEmail() {
        return MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").withPlainText("Body").buildEmail();
    }

    private static void validate(final MailerGenericBuilder<?> builder, final Email email) {
        ((CliMailerConfigurationBuilder) builder).validateEmailForCli(email);
    }

    private static DkimConfig dkim() {
        return DkimConfig.builder().dkimPrivateKeyData("unused-test-key").dkimSigningDomain("example.org").dkimSelector("test").build();
    }

    private static DKIMModule signingBoundary(final AtomicInteger calls, final AtomicReference<MimeMessage> message) {
        final DKIMModule dkim = mock(DKIMModule.class);
        when(dkim.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    final MimeMessage mime = invocation.getArgument(1);
                    message.set(mime);
                    return FinalizedMimeMessage.finalizeMessage(mime, FINAL_WIRE_SIGNED);
                });
        return dkim;
    }
}
