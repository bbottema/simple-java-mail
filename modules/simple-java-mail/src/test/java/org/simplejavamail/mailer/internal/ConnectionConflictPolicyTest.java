package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import jakarta.mail.Session;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import testutil.ConfigLoaderTestHelper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;

class ConnectionConflictPolicyTest {

    @Test
    void conflictingRawAndTypedProxyHostsFailBeforeConstruction() {
        assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("smtp.example.org", 25)
                .withProperty("mail.smtp.socks.host", "old-proxy.example.org")
                .withProxyConfig(SocksProxyConfig.builder().withHost("chosen-proxy.example.org").withPort(1080).build())
                .buildMailer())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mail.smtp.socks.host")
                .hasMessageContaining("proxy");
    }

    @Test
    void ordinarySourcePrecedenceIsResolvedBeforeComparingDeclarations() throws Exception {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of("simplejavamail.proxy.host", "old.example.org"))
                .withMap(Map.of("simplejavamail.proxy.host", "new.example.org")).load();
        try (Mailer mailer = SimpleJavaMail.withConfig(config).mailerBuilder().withSMTPServer("smtp.example.org", 25).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("new.example.org");
            assertThat(mailer.getOperationalConfig().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.REJECT_CONFLICTS);
        }
    }

    @Test
    void policyInspectionIsPassiveAndRestorationResolvesCapturedMode() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25);
        assertThat(builder.getConnectionConflictPolicy()).isNull();
        assertThat(builder.resetConnectionConflictPolicy().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.REJECT_CONFLICTS);
        assertThatThrownBy(() -> builder.withConnectionConflictPolicy(null)).isInstanceOf(NullPointerException.class);
        assertThat(builder.getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.REJECT_CONFLICTS);
        assertThat(builder.useConfiguredConnectionConflictPolicy().getConnectionConflictPolicy()).isNull();
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getOperationalConfig().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
        }
    }

    @Test
    void explicitOverrideChoosesTypedProxyMembersInBothCallOrders() throws Exception {
        for (boolean typedLast : new boolean[] {true, false}) {
            final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            final SocksProxyConfig typed = SocksProxyConfig.builder().withHost("typed.example.org").build();
            if (typedLast) {
                builder.withProperty("mail.smtp.socks.host", "raw.example.org").withProperty("mail.smtp.socks.port", "2345").withProxyConfig(typed);
            } else {
                builder.withProxyConfig(typed).withProperty("mail.smtp.socks.host", "raw.example.org").withProperty("mail.smtp.socks.port", "2345");
            }
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("typed.example.org");
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(2345);
                assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("typed.example.org");
                assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("2345");
            }
        }
    }

    @Test
    void parsedNumbersAndBooleansCanAgreeAcrossConfigurationMechanisms() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withProperty("mail.smtp.timeout", "00123")
                .withProperty("mail.smtp.ssl.checkserveridentity", "TRUE")
                .withConnectionConfig(ConnectionConfig.builder().withReadTimeout(Duration.ofMillis(123)).build())
                .withTlsConfig(TlsConfig.builder().verifyingServerIdentity(true).build()).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(123));
            assertThat(mailer.getOperationalConfig().isVerifyingServerIdentity()).isTrue();
        }
    }

    @Test
    void implicitDefaultsYieldToRawValuesWithoutAConflict() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withProperty("mail.smtp.timeout", "2345").withProperty("mail.smtp.ssl.checkserveridentity", "false").buildMailer()) {
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(2345));
            assertThat(mailer.getOperationalConfig().isVerifyingServerIdentity()).isFalse();
        }
    }

    @Test
    void strictComparesCapturedInputsWithSessionAndJavaWithoutMutatingTheSession() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "session.example.org");
        final Session session = Session.getInstance(properties);
        final Properties before = (Properties) properties.clone();
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.smtp.host", "factory.example.org")).mailerBuilder(session).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.smtp.host")
                .hasMessageContaining("supplied Session");
        assertThat(properties).isEqualTo(before);
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.smtp.host", "factory.example.org")).mailerBuilder()
                .withSMTPServerHost("java.example.org").buildMailer()).hasMessageContaining("withSMTPServerHost");
    }

    @Test
    void suppliedSessionGapsStayAbsentInBothModesDespiteConfiguredDefaults() throws Exception {
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            final Session session = Session.getInstance(new Properties());
            try (Mailer mailer = factory(Map.of("simplejavamail.defaults.sessiontimeoutmillis", "1234",
                    "simplejavamail.smtp.host", "factory.example.org", "simplejavamail.proxy.host", "factory-proxy.example.org"))
                    .mailerBuilder(session).withConnectionConflictPolicy(policy).buildMailer()) {
                assertThat(session.getProperty("mail.smtp.host")).isNull();
                assertThat(session.getProperty("mail.smtp.timeout")).isNull();
                assertThat(session.getProperty("mail.smtp.socks.host")).isNull();
                assertThat(mailer.getOperationalConfig().getReadTimeout()).isNull();
            }
        }
    }

    @Test
    void finalSettersAndReplacementGroupsDiscardEarlierLocalChoices() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder().withSMTPServer("old.example.org", 25)
                .withSMTPServerHost("smtp.example.org")
                .withProperty("mail.smtp.socks.host", "final-proxy.example.org")
                .withProxyConfig(SocksProxyConfig.builder().withHost("discarded.example.org").build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("final-proxy.example.org").build());
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.host")).isEqualTo("smtp.example.org");
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("final-proxy.example.org");
        }
        try (Mailer mailer = builder.withProxyConfig(SocksProxyConfig.builder().build()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("final-proxy.example.org");
        }
    }

    @Test
    void explicitRemovalProtectsImportedValuesButLocalOnlyEditsRemainPossible() throws Exception {
        final MailerRegularBuilder<?> imported = factory(Map.of("simplejavamail.smtp.localaddress", "192.0.2.1"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().build());
        assertThatThrownBy(imported::buildMailer).hasMessageContaining("simplejavamail.smtp.localaddress");
        try (Mailer mailer = imported.withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localaddress")).isNull();
        }
        try (Mailer mailer = imported.resetConnectionConflictPolicy().useConfiguredConnectionConfig().buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localaddress")).isEqualTo("192.0.2.1");
        }
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withConnectionConfig(ConnectionConfig.builder().withLocalBindAddress("192.0.2.1").build())
                .withConnectionConfig(ConnectionConfig.builder().clearLocalBindAddress().build()).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localaddress")).isNull();
        }
    }

    @Test
    void removingCapturedRawPropertyIsVisibleToStrictModeAndPermissiveModeSuppressesIt() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.extraproperties.mail.smtp.localhost", "captured.example.org"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25).withProperty("mail.smtp.localhost", null);
        assertThatThrownBy(builder::buildMailer).hasMessageContaining("simplejavamail.extraproperties.mail.smtp.localhost");
        try (Mailer mailer = builder.withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.localhost")).isNull();
        }
    }

    @Test
    void rawEndpointsComposeWithTypedProxyCredentialsForSmtpAndSmtps() throws Exception {
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            final String prefix = strategy == TransportStrategy.SMTP ? "mail.smtp." : "mail.smtps.";
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", strategy.getDefaultServerPort())
                    .withTransportStrategy(strategy)
                    .withProperty(prefix + "socks.host", "remote.example.org").withProperty(prefix + "socks.port", "2345")
                    .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("secret").build()).buildMailer()) {
                assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("remote.example.org");
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(2345);
                assertThat(mailer.getProxyConfig().requiresAuthentication()).isTrue();
                assertThat(mailer.getSession().getProperty(prefix + "socks.host")).isEqualTo(InetAddress.getLoopbackAddress().getHostAddress());
                assertThat(mailer.getSession().getProperty(prefix + "socks.port")).isEqualTo("0");
            }
        }
    }

    @Test
    void locksAndMandatoryTlsRemainRequirementsInPermissiveMode() {
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.locked.proxy.host", "locked.example.org"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperty("mail.smtp.socks.host", "other.example.org").buildMailer())
                .hasMessageContaining("locked.proxy.host");
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.locked.connection.conflictpolicy", "REJECT_CONFLICTS"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer())
                .hasMessageContaining("locked.connection.conflictpolicy");
        assertThatThrownBy(() -> factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 587)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withTransportStrategy(TransportStrategy.SMTP_TLS).withProperty("mail.smtp.starttls.required", "false").buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("starttls.required");
    }

    @Test
    void conflictingCredentialsNeverAppearInErrors() {
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.smtp.username", "user", "simplejavamail.smtp.password", "captured-secret"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25).withSMTPServerPassword("local-secret").buildMailer())
                .hasMessageContaining("simplejavamail.smtp.password").hasMessageNotContaining("captured-secret").hasMessageNotContaining("local-secret");
    }

    @Test
    void localOnlyRawRemovalDoesNotBecomeAConflictingDeclaration() throws Exception {
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                    .withConnectionConflictPolicy(policy)
                    .withProperty("mail.smtp.host", "discarded.example.org").withProperty("mail.smtp.host", null).buildMailer()) {
                assertThat(mailer.getSession().getProperty("mail.smtp.host")).isEqualTo("smtp.example.org");
            }
        }
    }

    @Test
    void oauth2TypedAndRawTokensAreOneLogicalCredentialInBothModes() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder()
                .withSMTPServer("smtp.example.org", 587, "user", "typed-secret")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2)
                .withProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY, "raw-secret");
        assertThatThrownBy(builder::buildMailer).hasMessageContaining(TransportStrategy.OAUTH2_TOKEN_PROPERTY)
                .hasMessageNotContaining("typed-secret").hasMessageNotContaining("raw-secret");
        try (Mailer mailer = builder.withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
            assertThat(mailer.getSession().getProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY)).isEqualTo("typed-secret");
        }
    }

    @Test
    void authenticatedRouteCanSatisfyALiteralLockedLoopbackEndpoint() throws Exception {
        final String loopback = InetAddress.getLoopbackAddress().getHostAddress();
        try (Mailer mailer = factory(Map.of("simplejavamail.locked.extraproperties.mail.smtp.socks.host", loopback,
                "simplejavamail.locked.extraproperties.mail.smtp.socks.port", "1089"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withProxyConfig(SocksProxyConfig.builder().withHost("remote.example.org").withPort(2345)
                        .withUsername("user").withPassword("secret").withBridgePort(1089).build()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("remote.example.org");
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(2345);
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo(loopback);
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("1089");
        }
    }

    @Test
    void missingRequestedAuthenticationModuleFailsBeforeChangingSuppliedSession() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.socks.host", "remote.example.org");
        final Session session = Session.getInstance(properties);
        final Properties original = (Properties) properties.clone();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadAuthenticatedSocksModule).thenThrow(new IllegalStateException("missing authenticated SOCKS module"));
            assertThatThrownBy(() -> factory(Map.of()).mailerBuilder(session)
                    .withProxyConfig(SocksProxyConfig.builder().withUsername("user").withPassword("secret").build()).buildMailer())
                    .hasMessageContaining("missing authenticated SOCKS module");
            assertThat(properties).isEqualTo(original);
        }
    }

    @Test
    void customMailerDoesNotActivateConfiguredSmtpOrProxyDeclarations() throws Exception {
        try (Mailer mailer = factory(Map.of("simplejavamail.proxy.host", "factory-proxy.example.org"))
                .mailerBuilder().withSMTPServer("smtp.example.org", 25)
                .withProperty("mail.smtp.socks.host", "different.example.org")
                .withProxyConfig(SocksProxyConfig.builder().withHost("typed.example.org").withUsername("incomplete-credentials").build())
                .withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertThat(mailer.getOperationalConfig().getCustomMailer()).isNotNull();
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("different.example.org");
        }
        final Properties customProperties = new Properties();
        customProperties.setProperty("mail.transport.protocol", "custom-provider");
        customProperties.setProperty("mail.smtp.socks.host", "supplied.example.org");
        final Session customSession = Session.getInstance(customProperties);
        try (Mailer ignored = factory(Map.of()).mailerBuilder(customSession)
                .withProxyConfig(SocksProxyConfig.builder().withHost("inactive.example.org").withUsername("incomplete-credentials").build())
                .withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertThat(customSession.getProperty("mail.transport.protocol")).isEqualTo("custom-provider");
            assertThat(customSession.getProperty("mail.smtp.socks.host")).isEqualTo("supplied.example.org");
        }
    }

    @Test
    void bridgePortRefreshUsesTheActualProtocolOfAnUnmarkedSuppliedSession() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtps");
        properties.setProperty("mail.smtps.socks.port", "0");
        final Session session = Session.getInstance(properties);
        final AnonymousSocks5Server bridge = mock(AnonymousSocks5Server.class);
        when(bridge.getLocalPort()).thenReturn(1089);
        try (SmtpProxyScope scope = new SmtpProxyScope(new AtomicInteger(), bridge, session)) {
            scope.start();
            assertThat(session.getProperty("mail.smtps.socks.port")).isEqualTo("1089");
            assertThat(session.getProperty("mail.smtp.socks.port")).isNull();
        }
    }

    @Test
    void explicitPasswordCannotBeSilentlyDisabledThroughRawAuthenticationProperty() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder()
                .withSMTPServer("smtp.example.org", 25, "user", "typed-secret")
                .withProperty("mail.smtp.auth", "false");
        assertThatThrownBy(builder::buildMailer).hasMessageContaining("mail.smtp.auth").hasMessageNotContaining("typed-secret");
        try (Mailer mailer = builder.withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.auth")).isEqualTo("true");
        }
    }

    @Test
    void clearedServerPortUsesTheSelectedStrategyFallbackInTheActualSession() throws Exception {
        try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 2525)
                .withTransportStrategy(TransportStrategy.SMTP_TLS).clearSMTPServerPort().buildMailer()) {
            assertThat(mailer.getServerConfig().getPort()).isEqualTo(587);
            assertThat(mailer.getSession().getProperty("mail.smtp.port")).isEqualTo("587");
        }
    }

    @Test
    void oauth2ValidationUsesTheFinalTokenAfterExplicitRemoval() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 587, "user")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2)
                .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                .withProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY, "raw-secret").clearSMTPServerPassword();
        assertThatThrownBy(builder::buildMailer).isInstanceOf(MailerException.class).hasMessageContaining("OAuth2");
        final AtomicInteger tokenRequests = new AtomicInteger();
        try (Mailer mailer = builder.withOAuth2AccessTokenProvider(() -> {
            tokenRequests.incrementAndGet();
            return "runtime-token";
        }).buildMailer()) {
            assertThat(mailer.getSession().getProperty(TransportStrategy.OAUTH2_TOKEN_PROPERTY)).isNull();
            assertThat(tokenRequests).hasValue(0);
        }
    }

    @Test
    void rawProtocolSelectsPropertyNamesWhenJavaStrategyIsOmitted() throws Exception {
        for (boolean inheritedStrategy : new boolean[] {false, true}) {
            final MailerRegularBuilder<?> builder = factory(inheritedStrategy
                    ? Map.of("simplejavamail.transportstrategy", "SMTP") : Map.of()).mailerBuilder()
                    .withSMTPServerHost("smtp.example.org")
                    .withConnectionConflictPolicy(inheritedStrategy ? ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES : ConnectionConflictPolicy.REJECT_CONFLICTS)
                    .withProperty("mail.transport.protocol", "smtps")
                    .withProperty("mail.smtps.socks.host", "proxy.example.org").withProperty("mail.smtps.socks.port", "2345");
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getSession().getProperty("mail.smtps.host")).isEqualTo("smtp.example.org");
                assertThat(mailer.getSession().getProperty("mail.smtps.port")).isEqualTo("465");
                assertThat(mailer.getTransportStrategy()).isEqualTo(TransportStrategy.SMTPS);
                assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example.org");
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(2345);
            }
        }
    }

    @Test
    void incompatiblePhysicalProxyLockFailsBeforeAllocatingOwnedExecutorOrMutatingSession() {
        final Properties properties = new Properties();
        final Session session = Session.getInstance(properties);
        final SimpleJavaMail configured = factory(Map.of("simplejavamail.locked.extraproperties.mail.smtp.socks.host", "locked.example.org"));
        try (MockedConstruction<MailSendExecutor> executors = mockConstruction(MailSendExecutor.class)) {
            assertThatThrownBy(() -> configured.mailerBuilder(session)
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES)
                    .withProxyConfig(SocksProxyConfig.builder().withHost("different.example.org").withPort(2345).build()).buildMailer())
                    .hasMessageContaining("locked.extraproperties.mail.smtp.socks.host");
            assertThat(executors.constructed()).isEmpty();
            assertThat(properties).isEmpty();
        }
    }

    @Test
    void permissiveModeStillRejectsInvalidProxyValues() {
        for (SocksProxyConfig choices : new SocksProxyConfig[] {
                SocksProxyConfig.builder().withHost("").withPort(1080).build(),
                SocksProxyConfig.builder().withHost("proxy.example.org").withUsername("user").withPassword("").build()}) {
            assertThatThrownBy(() -> {
                try (Mailer ignored = factory(Map.of()).mailerBuilder().withSMTPServer("smtp.example.org", 25)
                        .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES).withProxyConfig(choices).buildMailer()) {
                    // Close an unexpectedly accepted configuration as well.
                }
            }).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static SimpleJavaMail factory(final Map<String, ?> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
    }
}
