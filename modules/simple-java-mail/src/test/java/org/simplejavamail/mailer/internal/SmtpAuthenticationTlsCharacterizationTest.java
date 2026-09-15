package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import testutil.ConfigLoaderTestHelper;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static jakarta.mail.Message.RecipientType.TO;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;

/**
 * Records the authentication/TLS contract, including the construction-time mandatory STARTTLS guard added for 10.0.0.
 * Ordinary sends talk only to a loopback peer with fake credentials; a successful probe is never used as a send preflight.
 * Some passing cases deliberately expose permissive behavior, not a recommendation that callers should use it.
 */
@Timeout(30)
class SmtpAuthenticationTlsCharacterizationTest {

    private static final String USERNAME = "characterization-user";
    private static final String PASSWORD = "fake-characterization-password";
    private static final String TOKEN = "fake-characterization-token";
    private static final String AUTH_LOGIN = "250-localhost\r\n250 AUTH LOGIN";
    private static final String STARTTLS = "250-localhost\r\n250-STARTTLS\r\n250 AUTH LOGIN PLAIN XOAUTH2";
    private static final String NO_AUTH = "250 localhost";

    @Test
    void credentialsDoNotChangeTheDefaultFromOpportunisticSmtp() throws Exception {
        try (Mailer mailer = builder(12345).withSMTPServerUsername(USERNAME).withSMTPServerPassword(PASSWORD).buildMailer()) {
            assertThat(mailer.getTransportStrategy()).isEqualTo(SMTP);
            assertThat(mailer.getSession().getProperties())
                    .containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.starttls.required", "false")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .doesNotContainKey("mail.smtp.ssl.trust");
        }
    }

