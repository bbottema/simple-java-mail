package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPool;
import testutil.ConfigLoaderTestHelper;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;

class CapturedOAuth2TokenSourceTest {
    @Test
    void fixedStringVisibilityDoesNotMaterializeInheritedOrOpaquePropertyStorage() {
        final Properties defaults = new Properties();
        defaults.setProperty(OAUTH2_TOKEN_PROPERTY, "inherited-token");
        final Properties properties = new Properties(defaults);
        final Object opaque = new Object();
        properties.put(OAUTH2_TOKEN_PROPERTY, opaque);

        final Supplier<String> selected = OAuth2AccessTokenResolver.captureAccessTokenProvider(Session.getInstance(properties));

        assertThat(selected.get()).isEqualTo("inherited-token");
        assertThat(properties.get(OAUTH2_TOKEN_PROPERTY)).isSameAs(opaque);
        assertThat(defaults.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo("inherited-token");
    }

    @Test
    void capturedCallbackIsLazyAndDoesNotBecomeAnotherSessionsLaterChoice() {
        final AtomicInteger calls = new AtomicInteger();
        final Properties properties = new Properties();
        final Supplier<String> original = () -> "token-" + calls.incrementAndGet();
        properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, original);
        final Supplier<String> selected = OAuth2AccessTokenResolver.captureAccessTokenProvider(Session.getInstance(properties));
        assertThat(calls).hasValue(0);

        properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (Supplier<String>) () -> "another-registration-token");

        assertThat(selected.get()).isEqualTo("token-1");
        assertThat(selected.get()).isEqualTo("token-2");
        assertThat(calls).hasValue(2);
    }

    @Test
    void ordinaryChoiceDoesNotFallBackToPoolAliases() {
        final Properties properties = new Properties();
        properties.put(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY,
                (Supplier<String>) () -> { throw new AssertionError("Legacy callback must remain unused"); });
        properties.setProperty(SmtpConnectionPool.OAUTH2_TOKEN_PROPERTY, "legacy-token");

        assertThat(OAuth2AccessTokenResolver.captureAccessTokenProvider(Session.getInstance(properties))).isNull();
    }

    @Test
    void configuredCallbackFailureRetainsItsCauseWithoutChangingCredentialMode() {
        final Properties properties = new Properties();
        final IllegalStateException failure = new IllegalStateException("Token service unavailable");
        properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (Supplier<String>) () -> { throw failure; });
        final Supplier<String> selected = OAuth2AccessTokenResolver.captureAccessTokenProvider(Session.getInstance(properties));

        assertThatThrownBy(selected::get).isInstanceOf(MailException.class)
                .hasMessage(MailerException.OAUTH2_TOKEN_PROVIDER_FAILED).hasCause(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void pooledSendingRetainsTheCurrentCallbackFailureInsteadOfTryingLegacyCredentials(final String protocol) throws Exception {
        final Properties properties = new Properties();
        final AtomicInteger currentCalls = new AtomicInteger();
        final AtomicInteger legacyCalls = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("Current token service unavailable");
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".host", "localhost");
        properties.setProperty("mail." + protocol + ".port", "9");
        properties.setProperty("mail." + protocol + ".user", "callback-user@example.test");
        properties.setProperty("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
        properties.setProperty("mail." + protocol + ".connectiontimeout", "500");
        final Supplier<String> legacy = () -> {
            legacyCalls.incrementAndGet();
            throw new AssertionError("Legacy credentials must not be consulted");
        };
        properties.put(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY, legacy);
        properties.setProperty(SmtpConnectionPool.OAUTH2_TOKEN_PROPERTY, "unused-legacy-token");
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        try (Mailer mailer = mail.mailerBuilder(Session.getInstance(properties))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build())
                .withOAuth2AccessTokenProvider(() -> { currentCalls.incrementAndGet(); throw failure; }).buildMailer()) {
            assertThat(currentCalls).hasValue(0);
            assertThatThrownBy(() -> mailer.sync().sendMail(mail.emailBuilder().startingBlank()
                    .from("sender@example.test").to("recipient@example.test").withPlainText("Synthetic content").buildEmail()))
                    .isInstanceOf(MailException.class).hasRootCause(failure);
        }
        assertThat(currentCalls).hasValue(1);
        assertThat(legacyCalls).hasValue(0);
        assertThat(properties.get(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(legacy);
    }
}
