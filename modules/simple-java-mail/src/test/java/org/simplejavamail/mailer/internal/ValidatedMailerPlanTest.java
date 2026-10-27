package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValidatedMailerPlanTest {
    @Test
    void captureFreezesExecutionGovernanceAndRegistrationsBeforeResolution() throws Exception {
        final var config = ConfigLoader.builder().load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        final AtomicInteger capturedIds = new AtomicInteger();
        final AtomicInteger changedIds = new AtomicInteger();
        final Email before = mail.emailBuilder().startingBlank().withSubject("captured").buildEmail();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config).withTransportModeLoggingOnly(true)
                .withThreadPoolSize(2).withThreadPoolKeepAliveTime(Duration.ofSeconds(3)).withEmailDefaults(before)
                .withSendIdGenerator(() -> { capturedIds.incrementAndGet(); return UUID.randomUUID(); });
        final CapturedMailerInputs captured = builder.captureMailerInputs();
        builder.withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(7).withQueueCapacity(4).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(9).build())
                .withEmailDefaults(mail.emailBuilder().startingBlank().withSubject("changed").buildEmail())
                .withSendIdGenerator(() -> { changedIds.incrementAndGet(); return UUID.randomUUID(); });
        final ValidatedMailerPlan plan = ValidatedMailerPlan.validate(ResolvedMailerConfiguration.resolve(captured));
        assertThat(capturedIds).hasValue(0);
        assertThat(changedIds).hasValue(0);
        try (Mailer built = MailerImpl.construct(plan)) {
            assertThat(built.getOperationalConfig().getThreadPoolSize()).isEqualTo(2);
            assertThat(built.getOperationalConfig().getThreadPoolKeepAliveTime()).isEqualTo(Duration.ofSeconds(3));
            assertThat(built.getOperationalConfig().getConnectionPoolMaxSize()).isNotEqualTo(9);
            final Email submitted = mail.emailBuilder().startingBlank().from("sender@example.org").to("recipient@example.org").buildEmail();
            assertThat(built.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(submitted).getSubject()).isEqualTo("captured");
            built.sync().sendMail(submitted);
            assertThat(capturedIds).hasValue(1);
            assertThat(changedIds).hasValue(0);
        }
    }

    enum InvalidNode { PARENT_WORKERS, CHILD_WORKERS, CHILD_POOL, CHILD_GOVERNANCE }

    @ParameterizedTest
    @EnumSource(InvalidNode.class)
    void everyNodeIsValidatedBeforeAnyOwnedGraphResourceIsAllocated(final InvalidNode invalid) {
        final Map<String, Object> values = new LinkedHashMap<>(Map.of(
                "simplejavamail.smtp.host", "primary.example.org", "simplejavamail.failover.mailer", "backup",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.org",
                "simplejavamail.mailers.backup.proxy.host", "proxy.example.org",
                "simplejavamail.mailers.backup.proxy.username", "test-user",
                "simplejavamail.mailers.backup.proxy.password", "test-password"));
        switch (invalid) {
            case PARENT_WORKERS: values.put("simplejavamail.defaults.poolsize", 0); break;
            case CHILD_WORKERS: values.put("simplejavamail.mailers.backup.defaults.poolsize", 0); break;
            case CHILD_POOL:
                values.put("simplejavamail.mailers.backup.defaults.connectionpool.coresize", 3);
                values.put("simplejavamail.mailers.backup.defaults.connectionpool.maxsize", 1);
                break;
            case CHILD_GOVERNANCE: values.put("simplejavamail.mailers.backup.smime.signing.keystore", "missing-867-keystore.pkcs12"); break;
            default: throw new AssertionError(invalid);
        }
        final var config = ConfigLoader.builder().withMap(values).load();
        final AuthenticatedSocksModule socks = mock(AuthenticatedSocksModule.class);
        when(socks.createAnonymousSocks5Server(any())).thenThrow(new AssertionError("Proxy allocated before complete validation"));
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
             MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(socks);
            assertThatThrownBy(() -> new MailerRegularBuilderImpl(config).buildMailer()).isInstanceOf(RuntimeException.class);
            assertThat(executors.constructed()).isEmpty();
            verifyNoInteractions(socks);
        }
    }

    @Test
    void graphRateLimitIncompatibilityIsRejectedBeforeConstructingEitherNode() {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.transport.mode.logging.only", true, "simplejavamail.failover.mailer", "backup",
                "simplejavamail.smtp.ratelimit.group", "shared", "simplejavamail.smtp.ratelimit.messages.limit", 1,
                "simplejavamail.smtp.ratelimit.messages.period", "PT1S",
                "simplejavamail.mailers.backup.transport.mode.logging.only", true,
                "simplejavamail.mailers.backup.smtp.ratelimit.group", "shared",
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.limit", 2,
                "simplejavamail.mailers.backup.smtp.ratelimit.messages.period", "PT1S")).load();
        try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            assertThatThrownBy(() -> new MailerRegularBuilderImpl(config).buildMailer()).hasMessageContaining("sharing a rate-limit group");
            assertThat(executors.constructed()).isEmpty();
        }
    }

    @Test
    void losingTheCallerSessionClaimAfterValidationRetiresOwnedWorkersWithoutRetiringAnotherOwner() {
        final Session session = Session.getInstance(new Properties());
        final var builder = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session);
        final ValidatedMailerPlan plan = ValidatedMailerPlan.validate(ResolvedMailerConfiguration.resolve(builder.captureMailerInputs()));
        final Object otherOwner = new Object();
        session.getProperties().put("org.simplejavamail.internal.mailerOwner", otherOwner);
        try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class, (executor, context) -> {
            final AtomicBoolean shutdown = new AtomicBoolean();
            when(executor.isShutdown()).thenAnswer(invocation -> shutdown.get());
            doAnswer(invocation -> { shutdown.set(true); return null; }).when(executor).shutdown();
        })) {
            assertThatThrownBy(() -> MailerImpl.construct(plan)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already belongs");
            assertThat(executors.constructed()).hasSize(1);
            verify(executors.constructed().get(0)).shutdown();
            assertThat(session.getProperties().get("org.simplejavamail.internal.mailerOwner")).isSameAs(otherOwner);
        }
    }
}
