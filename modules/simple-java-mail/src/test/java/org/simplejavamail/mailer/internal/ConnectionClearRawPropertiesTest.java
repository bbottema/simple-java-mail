package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionClearRawPropertiesTest {

    @Test
    void clearsCapturedRawPropertiesForEveryTransportWithoutChangingOtherProtocolProperties() throws Exception {
        for (TransportStrategy strategy : TransportStrategy.values()) {
            final Map<String, String> raw = rawProperties(strategy);
            final String otherPrefix = strategy.propertyNameLocalHost().startsWith("mail.smtps.") ? "mail.smtp." : "mail.smtps.";
            raw.put(otherPrefix + "localhost", "other-protocol.example.test");
            raw.put(otherPrefix + "localaddress", "192.0.2.9");
            raw.put(otherPrefix + "localport", "9009");
            raw.put("mail.mime.charset", "UTF-8");
            try (Mailer mailer = builder(factory(raw), strategy).withConnectionConfig(clearAll()).buildMailer()) {
                assertConnection(mailer, strategy, null, null, null);
                assertThat(mailer.getOperationalConfig().getProperties().getProperty(strategy.propertyNameLocalHost()))
                        .isEqualTo("raw.example.test");
                assertThat(mailer.getSession().getProperty(otherPrefix + "localhost")).isEqualTo("other-protocol.example.test");
                assertThat(mailer.getSession().getProperty(otherPrefix + "localaddress")).isEqualTo("192.0.2.9");
                assertThat(mailer.getSession().getProperty(otherPrefix + "localport")).isEqualTo("9009");
                assertThat(mailer.getSession().getProperty("mail.mime.charset")).isEqualTo("UTF-8");
            }
        }
    }

    @Test
    void clearsJavaRawPropertiesRegardlessOfTheRawSetterOrder() throws Exception {
        for (TransportStrategy strategy : TransportStrategy.values()) {
            for (boolean rawFirst : List.of(false, true)) {
                final MailerRegularBuilder<?> builder = builder(factory(Map.of()), strategy);
                if (rawFirst) {
                    rawProperties(strategy).forEach(builder::withProperty);
                }
                builder.withConnectionConfig(clearAll());
                if (!rawFirst) {
                    rawProperties(strategy).forEach(builder::withProperty);
                }
                try (Mailer mailer = builder.buildMailer()) {
                    assertConnection(mailer, strategy, null, null, null);
                }
            }
        }
    }

    @Test
    void clearingTheAddressAlsoClearsItsPortButKeepsTheClientHostname() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        try (Mailer mailer = builder(factory(rawProperties(strategy)), strategy)
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().build()).buildMailer()) {
            assertConnection(mailer, strategy, null, null, "raw.example.test");
        }
    }

    @Test
    void clearingOnlyThePortKeepsTheAddressAndClientHostname() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTPS;
        try (Mailer mailer = builder(factory(rawProperties(strategy)), strategy)
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindPort().build()).buildMailer()) {
            assertConnection(mailer, strategy, "192.0.2.1", null, "raw.example.test");
        }
    }

    @Test
    void clearingOnlyTheClientHostnameKeepsTheBinding() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP_TLS;
        try (Mailer mailer = builder(factory(rawProperties(strategy)), strategy)
                .withConnectionConfig(ConnectionConfig.builder().clearSmtpClientHostname().build()).buildMailer()) {
            assertConnection(mailer, strategy, "192.0.2.1", 8000, null);
        }
    }

    @Test
    void nullableSettersRemoveOnlyTheirNamedComponents() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final SimpleJavaMail factory = factory(rawProperties(strategy));
        try (Mailer addressRemoved = builder(factory, strategy).withConnectionConfig(ConnectionConfig.builder()
                .withLocalBindAddress(null).build()).buildMailer();
                Mailer portRemoved = builder(factory, strategy).withConnectionConfig(ConnectionConfig.builder()
                        .withLocalBindPort(null).build()).buildMailer();
                Mailer hostnameRemoved = builder(factory, strategy).withConnectionConfig(ConnectionConfig.builder()
                        .withSmtpClientHostname(null).build()).buildMailer()) {
            assertConnection(addressRemoved, strategy, null, 8000, "raw.example.test");
            assertConnection(portRemoved, strategy, "192.0.2.1", null, "raw.example.test");
            assertConnection(hostnameRemoved, strategy, "192.0.2.1", 8000, null);
        }
    }

    @Test
    void unsetChoicesKeepRawPropertiesAndReplacingTheGroupRestoresThem() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final MailerRegularBuilder<?> builder = builder(factory(rawProperties(strategy)), strategy);
        try (Mailer untouched = builder.withConnectionConfig(ConnectionConfig.builder().build()).buildMailer();
                Mailer cleared = builder.withConnectionConfig(clearAll()).buildMailer();
                Mailer replaced = builder.withConnectionConfig(ConnectionConfig.builder().build()).buildMailer();
                Mailer restored = builder.withConnectionConfig(clearAll()).useConfiguredConnectionConfig().buildMailer()) {
            assertConnection(untouched, strategy, "192.0.2.1", 8000, "raw.example.test");
            assertConnection(cleared, strategy, null, null, null);
            assertConnection(replaced, strategy, "192.0.2.1", 8000, "raw.example.test");
            assertConnection(restored, strategy, "192.0.2.1", 8000, "raw.example.test");
        }
    }

    @Test
    void useConfiguredRestoresRawPrecedenceWhenJavaMembersAreUnset() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final Map<String, String> settings = capturedRawSettings(rawProperties(strategy));
        settings.put("simplejavamail.smtp.localaddress", "192.0.2.2");
        settings.put("simplejavamail.smtp.localport", "8001");
        settings.put("simplejavamail.smtp.clienthostname", "captured.example.test");
        settings.put("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES");
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(settings).load());
        final ConnectionConfig restored = clearAll().toBuilder().useConfiguredLocalBindAddress().useConfiguredLocalBindPort()
                .useConfiguredSmtpClientHostname().build();
        assertThat(restored.getLocalBindAddress()).isNull();
        assertThat(restored.getLocalBindPort()).isNull();
        assertThat(restored.getSmtpClientHostname()).isNull();
        try (Mailer mailer = builder(factory, strategy).withConnectionConfig(restored).buildMailer()) {
            assertConnection(mailer, strategy, "192.0.2.1", 8000, "raw.example.test");
        }
        final SimpleJavaMail withoutRaw = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.localaddress", "192.0.2.2", "simplejavamail.smtp.localport", "8001",
                "simplejavamail.smtp.clienthostname", "captured.example.test")).load());
        try (Mailer mailer = builder(withoutRaw, strategy).withConnectionConfig(restored).buildMailer()) {
            assertConnection(mailer, strategy, "192.0.2.2", 8001, "captured.example.test");
        }
    }

    @Test
    void explicitTypedValuesCanReplaceClearsWithoutChangingEarlierMailers() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTPS;
        final ConnectionConfig.Builder choices = clearAll().toBuilder();
        final MailerRegularBuilder<?> builder = builder(factory(rawProperties(strategy)), strategy);
        try (Mailer cleared = builder.withConnectionConfig(choices.build()).buildMailer();
                Mailer replaced = builder.withConnectionConfig(choices.withLocalBindAddress("192.0.2.3", 8002)
                        .withSmtpClientHostname("selected.example.test").build()).buildMailer()) {
            assertConnection(cleared, strategy, null, null, null);
            assertConnection(replaced, strategy, "192.0.2.3", 8002, "selected.example.test");
        }
    }

    @Test
    void existingEmptyAndWhitespaceStringRulesRemainUnchanged() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final SimpleJavaMail factory = factory(rawProperties(strategy));
        try (Mailer empty = builder(factory, strategy).withConnectionConfig(ConnectionConfig.builder()
                .withLocalBindAddress("").withSmtpClientHostname("").build()).buildMailer();
                Mailer whitespace = builder(factory, strategy).withConnectionConfig(ConnectionConfig.builder()
                        .withLocalBindAddress(" ").withSmtpClientHostname(" ").build()).buildMailer()) {
            assertConnection(empty, strategy, "192.0.2.1", 8000, "raw.example.test");
            assertConnection(whitespace, strategy, " ", 8000, " ");
        }
    }

    @Test
    void clearingDoesNotChangeThePublishedSocketTimeoutReset() throws Exception {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final Map<String, String> raw = rawProperties(strategy);
        raw.put(strategy.propertyNameTimeout(), "17");
        try (Mailer mailer = builder(factory(raw), strategy).withConnectionConfig(clearAll().toBuilder()
                .withSessionTimeout(Duration.ofMillis(23)).resetSessionTimeout().build()).buildMailer()) {
            assertConnection(mailer, strategy, null, null, null);
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
            assertThat(mailer.getSession().getProperty(strategy.propertyNameTimeout()))
                    .isEqualTo(String.valueOf(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT.toMillis()));
        }
    }

    @Test
    void callerOwnedSessionsRetainTheSameExplicitRemovalBehavior() throws Exception {
        for (TransportStrategy strategy : List.of(TransportStrategy.SMTP, TransportStrategy.SMTPS)) {
            final Properties properties = new Properties();
            properties.putAll(strategy.generateProperties());
            properties.putAll(rawProperties(strategy));
            properties.setProperty(strategy.propertyNameHost(), "caller.example.test");
            final Session session = Session.getInstance(properties);
            try (Mailer mailer = factory(rawProperties(strategy)).mailerBuilder(session).withConnectionConfig(clearAll()).buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(session);
                assertConnection(mailer, strategy, null, null, null);
            }
        }
    }

    @Test
    void callerOwnedInheritedPropertiesStillRejectRemovalWithoutMutation() {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        for (String inheritedKey : rawProperties(strategy).keySet()) {
            final Properties defaults = new Properties();
            defaults.setProperty(inheritedKey, rawProperties(strategy).get(inheritedKey));
            final Properties properties = new Properties(defaults);
            properties.putAll(rawProperties(strategy));
            final Session session = Session.getInstance(properties);
            final Properties original = (Properties) properties.clone();
            assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session).withConnectionConfig(clearAll()).buildMailer())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(inheritedKey)
                    .hasMessageContaining("inherits a default");
            assertThat(properties).isEqualTo(original);
            assertThat(session.getProperty(inheritedKey)).isEqualTo(defaults.getProperty(inheritedKey));
        }
    }

    @Test
    void correspondingTypedAndRawLocksStillRejectClears() {
        final TransportStrategy strategy = TransportStrategy.SMTP;
        final Map<String, String> locks = Map.of(
                "simplejavamail.locked.smtp.localaddress", "192.0.2.1",
                "simplejavamail.locked.smtp.localport", "8000",
                "simplejavamail.locked.smtp.clienthostname", "raw.example.test",
                "simplejavamail.locked.extraproperties." + strategy.propertyNameLocalAddress(), "192.0.2.1",
                "simplejavamail.locked.extraproperties." + strategy.propertyNameLocalPort(), "8000",
                "simplejavamail.locked.extraproperties." + strategy.propertyNameLocalHost(), "raw.example.test");
        for (Map.Entry<String, String> lock : locks.entrySet()) {
            final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder()
                    .withMap(Map.of(lock.getKey(), lock.getValue())).load());
            assertThatThrownBy(() -> builder(factory, strategy).withConnectionConfig(clearAll()).buildMailer())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(lock.getKey());
            final Properties properties = new Properties();
            properties.putAll(rawProperties(strategy));
            properties.setProperty(strategy.propertyNameHost(), "caller.example.test");
            final Session session = Session.getInstance(properties);
            final Properties original = (Properties) properties.clone();
            assertThatThrownBy(() -> factory.mailerBuilder(session).withConnectionConfig(clearAll()).buildMailer())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(lock.getKey());
            assertThat(properties).isEqualTo(original);
        }
    }

    private static ConnectionConfig clearAll() {
        return ConnectionConfig.builder().clearLocalBindAddress().clearSmtpClientHostname().build();
    }

    private static Map<String, String> rawProperties(final TransportStrategy strategy) {
        final Map<String, String> raw = new LinkedHashMap<>();
        raw.put(strategy.propertyNameLocalAddress(), "192.0.2.1");
        raw.put(strategy.propertyNameLocalPort(), "8000");
        raw.put(strategy.propertyNameLocalHost(), "raw.example.test");
        return raw;
    }

    private static Map<String, String> capturedRawSettings(final Map<String, String> raw) {
        final Map<String, String> settings = new LinkedHashMap<>();
        raw.forEach((key, value) -> settings.put("simplejavamail.extraproperties." + key, value));
        return settings;
    }

    private static SimpleJavaMail factory(final Map<String, String> raw) {
        final Map<String, String> settings = capturedRawSettings(raw);
        // These controls deliberately remove or replace an imported raw connection baseline.
        settings.put("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES");
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(settings).load());
    }

    private static MailerRegularBuilder<?> builder(final SimpleJavaMail factory, final TransportStrategy strategy) {
        return factory.mailerBuilder().withSMTPServer("localhost", strategy.getDefaultServerPort(), "user", "credential")
                .withTransportStrategy(strategy);
    }

    private static void assertConnection(final Mailer mailer, final TransportStrategy strategy, final String address,
            final Integer port, final String hostname) {
        assertThat(mailer.getSession().getProperty(strategy.propertyNameLocalAddress())).as("%s local address", strategy).isEqualTo(address);
        assertThat(mailer.getSession().getProperty(strategy.propertyNameLocalPort())).as("%s local port", strategy)
                .isEqualTo(port == null ? null : port.toString());
        assertThat(mailer.getSession().getProperty(strategy.propertyNameLocalHost())).as("%s client hostname", strategy).isEqualTo(hostname);
        assertThat(mailer.getOperationalConfig().getLocalBindAddress()).isEqualTo(address);
        assertThat(mailer.getOperationalConfig().getLocalBindPort()).isEqualTo(port);
        assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isEqualTo(hostname);
    }
}
