package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionProtocolContractTest {
    @Test
    void anExplicitJavaProtocolCompletesAMissingNamespaceBeforeConnectionChoicesAndLocks() throws Exception {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            final Session session = Session.getInstance(new Properties());
            session.setProtocolForAddress("rfc822", "smtps");
            try (Mailer mailer = factory(Map.of("simplejavamail.locked.defaults.readtimeoutmillis", "2000"))
                    .mailerBuilder(session).withProperty("mail.transport.protocol", protocol)
                    .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofSeconds(2)).build()).buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(session);
                assertThat(session.getProperty("mail.transport.protocol")).isEqualTo(protocol);
                assertThat(session.getProperty("mail." + protocol + ".timeout")).isEqualTo("2000");
                assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofSeconds(2));
            }
        }
    }

    @Test
    void explicitProtocolConflictsRemainStrictIncludingACustomTarget() throws Exception {
        for (String replacement : new String[] {"smtps", "application-protocol"}) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", "smtp");
            final Session supplied = Session.getInstance(properties);
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of()).mailerBuilder(supplied)
                        .withProperty("mail.transport.protocol", replacement).buildMailer()) { }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.transport.protocol");
            assertThat(supplied.getProperty("mail.transport.protocol")).isEqualTo("smtp");
            try (Mailer mailer = factory(Map.of()).mailerBuilder(supplied)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperty("mail.transport.protocol", replacement).buildMailer()) {
                assertThat(mailer.getSession().getProperty("mail.transport.protocol")).isEqualTo(replacement);
            }
        }
    }

    @Test
    void protocolLockRemainsBindingInBothConflictModes() {
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            final Session supplied = Session.getInstance(new Properties());
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of("simplejavamail.locked.extraproperties.mail.transport.protocol", "smtp"))
                        .mailerBuilder(supplied).withConnectionConflictPolicy(policy)
                        .withProperty("mail.transport.protocol", "smtps").buildMailer()) { }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.extraproperties.mail.transport.protocol");
            assertThat(supplied.getProperties()).isEmpty();
        }
    }

    @Test
    void callbackNamespaceValidationRequiresAProtocolWithoutCallingTheCallback() {
        final Session supplied = Session.getInstance(new Properties());
        final AtomicInteger calls = new AtomicInteger();
        final OAuth2AccessTokenProvider callback = () -> { calls.incrementAndGet(); return "token"; };
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory(Map.of()).mailerBuilder(supplied).withOAuth2AccessTokenProvider(callback).buildMailer()) { }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.transport.protocol");
        assertThat(calls).hasValue(0);
        assertThat(supplied.getProperties()).isEmpty();
    }

    @Test
    void ordinaryFactoryProxyDefaultsCannotActivateAGuessedSuppliedSessionRoute() throws Exception {
        final Session supplied = Session.getInstance(new Properties());
        try (Mailer mailer = factory(Map.of("simplejavamail.proxy.host", "proxy.example.test"))
                .mailerBuilder(supplied).buildMailer()) {
            assertThat(mailer.getProxyConfig().requiresProxy()).isFalse();
            assertThat(supplied.getProperty("mail.smtp.socks.host")).isNull();
            assertThat(supplied.getProperty("mail.smtps.socks.host")).isNull();
        }
        assertThat(supplied.getProperties()).isEmpty();
    }

    private static SimpleJavaMail factory(final Map<String, ?> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }
}
