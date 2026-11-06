package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.SocketFactoryConfiguration;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static testutil.socks.AuthenticatedSocksRouteTestSupport.requireDirectRoute;

class ConnectionPlanLifecycleTest {
    enum InvalidExecutionInput { JAVA, CONFIGURATION, SUPPLIED_SESSION }

    @ParameterizedTest
    @EnumSource(InvalidExecutionInput.class)
    void invalidParentExecutionIsRejectedBeforeNamedChildAllocation(final InvalidExecutionInput input) {
        final Map<String, Object> properties = new LinkedHashMap<>(Map.of(
                "simplejavamail.smtp.host", "primary.example.org", "simplejavamail.failover.mailer", "backup",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.org",
                "simplejavamail.mailers.backup.proxy.host", "proxy.example.org",
                "simplejavamail.mailers.backup.proxy.username", "test-user",
                "simplejavamail.mailers.backup.proxy.password", "test-password"));
        if (input == InvalidExecutionInput.CONFIGURATION) {
            properties.put("simplejavamail.defaults.async.queue.capacity", 1);
        }
        final var config = ConfigLoader.builder().withMap(properties).load();
        final Session suppliedSession = Session.getInstance(new Properties());
        final Properties before = (Properties) suppliedSession.getProperties().clone();
        final ExecutorService borrowed = Executors.newSingleThreadExecutor();
        final AuthenticatedSocksModule module = mock(AuthenticatedSocksModule.class);
        when(module.createSocketProvider(any())).thenThrow(new AssertionError("Named child allocated a proxy socket capability"));
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
             MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(module);
            final MailerGenericBuilderImpl<?> builder = input == InvalidExecutionInput.SUPPLIED_SESSION
                    ? new MailerFromSessionBuilderImpl(config).usingSession(suppliedSession) : new MailerRegularBuilderImpl(config);
            final AsyncExecutionConfig.Builder execution = AsyncExecutionConfig.builder().withExecutorService(borrowed);
            if (input != InvalidExecutionInput.CONFIGURATION) {
                execution.withQueueCapacity(1);
            }
            builder.withAsyncExecutionConfig(execution.build());

            assertThatThrownBy(builder::buildMailer).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("caller-owned executor").hasMessageContaining("withExecutorService");
            verifyNoInteractions(module);
            assertThat(executors.constructed()).isEmpty();
            assertThat(borrowed.isShutdown()).isFalse();
            assertThat(suppliedSession.getProperties()).isEqualTo(before);
        } finally {
            borrowed.shutdownNow();
        }
    }

    @Test
    void aRawSetterCapturesOneTextValueForStorageAndConflictComparison() throws Exception {
        final AtomicInteger renders = new AtomicInteger();
        final Object changingText = new Object() {
            @Override public String toString() { return renders.incrementAndGet() == 1 ? "123" : "456"; }
        };
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.defaults.readtimeoutmillis", 123)).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config).withSMTPServerHost("relay.example.org")
                .withProperty("mail.smtp.timeout", changingText);
        assertThat(renders).hasValue(1);
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
        }
        assertThat(renders).hasValue(1);
    }

    @Test
    void missingRawPropertyNamesDoNotMutateTheChoiceHistory() throws Exception {
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load()).withSMTPServerHost("relay.example.org");
        assertThatThrownBy(() -> builder.withProperty(null, 123)).isInstanceOf(NullPointerException.class);
        assertThat(builder.captureConnectionInputs(null).getRawChoices()).doesNotContainKey(null);
        try (Mailer ignored = builder.buildMailer()) {
            // The rejected setter leaves the existing draft usable.
        }
    }
    @Test
    void eachNamedNodeIsPreparedOnceAndUsedForConstruction() throws Exception {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.host", "primary.example.org",
                "simplejavamail.failover.mailer", "backup", "simplejavamail.mailers.backup.smtp.host", "backup.example.org")).load();
        final FactoryMailerRuntime runtime = spy(new FactoryMailerRuntime(config, new FactorySendingLimits()));
        try (Mailer ignored = new MailerRegularBuilderImpl(config, runtime).buildMailer()) {
            verify(runtime, times(1)).named("backup");
        }
    }

    @Test
    void constructionConsumesCapturedRawProperties() throws Exception {
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().load()) {
            @Override MailerImpl constructMailer(final ValidatedMailerPlan plan) {
                withProperty("mail.smtp.timeout", "99999");
                withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                return super.constructMailer(plan);
            }
        };
        try (Mailer mailer = builder.withSMTPServerHost("relay.example.org").withProperty("mail.smtp.timeout", "123").buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.timeout")).isEqualTo("123");
            assertThat(mailer.getOperationalConfig().getProperties().getProperty("mail.smtp.timeout")).isEqualTo("123");
            assertThat(mailer.getOperationalConfig().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.REJECT_CONFLICTS);
        }
    }

    @Test
    void untouchedSuppliedDefaultsRemainInherited() throws Exception {
        final Properties inherited = new Properties();
        inherited.setProperty("mail.smtp.timeout", "5000");
        final Properties supplied = new Properties(inherited);
        final Session session = Session.getInstance(supplied);
        try (Mailer mailer = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session).buildMailer()) {
            assertThat(supplied.containsKey("mail.smtp.timeout")).isFalse();
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        }
        assertThat(supplied.containsKey("mail.smtp.timeout")).isFalse();
    }

    @Test
    void inheritedStringTokenRemainsVisibleWithoutMaterializingIt() throws Exception {
        final Properties inherited = new Properties();
        inherited.setProperty("simplejavamail.oauth2.token", "test-token");
        final Properties supplied = new Properties(inherited);
        supplied.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        supplied.setProperty("mail.smtp.user", "test-user");
        final Session session = Session.getInstance(supplied);
        try (Mailer ignored = new MailerFromSessionBuilderImpl(ConfigLoader.builder().load()).usingSession(session).buildMailer()) {
            assertThat(supplied.containsKey("simplejavamail.oauth2.token")).isFalse();
            assertThat(supplied.getProperty("simplejavamail.oauth2.token")).isEqualTo("test-token");
        }
    }

    @Test
    void allocationFailureRestoresOnlyTheAppliedCallerSessionChoices() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.socks.host", "proxy.example.org");
        properties.setProperty("application.extra", "original");
        final Properties before = (Properties) properties.clone();
        final Session session = Session.getInstance(properties);
        final var config = ConfigLoader.builder().load();
        final AuthenticatedSocksModule module = mock(AuthenticatedSocksModule.class);
        final IllegalStateException failure = new IllegalStateException("proxy capability allocation failed");
        when(module.createSocketProvider(any())).thenThrow(failure);
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session)).isTrue();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(module);
            assertThatThrownBy(() -> new MailerFromSessionBuilderImpl(config).usingSession(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperty("application.extra", "changed")
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build())
                    .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("test-secret").build()).buildMailer())
                    .isSameAs(failure);
            verify(module).createSocketProvider(any());
        }
        assertThat(properties).isEqualTo(before);
        try (Mailer ignored = new MailerFromSessionBuilderImpl(config).usingSession(session).buildMailer()) {
            // A failed construction must retire its ownership claim as well as its property changes.
        }
    }

    @ParameterizedTest(name = "late direct decoration rollback, originalTransport={0}")
    @ValueSource(booleans = {false, true})
    void lateDirectDecorationFailureRestoresCallerTransportAndOwnership(final boolean originalTransport) throws Exception {
        requireDirectRoute();
        final FailingDecorationProperties properties = new FailingDecorationProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "smtp.example.org");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.socks.host", "proxy.example.org");
        properties.setProperty("mail.smtp.socks.port", "2345");
        properties.setProperty("application.extra", "original");
        final SocketFactory applicationFactory = mock(SocketFactory.class);
        if (originalTransport) { properties.put("mail.smtp.socketFactory.transport", applicationFactory); }
        final Properties before = (Properties) properties.clone();
        final Session session = Session.getInstance(properties);
        final jakarta.mail.Provider provider = session.getProvider("smtp");
        assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(session)).isTrue();
        final boolean originalDebug = session.getDebug();
        final java.io.PrintStream originalDebugOut = session.getDebugOut();
        final var config = ConfigLoader.builder().load();
        final AuthenticatedSocksModule module = mock(AuthenticatedSocksModule.class);
        when(module.createSocketProvider(any())).thenReturn(raw -> {
            throw new AssertionError("Offline construction must not create or wrap a physical socket");
        });
        properties.failOnDecoration = true;
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(module);
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            assertThatThrownBy(() -> new MailerFromSessionBuilderImpl(config).usingSession(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperty("application.extra", "changed")
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build())
                    .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("test-secret").build()).buildMailer())
                    .isSameAs(properties.failure);
            assertThat(properties.decorated).as("the direct factory was written before the injected late failure")
                    .isInstanceOf(SocketFactoryConfiguration.class);
            if (originalTransport) {
                assertThat(((SocketFactoryConfiguration) properties.decorated).configuredInstance()).isSameAs(applicationFactory);
            }
            assertThat(properties).isEqualTo(before);
            assertThat(session.getProvider("smtp")).isSameAs(provider);
            assertThat(session.getDebug()).isEqualTo(originalDebug);
            assertThat(session.getDebugOut()).isSameAs(originalDebugOut);
            verifyNoInteractions(applicationFactory);
            verify(module).createSocketProvider(any());
            try (Mailer replacement = new MailerFromSessionBuilderImpl(config).usingSession(session).buildMailer()) {
                assertThat(replacement.getSession()).as("failed direct construction retires its ownership claim").isSameAs(session);
            }
            assertThat(properties).isEqualTo(before);
        }
    }

    private static final class FailingDecorationProperties extends Properties {
        private static final long serialVersionUID = 1L;
        private final IllegalStateException failure = new IllegalStateException("direct decoration failed after its Session write");
        private boolean failOnDecoration;
        private Object decorated;

        @Override public synchronized Object put(final Object key, final Object value) {
            final Object previous = super.put(key, value);
            if (failOnDecoration && key.equals("mail.smtp.socketFactory.transport") && value instanceof SocketFactoryConfiguration) {
                failOnDecoration = false;
                decorated = value;
                throw failure;
            }
            return previous;
        }
    }
}
