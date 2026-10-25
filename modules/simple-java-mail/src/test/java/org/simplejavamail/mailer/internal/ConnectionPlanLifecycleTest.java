package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionPlanLifecycleTest {
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
            @Override MailerImpl constructMailer(final ValidatedConnectionPlan plan) {
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
        when(module.createAnonymousSocks5Server(any())).thenThrow(new IllegalStateException("bridge allocation failed"));
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenReturn(module);
            assertThatThrownBy(() -> new MailerFromSessionBuilderImpl(config).usingSession(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperty("application.extra", "changed")
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build())
                    .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("test-secret").build()).buildMailer())
                    .isInstanceOf(IllegalStateException.class).hasMessage("bridge allocation failed");
        }
        assertThat(properties).isEqualTo(before);
        try (Mailer ignored = new MailerFromSessionBuilderImpl(config).usingSession(session).buildMailer()) {
            // A failed construction must retire its ownership claim as well as its property changes.
        }
    }
}
