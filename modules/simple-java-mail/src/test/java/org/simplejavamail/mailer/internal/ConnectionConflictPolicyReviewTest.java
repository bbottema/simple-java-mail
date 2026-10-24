package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import javax.net.ssl.SSLSocketFactory;
import java.util.Map;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ConnectionConflictPolicyReviewTest {

    @Test
    void strictModeDoesNotInterpretRawTrustHostsAsTheSjmPropertySyntax() {
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory(Map.of()).mailerBuilder()
                    .withSMTPServerHost("relay.example.org")
                    .withProperty("mail.smtp.ssl.trust", "first.example.org;second.example.org")
                    .withTlsConfig(TlsConfig.builder().trustingSSLHosts("first.example.org", "second.example.org").build())
                    .buildMailer()) {
                // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.ssl.trust");
    }

    @Test
    void deliberateTypedTrustHostsReplaceDifferentRawProviderSyntax() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder()
                .withSMTPServerHost("relay.example.org")
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperty("mail.smtp.ssl.trust", "first.example.org;second.example.org")
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("first.example.org", "second.example.org").build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust"))
                    .isEqualTo("first.example.org second.example.org");
        }
    }

    @Test
    void sjmTrustHostSyntaxStillAgreesWithEquivalentRawProviderSyntax() throws Exception {
        try (Mailer mailer = factory(Map.of("simplejavamail.defaults.trustedhosts", "first.example.org;second.example.org"))
                .mailerBuilder().withSMTPServerHost("relay.example.org")
                .withProperty("mail.smtp.ssl.trust", "first.example.org second.example.org")
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust"))
                    .isEqualTo("first.example.org second.example.org");
        }
    }

    @Test
    void strictModeRequiresTheProvidersExactWildcardSyntax() {
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory(Map.of()).mailerBuilder()
                    .withSMTPServerHost("relay.example.org")
                    .withProperty("mail.smtp.ssl.trust", " * ")
                    .withTlsConfig(TlsConfig.builder().trustingAllHosts(true).build())
                    .buildMailer()) {
                // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.ssl.trust");
    }

    @Test
    void deliberateTrustAllChoiceReplacesRawTextThatIsNotAWildcard() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder()
                .withSMTPServerHost("relay.example.org")
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperty("mail.smtp.ssl.trust", " * ")
                .withTlsConfig(TlsConfig.builder().trustingAllHosts(true).build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" * ", "first.example.org;second.example.org"})
    void javaHostListsKeepTheirAlreadyEncodedProviderText(final String hostText) throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder()
                .withSMTPServerHost("relay.example.org")
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts(hostText).build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo(hostText);
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
        }
    }

    @Test
    void wildcardHostEntryDoesNotEnableTrustAllWithoutTheDedicatedChoice() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder()
                .withSMTPServerHost("relay.example.org")
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("*").build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isNull();
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void strictModeDistinguishesAnEmptyRawTrustListFromRemovingIt(final String rawTrustList) {
        assertThatThrownBy(() -> {
            try (Mailer ignored = factory(Map.of()).mailerBuilder()
                    .withSMTPServerHost("relay.example.org")
                    .withProperty("mail.smtp.ssl.trust", rawTrustList)
                    .withTlsConfig(TlsConfig.builder().clearTrustedSSLHosts().build())
                    .buildMailer()) {
                // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.ssl.trust");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void opaqueTrustPropertyObjectsCannotMasqueradeAsProviderText(final ConnectionConflictPolicy policy) throws Exception {
        final Properties rawSettings = new Properties();
        rawSettings.put("mail.smtp.ssl.trust", List.of("relay.example.org"));
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder()
                .withSMTPServerHost("relay.example.org").withConnectionConflictPolicy(policy)
                .withProperties(rawSettings)
                .withTlsConfig(TlsConfig.builder().trustingSSLHosts("relay.example.org").build());

        if (policy == ConnectionConflictPolicy.REJECT_CONFLICTS) {
            assertThatThrownBy(() -> {
                try (Mailer ignored = builder.buildMailer()) {
                    // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.ssl.trust");
        } else {
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo("relay.example.org");
            }
        }
    }

    @Test
    void trustConflictNamesTheSourceThatSuppliedTheHostList() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap("trust mode", Map.of("simplejavamail.defaults.trustallhosts", false))
                .withMap("host exceptions", Map.of("simplejavamail.defaults.trustedhosts", "relay.example.org"))
                .load());
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.org")
                .withProperty("mail.smtp.ssl.trust", "different.example.org");

        assertThatThrownBy(() -> buildAndClose(builder))
                .hasMessageContaining("simplejavamail.defaults.trustedhosts from host exceptions");
    }

    @Test
    void oauthMechanismConflictNamesTheConfiguredTransportSource() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap("transport requirements", Map.of("simplejavamail.transportstrategy", "SMTP_OAUTH2"))
                .withMap("credentials", Map.of("simplejavamail.smtp.username", "user", "simplejavamail.smtp.password", "fake-token"))
                .load());
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withSMTPServerHost("relay.example.org")
                .withProperty("mail.smtp.auth.mechanisms", "PLAIN");

        assertThatThrownBy(() -> buildAndClose(builder))
                .hasMessageContaining("simplejavamail.transportstrategy from transport requirements")
                .hasMessageNotContaining("fake-token");
    }

    @Test
    void oauthMechanismConflictNamesTheJavaTransportChoice() {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder()
                .withSMTPServer("relay.example.org", 587, "user", "fake-token")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2).withProperty("mail.smtp.auth.mechanisms", "PLAIN");

        assertThatThrownBy(() -> buildAndClose(builder))
                .hasMessageContaining("withTransportStrategy").hasMessageNotContaining("fake-token");
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void customMailerDoesNotInterpretInactiveRawSmtpValues(final ConnectionConflictPolicy policy) throws Exception {
        final Object opaqueHost = new Object();
        final Object opaqueSocketFactory = new Object();
        final Properties rawSettings = new Properties();
        rawSettings.put("mail.smtp.host", opaqueHost);
        rawSettings.put("mail.smtp.ssl.socketFactory", opaqueSocketFactory);

        try (Mailer mailer = factory(Map.of()).mailerBuilder()
                .withConnectionConflictPolicy(policy).withCustomMailer(mock(CustomMailer.class))
                .withProperties(rawSettings).buildMailer()) {
            assertThat(mailer.getServerConfig()).isNull();
            assertThat(mailer.getSession().getProperties().get("mail.smtp.host")).isSameAs(opaqueHost);
            assertThat(mailer.getSession().getProperties().get("mail.smtp.ssl.socketFactory")).isSameAs(opaqueSocketFactory);
        }
    }

    @ParameterizedTest
    @EnumSource(ConnectionConflictPolicy.class)
    void ownedEmptyConnectionTextRetainsTheRawBaseline(final ConnectionConflictPolicy policy) throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost("relay.example.org")
                .withConnectionConflictPolicy(policy)
                .withProperty("mail.smtp.localaddress", "192.0.2.1")
                .withProperty("mail.smtp.localhost", "raw.example.org")
                .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("").withSmtpClientHostname("").build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.1");
            assertThat(mailer.getSession().getProperty("mail.smtp.localhost")).isEqualTo("raw.example.org");
        }
    }

    @Test
    void ownedEmptyConnectionTextCannotSilentlyRemoveCapturedTypedValues() {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.smtp.localaddress", "192.0.2.1"))
                .mailerBuilder().withSMTPServerHost("relay.example.org")
                .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("").build());
        assertThatThrownBy(() -> buildAndClose(builder)).hasMessageContaining("mail.smtp.localaddress");
    }

    @Test
    void suppliedSessionRetainsLiteralEmptyConnectionTextWhenDeliberatelySelected() throws Exception {
        final Properties supplied = new Properties();
        supplied.setProperty("mail.smtp.localaddress", "192.0.2.1");
        supplied.setProperty("mail.smtp.localhost", "caller.example.org");
        try (Mailer mailer = factory(Map.of()).mailerBuilder(Session.getInstance(supplied))
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("").withSmtpClientHostname("").build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localaddress")).isEmpty();
            assertThat(mailer.getSession().getProperty("mail.smtp.localhost")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typedFactoryInstanceShadowsTheClassOnlyDuringOwnedSessionActivation(final boolean inheritedClass) throws Exception {
        final SSLSocketFactory instance = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final String className = "javax.net.ssl.SSLSocketFactory";
        final TlsConfig.Builder choices = TlsConfig.builder().withCustomSSLFactoryInstance(instance);
        if (!inheritedClass) {
            choices.withCustomSSLFactoryClass(className);
        }
        final MailerRegularBuilder<?> builder = factory(inheritedClass
                ? Map.of("simplejavamail.custom.sslfactory.class", className) : Map.of())
                .mailerBuilder().withSMTPServerHost("relay.example.org").withTlsConfig(choices.build());
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getServerConfig().getCustomSSLFactoryClass()).isEqualTo(className);
            assertThat(mailer.getServerConfig().getCustomSSLFactoryInstance()).isSameAs(instance);
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isNull();
        }
        builder.withTlsConfig(builder.getTlsConfig().toBuilder().clearCustomSSLFactoryInstance().build());
        try (Mailer restored = builder.buildMailer()) {
            assertThat(restored.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isEqualTo(className);
        }
    }

    @Test
    void typedFactoryInstanceKeepsAnExplicitRawClassProperty() throws Exception {
        final SSLSocketFactory instance = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final String className = "javax.net.ssl.SSLSocketFactory";
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServerHost("relay.example.org")
                .withProperty("mail.smtp.ssl.socketFactory.class", className)
                .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(instance).build()).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isEqualTo(className);
        }
    }

    @Test
    void suppliedSessionKeepsItsFactoryInstanceAndClassProperties() throws Exception {
        final SSLSocketFactory instance = (SSLSocketFactory) SSLSocketFactory.getDefault();
        final String className = "javax.net.ssl.SSLSocketFactory";
        final Properties supplied = new Properties();
        supplied.put("mail.smtp.ssl.socketFactory", instance);
        supplied.setProperty("mail.smtp.ssl.socketFactory.class", className);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(Session.getInstance(supplied)).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isEqualTo(className);
            assertThat(mailer.getSession().getProperties().get("mail.smtp.ssl.socketFactory")).isSameAs(instance);
        }
    }

    private static void buildAndClose(final MailerRegularBuilder<?> builder) throws Exception {
        try (Mailer ignored = builder.buildMailer()) {
            // An unexpected successful build must still release its resources.
        }
    }

    @Test
    void rawSelectedSmtpsCannotBypassLockedTlsFactoryRestrictions() {
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            final Properties rawSettings = new Properties();
            rawSettings.setProperty("mail.transport.protocol", "smtps");
            rawSettings.put("mail.smtps.ssl.socketFactory", SSLSocketFactory.getDefault());

            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of("simplejavamail.locked.defaults.verifyserveridentity", true))
                        .mailerBuilder().withSMTPServerHost("relay.example.org")
                        .withConnectionConflictPolicy(policy).withProperties(rawSettings).buildMailer()) {
                    // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
                }
            }).as(policy.name()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("simplejavamail.locked.defaults.verifyserveridentity")
                    .hasMessageContaining("socket factory");
        }
    }

    @Test
    void deliberatelyRemovedRawFactoryDoesNotConflictWithManagedTlsRequirements() throws Exception {
        final Properties rawSettings = new Properties();
        rawSettings.put("mail.smtp.ssl.socketFactory", SSLSocketFactory.getDefault());

        try (Mailer mailer = factory(Map.of("simplejavamail.locked.defaults.verifyserveridentity", true))
                .mailerBuilder().withSMTPServerHost("relay.example.org")
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperties(rawSettings)
                .withTlsConfig(TlsConfig.builder().clearCustomSSLFactoryInstance().build())
                .buildMailer()) {
            assertThat(mailer.getSession().getProperties()).doesNotContainKey("mail.smtp.ssl.socketFactory");
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        }
    }

    @Test
    void suppliedSessionProtocolOverrideCannotHideTheFinalCustomFactoryFromLocks() {
        final Properties suppliedSettings = new Properties();
        suppliedSettings.setProperty("mail.transport.protocol", "smtp");
        final Session suppliedSession = Session.getInstance(suppliedSettings);
        final Properties rawSettings = new Properties();
        rawSettings.setProperty("mail.transport.protocol", "smtps");
        rawSettings.put("mail.smtps.ssl.socketFactory", SSLSocketFactory.getDefault());

        assertThatThrownBy(() -> {
            try (Mailer ignored = factory(Map.of("simplejavamail.locked.defaults.verifyserveridentity", true))
                    .mailerBuilder(suppliedSession)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProperties(rawSettings).buildMailer()) {
                // Close an unexpectedly accepted Mailer so a regression cannot retain its resources.
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("socket factory");
        assertThat(suppliedSettings).containsOnlyKeys("mail.transport.protocol");
        assertThat(suppliedSession.getProperty("mail.transport.protocol")).isEqualTo("smtp");
    }

    @Test
    void inactiveFactoryOnSuppliedSessionDoesNotControlTheResolvedTlsProtocol() throws Exception {
        final Properties suppliedSettings = new Properties();
        suppliedSettings.setProperty("mail.transport.protocol", "smtp");
        suppliedSettings.put("mail.smtp.ssl.socketFactory", SSLSocketFactory.getDefault());
        final Session suppliedSession = Session.getInstance(suppliedSettings);

        try (Mailer mailer = factory(Map.of("simplejavamail.locked.defaults.verifyserveridentity", true))
                .mailerBuilder(suppliedSession)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperty("mail.transport.protocol", "smtps").buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.transport.protocol")).isEqualTo("smtps");
            assertThat(mailer.getSession().getProperties()).doesNotContainKey("mail.smtps.ssl.socketFactory");
            assertThat(mailer.getSession().getProperty("mail.smtps.ssl.checkserveridentity")).isEqualTo("true");
        }
    }

    private static SimpleJavaMail factory(final Map<String, ?> settings) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(settings).load());
    }
}
