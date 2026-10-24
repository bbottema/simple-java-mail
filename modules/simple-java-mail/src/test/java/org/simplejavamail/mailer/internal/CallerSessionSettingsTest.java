package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import java.time.Duration;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallerSessionSettingsTest {

    @Test
    void reusedSessionsApplyOverridesToTheirActualProtocolForEveryStrategyMarker() throws Exception {
        final SimpleJavaMail factory = factory(Map.of());
        for (TransportStrategy originalStrategy : TransportStrategy.values()) {
            final Session session;
            try (Mailer original = factory.mailerBuilder()
                    .withSMTPServer("localhost", originalStrategy.getDefaultServerPort(), "user", "test-credential")
                    .withTransportStrategy(originalStrategy).buildMailer()) {
                session = original.getSession();
            }
            try (Mailer reused = factory.mailerBuilder(session)
                    .withProperty("mail.transport.protocol", "smtp")
                    .withProperty("mail.smtp.host", "localhost")
                    .withProperty("mail.smtp.user", "user")
                    .withProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
                    .withProperty("mail.smtp.starttls.enable", "true")
                    .withProperty("mail.smtp.starttls.required", "true")
                    .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(5))
                            .withSmtpClientHostname("requested.example").build())
                    .withTlsConfig(TlsConfig.builder().verifyingServerIdentity(true).build()).buildMailer()) {
                assertThat(session.getProperty("mail.smtp.timeout")).as("%s timeout", originalStrategy).isEqualTo("5000");
                assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
                assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
                assertThat(session.getProperty("mail.smtp.localhost")).isEqualTo("requested.example");
                assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
                assertThat(reused.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void inheritedConnectionDefaultsDoNotReplaceCallerSettingsWithOrWithoutAMarker() throws Exception {
        final SimpleJavaMail factory = factory(Map.of(
                "defaults.sessiontimeoutmillis", "12000", "smtp.localaddress", "192.0.2.99", "smtp.localport", "9000",
                "smtp.clienthostname", "factory.example.test", "defaults.trustallhosts", "true"));
        for (final boolean marked : List.of(false, true)) {
            final Session session = configuredSession("smtp", marked);
            try (Mailer mailer = factory.mailerBuilder(session).buildMailer()) {
                assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("6000");
                assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("5000");
                assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("7000");
                assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("private.example.test");
                assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("false");
                assertThat(session.getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.1");
                assertThat(session.getProperty("mail.smtp.localport")).isEqualTo("8000");
                assertThat(session.getProperty("mail.smtp.localhost")).isEqualTo("caller.example.test");
                assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
                assertThat(mailer.getOperationalConfig().getLocalBindAddress()).isEqualTo("192.0.2.1");
                assertThat(mailer.getOperationalConfig().getLocalBindPort()).isEqualTo(8000);
                assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isEqualTo("caller.example.test");
                assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).containsExactly("private.example.test");
                assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
                assertThat(mailer.getOperationalConfig().isVerifyingServerIdentity()).isFalse();
            }
        }
    }

    @Test
    void explicitTimeoutOverridesAllThreeSettingsForBothProtocolsAndMarkerStates() throws Exception {
        for (final String protocol : List.of("smtp", "smtps")) {
            for (final boolean marked : List.of(false, true)) {
                final Session session = configuredSession(protocol, marked);
                try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(1000)).build()).buildMailer()) {
                    assertThat(session.getProperty("mail." + protocol + ".connectiontimeout")).isEqualTo("1000");
                    assertThat(session.getProperty("mail." + protocol + ".timeout")).isEqualTo("1000");
                    assertThat(session.getProperty("mail." + protocol + ".writetimeout")).isEqualTo("1000");
                    assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1000));
                    assertThat(session.getProperty("mail." + protocol + ".ssl.trust")).isEqualTo("private.example.test");
                }
            }
        }
    }

    @Test
    void nullIsNotARequestToDisableConfiguredSocketTimeouts() {
        final Session session = configuredSession("smtp", false);
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(null).build()).buildMailer())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sessionTimeout");
        assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(null).build()).buildMailer())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sessionTimeout");
    }

    @Test
    void connectionOverridesDoNotDependOnTheMarkerAndDoNotImportUnchosenDefaults() throws Exception {
        for (final boolean marked : List.of(false, true)) {
            final Session session = configuredSession("smtp", marked);
            try (Mailer mailer = factory(Map.of()).mailerBuilder(session)
                    .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("192.0.2.2", 8100).withSmtpClientHostname("explicit.example.test").build())
                    .withTlsConfig(TlsConfig.builder().trustingSSLHosts("one.example.test", "two.example.test").verifyingServerIdentity(true).build()).buildMailer()) {
                assertThat(session.getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.2");
                assertThat(session.getProperty("mail.smtp.localport")).isEqualTo("8100");
                assertThat(session.getProperty("mail.smtp.localhost")).isEqualTo("explicit.example.test");
                assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("one.example.test two.example.test");
                assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
                assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
            }
        }
    }

    @Test
    void explicitNullAndEmptyChoicesRemoveCallerSettingsRatherThanLeavingStaleValues() throws Exception {
        final Session session = configuredSession("smtp", true);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session)
                .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress(null, null).withSmtpClientHostname(null).build()).withTlsConfig(TlsConfig.builder().trustingSSLHosts().build()).buildMailer()) {
            assertThat(session.getProperty("mail.smtp.localaddress")).isNull();
            assertThat(session.getProperty("mail.smtp.localport")).isNull();
            assertThat(session.getProperty("mail.smtp.localhost")).isNull();
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isNull();
            assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).isEmpty();
        }
    }

    @Test
    void trustAllIsAnExplicitChoiceAndDisablingItDoesNotImportFactoryTrustedHosts() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("defaults.trustallhosts", "true", "defaults.trustedhosts", "factory.example.test"));
        final Session session = configuredSession("smtp", false);
        session.getProperties().put("mail.smtp.ssl.trust", "*");
        try (Mailer mailer = factory.mailerBuilder(session).withTlsConfig(TlsConfig.builder().trustingAllHosts(false).build()).buildMailer()) {
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isNull();
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
        }
        try (Mailer mailer = factory.mailerBuilder(session).withTlsConfig(TlsConfig.builder().trustingAllHosts(true).build()).buildMailer()) {
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("*");
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isTrue();
        }
    }

    @Test
    void timeoutSnapshotIsNullableAndUsesConfiguredValuesNotProviderDefaults() throws Exception {
        assertThat(OperationalConfig.class.getMethod("getSessionTimeout").getReturnType()).isEqualTo(Duration.class);
        final List<Properties> configurations = List.of(new Properties(), timeoutProperties("1000", "1000", "1000"),
                timeoutProperties("1000", "1000", "2000"), timeoutProperties("1000", "1000", "invalid"),
                timeoutProperties("1000", "1000", "99999999999999999999999"), timeoutProperties("0", "0", "0"));
        final Duration[] expected = {null, Duration.ofMillis(1000), null, null, null, Duration.ZERO};
        for (int index = 0; index < configurations.size(); index++) {
            try (Mailer mailer = factory(Map.of()).mailerBuilder(Session.getInstance(configurations.get(index))).buildMailer()) {
                assertThat(mailer.getOperationalConfig().getSessionTimeout()).as("configuration %s", index).isEqualTo(expected[index]);
            }
        }
    }

    @Test
    void additionalPropertiesContributeToTheEffectiveTimeoutSnapshot() throws Exception {
        final Session session = configuredSession("smtp", false);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withProperties(timeoutProperties("1000", "1000", "1000")).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1000));
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("1000");
        }
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withProperty("mail.smtp.writetimeout", "2000").buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
            assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("2000");
        }
    }

    @Test
    void callerPropertyDefaultsAndProviderSupportedValueTypesRemainVisible() throws Exception {
        final Properties defaults = timeoutProperties("01000", "1000", "1000");
        defaults.setProperty("mail.smtp.localhost", "inherited.example.test");
        final Properties properties = new Properties(defaults);
        properties.put("mail.smtp.connectiontimeout", 1000);
        properties.put("mail.smtp.ssl.checkserveridentity", Boolean.TRUE);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(Session.getInstance(properties)).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1000));
            assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isEqualTo("inherited.example.test");
            assertThat(mailer.getOperationalConfig().isVerifyingServerIdentity()).isTrue();
        }
    }

    @Test
    void clearingCannotSilentlyReactivateAnInheritedPropertiesDefault() {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.localaddress", "192.0.2.1");
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.smtp.localaddress", "192.0.2.2");
        final Session session = Session.getInstance(properties);
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().clearLocalBindPort().build()).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mail.smtp.localaddress")
                .hasMessageContaining("inherits a default").hasMessageContaining("new Session");
        assertThat(session.getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.2");
    }

    @Test
    void inheritedSessionPropertiesCanSatisfyMandatoryConnectionSettings() throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.host", "required.example.test");
        final Session session = Session.getInstance(new Properties(defaults));
        try (Mailer mailer = factory(Map.of("locked.smtp.host", "required.example.test")).mailerBuilder(session).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.host")).isEqualTo("required.example.test");
            SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
        }
    }

    @Test
    void unmarkedSessionsCanSatisfyTlsLocksThroughTheirActualProtocolSettings() throws Exception {
        final Session session = configuredSession("smtp", false);
        session.getProperties().setProperty("mail.smtp.starttls.enable", "true");
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        final SimpleJavaMail factory = factory(Map.of("locked.transportstrategy", "SMTP_TLS"));
        try (Mailer mailer = factory.mailerBuilder(session).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(session);
            SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
        }
        session.getProperties().setProperty("mail.smtp.starttls.required", "false");
        assertThatThrownBy(() -> factory.mailerBuilder(session).buildMailer())
                .hasMessageContaining("locked.transportstrategy").hasMessageContaining("mail.smtp.starttls.required");
    }

    @Test
    void oneArgumentLocalAddressOverrideDoesNotReplaceTheCallersPort() throws Exception {
        final Session session = configuredSession("smtp", false);
        try (Mailer mailer = factory(Map.of("smtp.localport", "9000")).mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("192.0.2.2").build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getLocalBindAddress()).isEqualTo("192.0.2.2");
            assertThat(mailer.getOperationalConfig().getLocalBindPort()).isEqualTo(8000);
            assertThat(session.getProperty("mail.smtp.localport")).isEqualTo("8000");
        }
    }

    @Test
    void namedClearMethodsRemoveCallerOverridesAndDoNotChangeOtherSettings() throws Exception {
        final Session session = configuredSession("smtp", false);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session)
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().clearLocalBindPort().clearSmtpClientHostname().build()).withTlsConfig(TlsConfig.builder().clearTrustedSSLHosts().build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getLocalBindAddress()).isNull();
            assertThat(mailer.getOperationalConfig().getLocalBindPort()).isNull();
            assertThat(mailer.getOperationalConfig().getSmtpClientHostname()).isNull();
            assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).isEmpty();
            assertThat(session.getProperty("mail.smtp.localaddress")).isNull();
            assertThat(session.getProperty("mail.smtp.localport")).isNull();
            assertThat(session.getProperty("mail.smtp.localhost")).isNull();
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isNull();
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        }
    }

    @Test
    void explicitTrustedHostsReplaceAnExistingTrustAllChoice() throws Exception {
        final Session session = configuredSession("smtp", false);
        session.getProperties().setProperty("mail.smtp.ssl.trust", "*");
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withTlsConfig(TlsConfig.builder().trustingSSLHosts("selected.example.test").build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("selected.example.test");
        }
    }

    @Test
    void anUnmarkedOAuthSessionKeepsItsExistingLazyCredentialProviderBehavior() throws Exception {
        final Session session = configuredSession("smtp", false);
        session.getProperties().setProperty("mail.smtp.auth.mechanisms", "XOAUTH2");
        final AtomicInteger tokenRequests = new AtomicInteger();
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withOAuth2AccessTokenProvider(() -> {
            tokenRequests.incrementAndGet();
            return "application-token";
        }).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(session);
            assertThat(tokenRequests).hasValue(0);
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        }
    }

    @Test
    void lockedRawTimeoutsAreIncludedInTheCommonValueForOwnedAndSuppliedSessions() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("locked.extraproperties.mail.smtp.timeout", "5000"));
        final Session session = configuredSession("smtp", false);
        try (Mailer owned = factory.mailerBuilder().withSMTPServer("smtp.example.test", 25).buildMailer();
                Mailer supplied = factory.mailerBuilder(session).buildMailer()) {
            assertThat(owned.getOperationalConfig().getSessionTimeout()).isNull();
            assertThat(owned.getSession().getProperty("mail.smtp.connectiontimeout"))
                    .isEqualTo(String.valueOf(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT.toMillis()));
            assertThat(owned.getSession().getProperty("mail.smtp.timeout")).isEqualTo("5000");
            assertThat(supplied.getOperationalConfig().getSessionTimeout()).isNull();
            assertThat(supplied.getSession().getProperty("mail.smtp.timeout")).isEqualTo("5000");
        }
    }

    @Test
    void resetsExplicitlyApplyThePublishedDefaultToCallerSessions() throws Exception {
        final Session session = configuredSession("smtp", false);
        try (Mailer mailer = factory(Map.of("defaults.sessiontimeoutmillis", "12000"))
                .mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(1000)).resetSessionTimeout().build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo(String.valueOf(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT.toMillis()));
        }
    }

    @Test
    void unknownProtocolRejectsExplicitConvenienceOverridesWithoutMutatingTheSession() throws Exception {
        final Session session = configuredSession("smtp", false);
        session.getProperties().setProperty("mail.transport.protocol", "custom-smtp");
        final Properties original = new Properties();
        original.putAll(session.getProperties());
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(1000)).build()).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("withSessionTimeout")
                .hasMessageContaining("mail.transport.protocol").hasMessageContaining("withProperty");
        assertThat(session.getProperties()).isEqualTo(original);
    }

    @Test
    void unknownProtocolWithoutOverridesKeepsItsSettingsAndDoesNotClaimASmtpTimeout() throws Exception {
        final Session session = configuredSession("smtp", false);
        session.getProperties().setProperty("mail.transport.protocol", "custom-smtp");
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        }
    }

    @Test
    void mandatorySettingsStillApplyWithoutImportingUnrelatedDefaults() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("locked.defaults.sessiontimeoutmillis", "1500"));
        for (final boolean marked : List.of(false, true)) {
            final Session session = configuredSession("smtp", marked);
            try (Mailer mailer = factory.mailerBuilder(session).buildMailer()) {
                assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1500));
                assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("1500");
                assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("1500");
                assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("1500");
                assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("private.example.test");
            }
        }
    }

    @Test
    void aTrustedHostLockDoesNotImportAnUnrelatedFactoryTrustAllDefault() throws Exception {
        final SimpleJavaMail factory = factory(Map.of("defaults.trustallhosts", "true", "locked.defaults.trustedhosts", "required.example.test"));
        final Session session = configuredSession("smtp", false);
        try (Mailer mailer = factory.mailerBuilder(session).buildMailer()) {
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("required.example.test");
            assertThat(mailer.getOperationalConfig().isTrustAllSSLHost()).isFalse();
            assertThat(mailer.getOperationalConfig().getSslHostsToTrust()).containsExactly("required.example.test");
        }
    }

    @Test
    void conflictingExplicitOrRawLockedChoicesFailBeforeSessionMutation() {
        final Session session = configuredSession("smtp", false);
        final Properties original = new Properties();
        original.putAll(session.getProperties());
        assertThatThrownBy(() -> factory(Map.of("locked.defaults.sessiontimeoutmillis", "1500"))
                .mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(2000)).build()).buildMailer())
                .hasMessageContaining("simplejavamail.locked.defaults.sessiontimeoutmillis");
        assertThat(session.getProperties()).isEqualTo(original);
        assertThatThrownBy(() -> factory(Map.of("locked.extraproperties.mail.smtp.timeout", "1500"))
                .mailerBuilder(session).withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(2000)).build()).buildMailer())
                .hasMessageContaining("simplejavamail.locked.extraproperties.mail.smtp.timeout");
        assertThat(session.getProperties()).isEqualTo(original);
    }

    @Test
    void reusingABuilderDoesNotReuseTheFirstSessionsTimeoutSnapshot() throws Exception {
        final Session first = Session.getInstance(timeoutProperties("1000", "1000", "1000"));
        final Session second = configuredSession("smtp", false);
        final MailerFromSessionBuilder<?> builder = factory(Map.of()).mailerBuilder(first);
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1000));
        }
        try (Mailer mailer = builder.usingSession(second).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isNull();
            assertThat(second.getProperty("mail.smtp.timeout")).isEqualTo("5000");
        }
    }

    @Test
    void aFormerlyManagedSessionKeepsItsSettingsAfterSuccessfulOwnerShutdown() throws Exception {
        final Session session;
        try (Mailer original = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25)
                .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(1000)).build()).withTlsConfig(TlsConfig.builder().trustingSSLHosts("private.example.test").build()).buildMailer()) {
            session = original.getSession();
        }
        try (Mailer replacement = factory(Map.of("defaults.sessiontimeoutmillis", "12000")).mailerBuilder(session).buildMailer()) {
            assertThat(replacement.getSession()).isSameAs(session);
            assertThat(replacement.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(1000));
            assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("1000");
            assertThat(session.getProperty("mail.smtp.ssl.trust")).isEqualTo("private.example.test");
        }
    }

    @Test
    void libraryOwnedSessionsKeepTheirConfiguredAndBuiltInConnectionDefaults() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.test", 25).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT);
            assertThat(mailer.getSession().getProperty("mail.smtp.timeout")).isEqualTo(String.valueOf(MailerGenericBuilder.DEFAULT_SESSION_TIMEOUT.toMillis()));
        }
        try (Mailer mailer = factory(Map.of("defaults.sessiontimeoutmillis", "12000"))
                .mailerBuilder().withSMTPServer("smtp.example.test", 25).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ofMillis(12000));
            assertThat(mailer.getSession().getProperty("mail.smtp.timeout")).isEqualTo("12000");
        }
    }

    private static SimpleJavaMail factory(final Map<String, String> settings) {
        final Properties properties = new Properties();
        // These controls exercise deliberate replacement of supplied connection baselines.
        properties.setProperty("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES");
        settings.forEach((key, value) -> properties.put("simplejavamail." + key, value));
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
    }

    private static Session configuredSession(final String protocol, final boolean marked) {
        final Properties properties = marked
                ? ("smtps".equals(protocol) ? TransportStrategy.SMTPS : TransportStrategy.SMTP).generateProperties() : new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        final String prefix = "mail." + protocol + ".";
        properties.setProperty(prefix + "host", "caller.example.test");
        properties.setProperty(prefix + "connectiontimeout", "6000");
        properties.setProperty(prefix + "timeout", "5000");
        properties.setProperty(prefix + "writetimeout", "7000");
        properties.setProperty(prefix + "ssl.trust", "private.example.test");
        properties.setProperty(prefix + "ssl.checkserveridentity", "false");
        properties.setProperty(prefix + "localaddress", "192.0.2.1");
        properties.setProperty(prefix + "localport", "8000");
        properties.setProperty(prefix + "localhost", "caller.example.test");
        return Session.getInstance(properties);
    }

    private static Properties timeoutProperties(final String connect, final String read, final String write) {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", connect);
        properties.setProperty("mail.smtp.timeout", read);
        properties.setProperty("mail.smtp.writetimeout", write);
        return properties;
    }
}
