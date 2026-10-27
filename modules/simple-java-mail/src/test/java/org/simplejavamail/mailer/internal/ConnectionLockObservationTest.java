package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import javax.net.ssl.SSLSocketFactory;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ConnectionLockObservationTest {
    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void inheritedFactoryClassCannotBypassTlsLocks(final TransportStrategy strategy) {
        final String sslFactory = strategy.propertyNameSSLSocketFactory();
        try (MockedStatic<ModuleLoader> modules = withoutBatch()) {
            for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                for (String key : new String[] {sslFactory + ".class", sslFactory.replace(".ssl.", ".") + ".class"}) {
                    final Properties defaults = new Properties();
                    defaults.setProperty(key, "example.ApplicationSocketFactory");
                    final Properties properties = sessionProperties(strategy, defaults);
                    final Session session = Session.getInstance(properties);

                    assertThatThrownBy(() -> {
                        try (Mailer ignored = identityLockedFactory().mailerBuilder(session).withConnectionConflictPolicy(policy).buildMailer()) {
                            // A rejected configuration must not complete construction.
                        }
                    }).as("%s %s", policy, key).isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("simplejavamail.locked.defaults.verifyserveridentity")
                            .hasMessageContaining("caller-supplied socket factory");
                    assertThat(properties.containsKey(key)).isFalse();
                    assertThat(properties.getProperty(key)).isEqualTo("example.ApplicationSocketFactory");
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void textLocksUseTheProviderVisibleInheritedStringBehindAnOpaqueDirectEntry(final TransportStrategy strategy) throws Exception {
        final String host = strategy.propertyNameHost();
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.locked.smtp.host", "relay.example.org"));
        try (MockedStatic<ModuleLoader> modules = withoutBatch()) {
            for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                final Properties defaults = new Properties();
                defaults.setProperty(host, "relay.example.org");
                final Properties properties = sessionProperties(strategy, defaults);
                final Object direct = opaqueValue();
                properties.put(host, direct);
                final Session session = Session.getInstance(properties);

                try (Mailer mailer = factory.mailerBuilder(session).withConnectionConflictPolicy(policy).buildMailer()) {
                    assertThat(mailer.getSession().getProperty(host)).isEqualTo("relay.example.org");
                    assertThat(properties.get(host)).isSameAs(direct);
                }
                assertThat(properties.get(host)).isSameAs(direct);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void rawTextLocksUseTheSameObservationAsTypedTextLocks(final TransportStrategy strategy) throws Exception {
        final String host = strategy.propertyNameHost();
        final SimpleJavaMail factory = factory(Map.of("simplejavamail.locked.extraproperties." + host, "relay.example.org"));
        final Properties defaults = new Properties();
        defaults.setProperty(host, "relay.example.org");
        final Properties properties = sessionProperties(strategy, defaults);
        properties.put(host, opaqueValue());
        try (MockedStatic<ModuleLoader> modules = withoutBatch();
             Mailer mailer = factory.mailerBuilder(Session.getInstance(properties))
                     .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
            assertThat(mailer.getSession().getProperty(host)).isEqualTo("relay.example.org");
        }
    }

    @Test
    void numericAndBooleanLocksKeepDirectProviderValuesAheadOfInheritedStrings() {
        final Object[][] cases = {{"mail.smtp.timeout", "123", 456}, {"mail.smtp.ssl.checkserveridentity", "true", false}};
        try (MockedStatic<ModuleLoader> modules = withoutBatch()) {
            for (Object[] scenario : cases) {
                final String key = (String) scenario[0];
                final Properties defaults = new Properties();
                defaults.setProperty(key, (String) scenario[1]);
                final Properties properties = sessionProperties(TransportStrategy.SMTP, defaults);
                properties.put(key, scenario[2]);
                final SimpleJavaMail factory = factory(Map.of("simplejavamail.locked.extraproperties." + key, scenario[1]));

                assertThatThrownBy(() -> {
                    try (Mailer ignored = factory.mailerBuilder(Session.getInstance(properties))
                            .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
                        // The inherited String cannot conceal an incompatible direct number or Boolean.
                    }
                }).as(key).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.locked.extraproperties." + key);
            }
        }
    }

    @Test
    void inheritedTextInAnObjectFactorySlotDoesNotBecomeAnActiveFactory() throws Exception {
        final String key = "mail.smtp.ssl.socketFactory";
        final Properties defaults = new Properties();
        defaults.setProperty(key, "not-a-factory-instance");
        final Properties properties = sessionProperties(TransportStrategy.SMTP, defaults);
        try (MockedStatic<ModuleLoader> modules = withoutBatch();
             Mailer mailer = identityLockedFactory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get(key)).isNull();
            assertThat(mailer.getSession().getProperty(key)).isEqualTo("not-a-factory-instance");
        }
    }

    @Test
    void directFactoryInstancesStillCannotClaimToHonorTlsLocks() {
        final Properties properties = sessionProperties(TransportStrategy.SMTP, new Properties());
        properties.put("mail.smtp.ssl.socketFactory", mock(SSLSocketFactory.class));
        try (MockedStatic<ModuleLoader> modules = withoutBatch()) {
            assertThatThrownBy(() -> {
                try (Mailer ignored = identityLockedFactory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
                    // Presence of a borrowed instance still requires its existing ownership restriction.
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caller-supplied socket factory");
        }
    }

    @Test
    void unrelatedOpaqueProviderPropertiesRetainIdentityWithoutBeingInterpreted() throws Exception {
        final Properties properties = sessionProperties(TransportStrategy.SMTP, new Properties());
        final Object metadata = opaqueValue();
        properties.put("application.metadata", metadata);
        try (MockedStatic<ModuleLoader> modules = withoutBatch();
             Mailer mailer = identityLockedFactory().mailerBuilder(Session.getInstance(properties)).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("application.metadata")).isSameAs(metadata);
        }
    }

    private static Properties sessionProperties(final TransportStrategy strategy, final Properties defaults) {
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (defaults.getProperty(strategy.propertyNameHost()) == null) {
            properties.setProperty(strategy.propertyNameHost(), "relay.example.org");
        }
        return properties;
    }

    private static Object opaqueValue() {
        return new Object() {
            @Override public boolean equals(final Object other) { throw new AssertionError("Do not compare opaque input"); }
            @Override public int hashCode() { throw new AssertionError("Do not hash opaque input"); }
            @Override public String toString() { throw new AssertionError("Do not render opaque input"); }
        };
    }

    private static SimpleJavaMail identityLockedFactory() {
        return factory(Map.of("simplejavamail.locked.defaults.verifyserveridentity", true));
    }

    private static SimpleJavaMail factory(final Map<String, Object> configuration) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(configuration).load());
    }

    private static MockedStatic<ModuleLoader> withoutBatch() {
        final MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
        return modules;
    }
}