    @Test
    void unauthenticatedLocalRelayDoesNotNeedTlsOrAuth() throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet(NO_AUTH);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = builder(server.port()).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PLAIN"})
    void defaultSmtpSendsPasswordInPlaintextWhenStartTlsIsNotAdvertised(final String mechanism) throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet("250-localhost\r\n250 AUTH " + mechanism);
            authenticate(peer, mechanism, PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = builder(server.port()).withSMTPServerUsername(USERNAME).withSMTPServerPassword(PASSWORD).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @Test
    void explicitlyDisablingOpportunisticTlsSkipsAnAdvertisedUpgrade() throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet(STARTTLS);
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = passwordBuilder(server.port(), SMTP).withOpportunisticTLS(false).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS"})
    void refusedStartTlsStopsEvenAnOpportunisticSendBeforeAuth(final TransportStrategy strategy) throws Exception {
        try (Peer server = new Peer(peer -> {
            beginStartTlsRefusal(peer);
            peer.expectClosed();
        }); Mailer mailer = passwordBuilder(server.port(), strategy).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("454 TLS temporarily unavailable");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiredStartTlsWithoutAdvertisementFailsEvenWhenEnableIsFalse(final boolean enable) throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet(AUTH_LOGIN);
            peer.expectClosed();
        }); Mailer mailer = passwordBuilder(server.port(), SMTP_TLS)
                .withProperty("mail.smtp.starttls.enable", Boolean.toString(enable)).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("STARTTLS is required");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS", "SMTPS"})
    void successfulTlsPrecedesPasswordAuthenticationAndSubmission(final TransportStrategy strategy) throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, strategy, AUTH_LOGIN);
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = tlsPasswordBuilder(server.port(), strategy).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS", "SMTPS"})
    void certificateTrustFailureStopsBeforeAnyAuthOrMail(final TransportStrategy strategy) throws Exception {
        final KeyStore emptyTrustStore = KeyStore.getInstance("JKS");
        emptyTrustStore.load(null, null);
        try (Peer server = new Peer(peer -> {
            if (strategy != SMTPS) {
                beginStartTls(peer);
            }
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = passwordBuilder(server.port(), strategy)
                .withCustomSSLFactoryInstance(SmtpCapabilityProbeCharacterizationTest.tlsContext(emptyTrustStore).getSocketFactory())
                .buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("trustAnchors parameter must be non-empty");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS"})
    void tlsProtocolMismatchDoesNotFallBackToPlaintext(final TransportStrategy strategy) throws Exception {
        try (Peer server = new Peer(peer -> {
            beginStartTls(peer);
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = passwordBuilder(server.port(), strategy).withCustomSSLFactoryInstance(trustedFactory())
                .withProperty("mail.smtp.ssl.protocols", "TLSv1.3").buildMailer()) {
            // The loopback TLS fixture deliberately only offers TLSv1.2.
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("SSLHandshakeException");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostnameMismatchBlocksAuthUnlessIdentityCheckingIsExplicitlyDisabled(final boolean verifyIdentity) throws Exception {
        try (Peer server = new Peer(peer -> {
            beginStartTls(peer);
            if (verifyIdentity) {
                assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
            } else {
                peer.upgradeToTls();
                peer.expect("EHLO probe.example.test");
                peer.reply(AUTH_LOGIN);
                authenticate(peer, "LOGIN", PASSWORD);
                acceptMessageAndQuit(peer);
            }
        }); Mailer mailer = passwordBuilder(server.port(), SMTP_TLS).withCustomSSLFactoryInstance(wrongHostnameFactory())
                .verifyingServerIdentity(verifyIdentity).buildMailer()) {
            if (verifyIdentity) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                        .hasStackTraceContaining("wrong-host.invalid");
            } else {
                assertAccepted(mailer);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitTrustExceptionsLeaveIdentityCheckingEnabled(final boolean allHosts) throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_TLS, AUTH_LOGIN);
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = passwordBuilder(server.port(), SMTP_TLS)
                .trustingSSLHosts(allHosts ? new String[0] : new String[]{"localhost"})
                .trustingAllHosts(allHosts).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo(allHosts ? "*" : "localhost");
            assertAccepted(mailer);
        }
    }

    @Test
    void rawTrustAndIdentityPropertiesAreOverwrittenByTheBuilderSettings() throws Exception {
        try (Mailer mailer = passwordBuilder(12345, SMTP_TLS)
                .withProperty("mail.smtp.ssl.trust", "*")
                .withProperty("mail.smtp.ssl.checkserveridentity", "false").buildMailer()) {
            assertThat(mailer.getSession().getProperties()).doesNotContainKey("mail.smtp.ssl.trust")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
        }
    }

    @Test
    void rawRequiredFalseIsRejectedBeforePasswordAuthentication() {
        assertThatThrownBy(() -> passwordBuilder(12345, SMTP_TLS)
                .withProperty("mail.smtp.starttls.required", "false").buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("SMTP_TLS requires STARTTLS");
    }

    @Test
    void conflictingPropertiesLoadedFromConfigurationAreRejectedToo() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.transportstrategy", "SMTP_TLS");
        properties.setProperty("simplejavamail.extraproperties.mail.smtp.starttls.required", "false");
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties("application.properties", properties).load());
        assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServer("localhost", 12345, USERNAME, PASSWORD)
                .withConnectionPoolCoreSize(0).buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("SMTP_TLS requires STARTTLS");
    }

    @Test
    void smtpsDoesNotBecomePlaintextBySettingItsSslEnablePropertyFalse() throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTPS, AUTH_LOGIN);
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = tlsPasswordBuilder(server.port(), SMTPS)
                .withProperty("mail.smtps.ssl.enable", "false").buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oauthTokenIsResolvedButNotTransmittedWhenRequiredTlsIsUnavailable(final boolean refreshed) throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        try (Peer server = new Peer(peer -> {
            peer.greet("250-localhost\r\n250 AUTH XOAUTH2");
            peer.expectClosed();
        }); Mailer mailer = oauthBuilder(server.port(), refreshed, resolutions).buildMailer()) {
            assertThat(resolutions).hasValue(0);
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("STARTTLS is required");
            assertThat(resolutions).hasValue(refreshed ? 1 : 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oauthTokensAreNotTransmittedWhenStartTlsIsRefused(final boolean refreshed) throws Exception {
        try (Peer server = new Peer(peer -> {
            beginStartTlsRefusal(peer);
            peer.expectClosed();
        }); Mailer mailer = oauthBuilder(server.port(), refreshed, new AtomicInteger()).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("454 TLS temporarily unavailable");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oauthTokensAreNotTransmittedWhenCertificateTrustFails(final boolean refreshed) throws Exception {
        final KeyStore emptyTrustStore = KeyStore.getInstance("JKS");
        emptyTrustStore.load(null, null);
        try (Peer server = new Peer(peer -> {
            beginStartTls(peer);
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = oauthBuilder(server.port(), refreshed, new AtomicInteger())
                .withCustomSSLFactoryInstance(SmtpCapabilityProbeCharacterizationTest.tlsContext(emptyTrustStore).getSocketFactory())
                .buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("trustAnchors parameter must be non-empty");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fixedAndRefreshedOauthTokensAuthenticateAfterTls(final boolean refreshed) throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_OAUTH2, "250-localhost\r\n250 AUTH XOAUTH2");
            authenticate(peer, "XOAUTH2", TOKEN);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = oauthBuilder(server.port(), refreshed, resolutions)
                .withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertThat(resolutions).hasValue(0);
            assertAccepted(mailer);
            assertThat(resolutions).hasValue(refreshed ? 1 : 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawRequiredFalseIsRejectedBeforeResolvingOrTransmittingOauthTokens(final boolean refreshed) {
        final AtomicInteger resolutions = new AtomicInteger();
        assertThatThrownBy(() -> oauthBuilder(12345, refreshed, resolutions)
                .withProperty("mail.smtp.starttls.required", "false").buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("SMTP_OAUTH2 requires STARTTLS");
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void pooledSyncAndAsyncSendsShareOneAuthenticatedTlsConnectionWithoutRefreshingItsToken() throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_OAUTH2, "250-localhost\r\n250 AUTH XOAUTH2");
            authenticate(peer, "XOAUTH2", TOKEN);
            acceptMessage(peer);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = oauthBuilder(server.port(), true, resolutions).withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertAccepted(mailer);
            assertThat(mailer.async().sendMail(email()).getCompletion().get(10, SECONDS).getStatus()).isEqualTo(ACCEPTED);
            assertThat(resolutions).hasValue(1);
        }
    }

    @Test
    void reconnectingAPooledConnectionResolvesAFreshTokenAndAuthenticatesOnlyAfterTls() throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();
        try (Peer server = new Peer(2, peer -> {
            establishTls(peer, SMTP_OAUTH2, "250-localhost\r\n250 AUTH XOAUTH2");
            authenticate(peer, "XOAUTH2", TOKEN + "-" + connections.incrementAndGet());
            acceptMessage(peer);
            // Closing without answering another NOOP forces the next claim to reconnect instead of reusing this transport.
        }); Mailer mailer = builder(server.port()).withTransportStrategy(SMTP_OAUTH2).withSMTPServerUsername(USERNAME)
                .withOAuth2AccessTokenProvider(() -> TOKEN + "-" + resolutions.incrementAndGet())
                .withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertAccepted(mailer);
            assertAccepted(mailer);
            assertThat(resolutions).hasValue(2);
            assertThat(connections).hasValue(2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS"})
    void asyncSendingKeepsTheSameTlsRequirement(final TransportStrategy strategy) throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet(AUTH_LOGIN);
            if (strategy == SMTP_TLS) {
                peer.expectClosed();
            } else {
                authenticate(peer, "LOGIN", PASSWORD);
                acceptMessageAndQuit(peer);
            }
        }); Mailer mailer = passwordBuilder(server.port(), strategy).buildMailer()) {
            if (strategy == SMTP_TLS) {
                assertThatThrownBy(() -> mailer.async().sendMail(email()).getCompletion().get(10, SECONDS))
                        .hasStackTraceContaining("STARTTLS is required");
            } else {
                assertThat(mailer.async().sendMail(email()).getCompletion().get(10, SECONDS).getStatus()).isEqualTo(ACCEPTED);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTP_TLS"})
    void sendingWithoutTheBatchModuleKeepsTheSameTlsRequirement(final TransportStrategy strategy) throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Peer server = new Peer(peer -> {
                peer.greet(AUTH_LOGIN);
                if (strategy == SMTP_TLS) {
                    peer.expectClosed();
                } else {
                    authenticate(peer, "LOGIN", PASSWORD);
                    acceptMessageAndQuit(peer);
                }
            }); Mailer mailer = passwordBuilder(server.port(), strategy).buildMailer()) {
                if (strategy == SMTP_TLS) {
                    assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                            .hasStackTraceContaining("STARTTLS is required");
                } else {
                    assertAccepted(mailer);
                }
            }
        }
    }

    @Test
    void oauthBearerIsNotABuiltInAngusMechanism() throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_OAUTH2, "250-localhost\r\n250 AUTH OAUTHBEARER");
            peer.expectClosed();
        }); Mailer mailer = oauthBuilder(server.port(), false, new AtomicInteger())
                .withCustomSSLFactoryInstance(trustedFactory())
                .withProperty("mail.smtp.auth.mechanisms", "OAUTHBEARER").buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("No authentication mechanisms supported");
        }
    }

    @Test
    void successfulPostTlsEhloReplacesTheAuthenticationMechanismList() throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_TLS, "250-localhost\r\n250 AUTH PLAIN");
            authenticate(peer, "PLAIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = passwordBuilder(server.port(), SMTP_TLS).withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredCredentialsDoNotRequireTheServerToAdvertiseAuth(final boolean tls) throws Exception {
        try (Peer server = new Peer(peer -> {
            if (tls) {
                establishTls(peer, SMTP_TLS, NO_AUTH);
            } else {
                peer.greet(NO_AUTH);
            }
            acceptMessageAndQuit(peer);
        }); Mailer mailer = passwordBuilder(server.port(), tls ? SMTP_TLS : SMTP)
                .withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.auth")).isEqualTo("true");
            assertAccepted(mailer);
        }
    }

    @Test
    void failedPostTlsEhloStillUsesThePreTlsAuthListButNotAPlaintextSocket() throws Exception {
        try (Peer server = new Peer(peer -> {
            establishTls(peer, SMTP_TLS, "500 EHLO unavailable after TLS");
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        }); Mailer mailer = passwordBuilder(server.port(), SMTP_TLS).withCustomSSLFactoryInstance(trustedFactory()).buildMailer()) {
            assertAccepted(mailer);
        }
    }

    @Test
    void callerOwnedSessionKeepsItsOwnPlaintextAuthenticationConfiguration() throws Exception {
        try (Peer server = new Peer(peer -> {
            peer.greet(AUTH_LOGIN);
            authenticate(peer, "LOGIN", PASSWORD);
            acceptMessageAndQuit(peer);
        })) {
            final Properties properties = new Properties();
            properties.setProperty("mail.transport.protocol", "smtp");
            properties.setProperty("mail.smtp.host", "localhost");
            properties.setProperty("mail.smtp.port", Integer.toString(server.port()));
            properties.setProperty("mail.smtp.localhost", "probe.example.test");
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.timeout", "5000");
            final Session session = Session.getInstance(properties, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(USERNAME, PASSWORD);
                }
            });
            try (Mailer mailer = factory().mailerBuilder(session).withConnectionPoolCoreSize(0).buildMailer()) {
                assertThat(mailer.getTransportStrategy()).isNull();
                assertThat(session.getProperties()).doesNotContainKeys("mail.smtp.starttls.required", "mail.smtp.ssl.checkserveridentity");
                assertAccepted(mailer);
            }
        }
    }

    @Test
    void customMailerOwnsAuthenticationRatherThanUsingTheSmtpConnectionPath() throws Exception {
        final CustomMailer customMailer = mock(CustomMailer.class);
        final Email email = email();
        try (Mailer mailer = passwordBuilder(12345, SMTP_TLS).withCustomMailer(customMailer).buildMailer()) {
            mailer.sync().sendMail(email);
            verify(customMailer).sendMessage(same(mailer.getOperationalConfig()), same(mailer.getSession()), any(Email.class), any());
        }
    }

    private static MailerRegularBuilder<?> builder(final int port) {
        return factory().mailerBuilder().withSMTPServer("localhost", port)
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(5000)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(5000)
                .withProperty("mail.smtps.ssl.protocols", "TLSv1.2")
                .withProperty("mail.smtps.socketFactory.fallback", "false")
                .withProperty("mail.smtps.quitwait", "true");
    }

    private static MailerRegularBuilder<?> passwordBuilder(final int port, final TransportStrategy strategy) {
        return builder(port).withTransportStrategy(strategy).withSMTPServerUsername(USERNAME).withSMTPServerPassword(PASSWORD);
    }

    private static MailerRegularBuilder<?> tlsPasswordBuilder(final int port, final TransportStrategy strategy) throws Exception {
        final MailerRegularBuilder<?> builder = passwordBuilder(port, strategy);
        // Local mail shields can re-sign implicit TLS, so this ordering case uses a localhost-only trust exception.
        // Identity checking stays enabled; the separate empty-trust-store case must fail closed for every strategy.
        return strategy == SMTPS ? builder.trustingSSLHosts("localhost") : builder.withCustomSSLFactoryInstance(trustedFactory());
    }

    private static MailerRegularBuilder<?> oauthBuilder(final int port, final boolean refreshed, final AtomicInteger resolutions) {
        final MailerRegularBuilder<?> builder = builder(port).withTransportStrategy(SMTP_OAUTH2).withSMTPServerUsername(USERNAME);
        if (refreshed) {
            return builder.withOAuth2AccessTokenProvider(() -> {
                resolutions.incrementAndGet();
                return TOKEN;
            });
        }
        return builder.withSMTPServerPassword(TOKEN);
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static Email email() {
        return factory().emailBuilder().startingBlank().from("sender@example.test").withRecipients(new Recipient(null, "recipient@example.test", TO, null))
                .withSubject("Local authentication characterization").withPlainText("Synthetic loopback test only.").buildEmail();
    }

    private static void assertAccepted(final Mailer mailer) {
        assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
    }

    private static SSLSocketFactory trustedFactory() throws Exception {
        return (SSLSocketFactory) SmtpCapabilityProbeCharacterizationTest.session(true).getProperties().get("mail.smtp.ssl.socketFactory");
    }

    private static SSLSocketFactory wrongHostnameFactory() throws Exception {
        final SSLSocketFactory trusted = trustedFactory();
        final SSLSocketFactory wrongHostname = mock(SSLSocketFactory.class);
        when(wrongHostname.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean())).thenAnswer(invocation ->
                trusted.createSocket(invocation.getArgument(0), "wrong-host.invalid", invocation.getArgument(2), invocation.getArgument(3)));
        return wrongHostname;
    }

    private static void establishTls(final Conversation peer, final TransportStrategy strategy, final String encryptedEhlo) throws Exception {
        if (strategy == SMTPS) {
            peer.upgradeToTls();
            peer.greet(encryptedEhlo);
        } else {
            beginStartTls(peer);
            peer.upgradeToTls();
            peer.expect("EHLO probe.example.test");
            peer.reply(encryptedEhlo);
        }
    }

    private static void beginStartTls(final Conversation peer) throws IOException {
        peer.greet(STARTTLS);
        peer.expect("STARTTLS");
        peer.reply("220 begin TLS");
    }

    private static void beginStartTlsRefusal(final Conversation peer) throws IOException {
        peer.greet(STARTTLS);
        peer.expect("STARTTLS");
        peer.reply("454 TLS temporarily unavailable");
    }

    private static void authenticate(final Conversation peer, final String mechanism, final String secret) throws IOException {
        if ("LOGIN".equals(mechanism)) {
            peer.expect("AUTH LOGIN");
            peer.reply("334 VXNlcm5hbWU6");
            assertThat(decode(peer.readLine())).isEqualTo(USERNAME);
            peer.reply("334 UGFzc3dvcmQ6");
            assertThat(decode(peer.readLine())).isEqualTo(secret);
        } else {
            final String command = peer.readLine();
            assertThat(command).startsWith("AUTH " + mechanism + " ");
            final String payload = decode(command.substring(command.lastIndexOf(' ') + 1));
            assertThat(payload).isEqualTo("PLAIN".equals(mechanism)
                    ? USERNAME + "\0" + USERNAME + "\0" + secret
                    : "user=" + USERNAME + "\1auth=Bearer " + secret + "\1\1");
        }
        peer.reply("235 authenticated");
    }

    private static String decode(final String payload) {
        return new String(Base64.getDecoder().decode(payload), UTF_8);
    }

    private static void acceptMessageAndQuit(final Conversation peer) throws IOException {
        acceptMessage(peer);
        expectCommand(peer, "QUIT");
        peer.reply("221 bye");
        peer.expectClosed();
    }

    private static void acceptMessage(final Conversation peer) throws IOException {
        expectCommand(peer, "MAIL FROM:<sender@example.test>");
        peer.reply("250 sender accepted");
        peer.expect("RCPT TO:<recipient@example.test>");
        peer.reply("250 recipient accepted");
        peer.expect("DATA");
        peer.reply("354 send synthetic message");
        int lines = 0;
        String line = peer.readLine();
        while (line != null && !".".equals(line)) {
            assertThat(++lines).as("bounded synthetic message").isLessThan(100);
            line = peer.readLine();
        }
        assertThat(line).isEqualTo(".");
        assertThat(lines).isPositive();
        peer.reply("250 2.0.0 accepted synthetic message");
    }

    private static void expectCommand(final Conversation peer, final String expected) throws IOException {
        String command = peer.readLine();
        // Ordinary pooled sends may check a connection before borrowing it or closing it.
        while ("NOOP".equals(command)) {
            peer.reply("250 still connected");
            command = peer.readLine();
        }
        assertThat(command).isEqualTo(expected);
    }

    @FunctionalInterface
    private interface Script {
        void run(Conversation peer) throws Exception;
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> serving;

        private Peer(final Script script) throws IOException {
            this(1, script);
        }

        private Peer(final int connections, final Script script) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
            server.setSoTimeout(10000);
            serving = CompletableFuture.runAsync(() -> {
                try {
                    for (int connection = 0; connection < connections; connection++) {
                        try (Conversation peer = new Conversation(server.accept())) {
                            script.run(peer);
                        }
                    }
                } catch (Exception failure) {
                    throw new AssertionError("Scripted authentication peer failed", failure);
                }
            }, worker);
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            try {
                serving.get(15, SECONDS);
            } finally {
                server.close();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, SECONDS)).isTrue();
            }
        }
    }
}
