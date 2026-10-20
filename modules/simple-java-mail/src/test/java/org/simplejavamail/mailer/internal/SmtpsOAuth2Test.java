package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.internal.util.TransportConnectionHelper;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;
import testutil.smtp.SmtpTestTls;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;

/** Actual setup, authentication and submission contracts; all credentials and SMTP endpoints are synthetic/local. */
@Timeout(30)
class SmtpsOAuth2Test {
    private static final String USER = "oauth-user@example.test";
    private static final String TOKEN = "synthetic-oauth-token";
    private static final String EHLO = "250-localhost\r\n250 AUTH LOGIN PLAIN XOAUTH2";

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void fixedTokenUsesTheSelectedProtocolAndDefaultPort(final String name) throws Exception {
        final TransportStrategy strategy = TransportStrategy.valueOf(name);
        final String protocol = protocol(strategy);
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", null, USER, TOKEN)
                .withTransportStrategy(strategy).buildMailer()) {
            assertThat(mailer.getServerConfig().getPort()).isEqualTo(implicit(strategy) ? 465 : 587);
            assertThat(mailer.getSession().getProperties())
                    .containsEntry("mail.transport.protocol", protocol)
                    .containsEntry("mail." + protocol + ".user", USER)
                    .containsEntry("mail." + protocol + ".auth.mechanisms", "XOAUTH2")
                    .containsEntry("mail." + protocol + ".ssl.checkserveridentity", "true")
                    .containsEntry(OAUTH2_TOKEN_PROPERTY, TOKEN)
                    .doesNotContainKey("mail." + protocol + ".auth");
            assertThat(mailer.getSession().getProperties().stringPropertyNames())
                    .noneMatch(key -> key.startsWith(implicit(strategy) ? "mail.smtp." : "mail.smtps."));
            assertThat(TransportStrategy.findStrategyForSession(mailer.getSession())).isSameAs(strategy);
            if (implicit(strategy)) {
                assertThat(mailer.getSession().getProperties()).doesNotContainKeys("mail.smtps.starttls.enable", "mail.smtps.starttls.required");
            } else {
                assertThat(mailer.getSession().getProperty("mail.smtp.starttls.required")).isEqualTo("true");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void providerIsLazyAndNotRenderedInDiagnostics(final String name) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final OAuth2AccessTokenProvider provider = new OAuth2AccessTokenProvider() {
            public String getAccessToken() { calls.incrementAndGet(); return TOKEN; }
            public String toString() { return "DO-NOT-RENDER-PROVIDER"; }
        };
        try (Mailer mailer = builder(12345, name).withOAuth2AccessTokenProvider(provider).buildMailer()) {
            assertThat(calls).hasValue(0);
            assertThat(mailer.getSession().getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
            assertThat(mailer.getOperationalConfig().toString()).doesNotContain("DO-NOT-RENDER-PROVIDER", TOKEN);
            assertThat(mailer.getTransportStrategy().toString(mailer.getSession().getProperties()))
                    .contains("oauth2: true").doesNotContain("DO-NOT-RENDER-PROVIDER", TOKEN);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void missingOrConflictingCredentialsFailBeforeResolvingAProvider(final String name) {
        final AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> builder(12345, name).buildMailer())
                .isInstanceOf(MailException.class).hasMessage(MailerException.MISSING_OAUTH2_TOKEN);
        assertThatThrownBy(() -> builder(12345, name).withSMTPServerPassword(TOKEN)
                .withOAuth2AccessTokenProvider(() -> { calls.incrementAndGet(); return "other-token"; }).buildMailer())
                .isInstanceOf(MailException.class).hasMessage(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void invalidOrFailingProvidersDoNotStartAConnection(final String name) throws Exception {
        for (final String token : new String[]{null, "", "  "}) {
            try (Mailer mailer = builder(12345, name).withOAuth2AccessTokenProvider(() -> token).buildMailer()) {
                final Transport transport = mock(Transport.class);
                assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(transport, mailer.getSession()))
                        .isInstanceOf(MailException.class).hasMessage(MailerException.BLANK_OAUTH2_TOKEN_FROM_PROVIDER);
                verifyNoInteractions(transport);
            }
        }
        final IllegalStateException cause = new IllegalStateException("synthetic-provider-failure");
        try (Mailer mailer = builder(12345, name).withOAuth2AccessTokenProvider(() -> { throw cause; }).buildMailer()) {
            final Transport transport = mock(Transport.class);
            assertThatThrownBy(() -> TransportConnectionHelper.connectTransport(transport, mailer.getSession()))
                    .isInstanceOf(MailException.class).hasMessage(MailerException.OAUTH2_TOKEN_PROVIDER_FAILED).hasCause(cause);
            verifyNoInteractions(transport);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void fixedTokenAuthenticatesOverTlsAndSubmitsAnEmail(final String name) throws Exception {
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, name);
            authenticate(peer, TOKEN);
            acceptMessage(peer);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), name).withSMTPServerPassword(TOKEN).buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void reconnectingDedicatedSendsResolveFreshTokens(final String name) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger connections = new AtomicInteger();
        try (Peer server = new Peer("localhost", 2, peer -> {
            establishTls(peer, name);
            authenticate(peer, TOKEN + "-" + connections.incrementAndGet());
            acceptMessage(peer);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), name)
                .withOAuth2AccessTokenProvider(() -> TOKEN + "-" + calls.incrementAndGet()).buildMailer()) {
            assertThat(calls).hasValue(0);
            for (int send = 0; send < 2; send++) {
                mailer.withOpenConnection(sender -> assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED));
            }
            assertThat(calls).hasValue(2);
            assertThat(connections).hasValue(2);
        }
    }

    @Test
    void asyncSendingUsesTheImplicitTlsOauthPath() throws Exception {
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, "SMTPS_OAUTH2");
            authenticate(peer, TOKEN);
            acceptMessage(peer);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), "SMTPS_OAUTH2").withOAuth2AccessTokenProvider(() -> TOKEN).buildMailer()) {
            assertThat(mailer.async().sendMail(email()).getCompletion().get(10, SECONDS).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void aRetainedPhysicalConnectionDoesNotResolveTokensForEachMessage(final String name) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, name);
            authenticate(peer, TOKEN);
            acceptMessage(peer);
            acceptMessage(peer);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), name)
                .withOAuth2AccessTokenProvider(() -> { calls.incrementAndGet(); return TOKEN; }).buildMailer()) {
            mailer.withOpenConnection(sender -> {
                assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                assertThat(sender.sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            });
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void unauthenticatedProbeNeverRequestsAToken(final String name) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, name);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), name)
                .withOAuth2AccessTokenProvider(() -> { calls.incrementAndGet(); return TOKEN; }).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.isTlsActive()).isTrue();
            assertThat(report.isAuthenticated()).isFalse();
            assertThat(report.isStartTlsAttempted()).isEqualTo(!name.startsWith("SMTPS"));
            assertThat(calls).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void authenticatedProbeUsesTheSelectedProtocolAndRedactsTokens(final String name) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, name);
            authenticate(peer, TOKEN);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), name)
                .withOAuth2AccessTokenProvider(() -> { calls.incrementAndGet(); return TOKEN; }).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.isAuthenticated()).isTrue();
            assertThat(report.getAuthenticationMechanism()).isEqualTo("XOAUTH2");
            assertThat(report.toString()).doesNotContain(TOKEN, USER);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void untrustedCertificateBlocksImplicitTlsAuthenticationAndSubmission() throws Exception {
        final KeyStore empty = KeyStore.getInstance("JKS");
        empty.load(null, null);
        try (Peer server = new Peer("localhost", 1, peer ->
                assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class));
             Mailer mailer = builder(server.port(), "SMTPS_OAUTH2").withSMTPServerPassword(TOKEN)
                     .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(SmtpTestTls.tlsContext(empty).getSocketFactory()).build()).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(MailException.class)
                    .hasStackTraceContaining("trustAnchors parameter must be non-empty");
        }
    }

    @Test
    void hostnameMismatchBlocksImplicitTlsAuthenticationAndSubmission() throws Exception {
        try (Peer server = new Peer("127.0.0.2", 1, peer -> {
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = trustedBuilder(server.port(), "SMTPS_OAUTH2").withSMTPServerHost("127.0.0.2")
                .withSMTPServerPassword(TOKEN).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(MailException.class)
                    .hasStackTraceContaining("No subject alternative names matching IP address 127.0.0.2");
        }
    }

    @Test
    void sslEnableFalseCannotDowngradeTheImplicitTlsTransport() throws Exception {
        try (Peer server = new Peer("localhost", 1, peer -> {
            establishTls(peer, "SMTPS_OAUTH2");
            authenticate(peer, TOKEN);
            acceptMessage(peer);
            quit(peer);
        }); Mailer mailer = trustedBuilder(server.port(), "SMTPS_OAUTH2").withSMTPServerPassword(TOKEN)
                .withProperty("mail.smtps.ssl.enable", "false").buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "imap", "SMTPS", ""})
    void contradictoryProtocolIsRejectedBeforeAnyResourceSetup(final String protocol) {
        final AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> builder(12345, "SMTPS_OAUTH2")
                .withOAuth2AccessTokenProvider(() -> { calls.incrementAndGet(); return TOKEN; })
                .withProperty("mail.transport.protocol", protocol).buildMailer())
                .isInstanceOf(MailException.class).hasMessageContaining("SMTPS_OAUTH2", "mail.transport.protocol", "smtps");
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTP_OAUTH2", "SMTPS_OAUTH2"})
    void strategyAndPasswordLocksKeepTheOauthProperties(final String name) throws Exception {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.transportstrategy", name,
                "simplejavamail.locked.smtp.password", TOKEN)).load());
        final String protocol = protocol(TransportStrategy.valueOf(name));
        try (Mailer mailer = configured.mailerBuilder().withSMTPServerHost("localhost").withSMTPServerUsername(USER).buildMailer()) {
            assertThat(mailer.getSession().getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo(TOKEN);
            assertThat(mailer.getSession().getProperty("mail." + protocol + ".auth.mechanisms")).isEqualTo("XOAUTH2");
            assertThat(mailer.getSession().getProperties()).doesNotContainKey("mail." + protocol + ".auth");
        }
        assertThatThrownBy(() -> configured.mailerBuilder().withSMTPServerHost("localhost")
                .withSMTPServerUsername(USER)
                .withProperty("mail." + protocol + ".auth.mechanisms", "PLAIN").buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.transportstrategy");
    }

    @Test
    void lockedProtocolCannotChangeTheImplicitTlsPreset() {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.transportstrategy", "SMTPS_OAUTH2",
                "simplejavamail.locked.extraproperties.mail.transport.protocol", "smtp")).load());
        assertThatThrownBy(() -> configured.mailerBuilder().withSMTPServer("localhost", null, USER, TOKEN).buildMailer())
                .isInstanceOf(MailException.class).hasMessageContaining("SMTPS_OAUTH2", "mail.transport.protocol");
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void callerSessionUsesItsOwnProtocolWithoutApplyingOwnedSessionDefaults(final String protocol) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
        properties.setProperty("mail." + protocol + ".user", USER);
        final Session session = Session.getInstance(properties);
        final jakarta.mail.Provider originalProvider = session.getProvider(protocol);
        try (Mailer mailer = factory().mailerBuilder(session).withOAuth2AccessTokenProvider(() -> TOKEN).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(session);
            assertThat(session.getProvider(protocol)).isSameAs(originalProvider);
            assertThat(properties).doesNotContainKeys("mail." + protocol + ".ssl.checkserveridentity", "mail." + protocol + ".ssl.trust");
            final Transport transport = mock(Transport.class);
            TransportConnectionHelper.connectTransport(transport, session);
            verify(transport).connect(USER, TOKEN);
        }
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static MailerRegularBuilder<?> builder(final int port, final String name) {
        return factory().mailerBuilder().withSMTPServer("localhost", port, USER)
                .withTransportStrategy(TransportStrategy.valueOf(name))
                .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(5))
                        .withSmtpClientHostname("probe.example.test").build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build())
                .withProperty("mail.smtps.quitwait", "true");
    }

    private static MailerRegularBuilder<?> trustedBuilder(final int port, final String name) throws Exception {
        final SSLSocketFactory ssl = SmtpTestTls.tlsContext(SmtpTestTls.testTrustStoreWithSystemRoots()).getSocketFactory();
        return builder(port, name).withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(ssl).build());
    }

    private static String protocol(final TransportStrategy strategy) { return implicit(strategy) ? "smtps" : "smtp"; }
    private static boolean implicit(final TransportStrategy strategy) { return strategy.name().startsWith("SMTPS"); }

    private static Email email() {
        return factory().emailBuilder().startingBlank().from("sender@example.test").to("recipient@example.test")
                .withSubject("Loopback implicit TLS OAuth2").withPlainText("Synthetic local SMTP fixture.").buildEmail();
    }

    private static void establishTls(final SmtpConversation peer, final String name) throws Exception {
        if (name.startsWith("SMTPS")) {
            peer.upgradeToTls();
            peer.greet(EHLO);
        } else {
            peer.startTls(EHLO);
        }
    }

    private static void authenticate(final SmtpConversation peer, final String token) throws IOException {
        final String line = peer.readLine();
        assertThat(line).startsWith("AUTH XOAUTH2 ");
        final String decoded = new String(Base64.getDecoder().decode(line.substring("AUTH XOAUTH2 ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("user=" + USER + "\1auth=Bearer " + token + "\1\1");
        peer.reply("235 authenticated");
    }

    private static void acceptMessage(final SmtpConversation peer) throws IOException {
        expect(peer, "MAIL FROM:<sender@example.test>");
        peer.reply("250 sender accepted");
        peer.expect("RCPT TO:<recipient@example.test>");
        peer.reply("250 recipient accepted");
        peer.expect("DATA");
        peer.reply("354 send message");
        int lines = 0;
        String line;
        while ((line = peer.readLine()) != null && !".".equals(line)) {
            assertThat(++lines).isLessThan(100);
        }
        assertThat(line).isEqualTo(".");
        peer.reply("250 2.0.0 accepted");
    }

    private static void quit(final SmtpConversation peer) throws IOException {
        expect(peer, "QUIT");
        peer.reply("221 bye");
        peer.expectClosed();
    }

    private static void expect(final SmtpConversation peer, final String expected) throws IOException {
        String line = peer.readLine();
        while ("NOOP".equals(line)) { peer.reply("250 connected"); line = peer.readLine(); }
        assertThat(line).isEqualTo(expected);
    }

    @FunctionalInterface
    private interface Script { void run(SmtpConversation peer) throws Exception; }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> completion;

        private Peer(final String host, final int connections, final Script script) throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getByName(host));
            server.setSoTimeout(10000);
            completion = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < connections; i++) {
                        try (SmtpConversation peer = new SmtpConversation(server.accept())) { script.run(peer); }
                    }
                } catch (Exception failure) { throw new AssertionError("Local OAuth2 peer failed", failure); }
            }, executor);
        }

        private int port() { return server.getLocalPort(); }

        public void close() throws Exception {
            try { completion.get(15, SECONDS); }
            finally { server.close(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, SECONDS)).isTrue(); }
        }
    }
}
