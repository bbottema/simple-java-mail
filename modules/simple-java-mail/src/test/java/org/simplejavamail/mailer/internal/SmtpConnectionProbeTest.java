package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.bbottema.javasocksproxyserver.RunningSocksServer;
import org.bbottema.javasocksproxyserver.SyncSocksServer;
import org.bbottema.javasocksproxyserver.auth.UsernamePasswordAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.SmtpProbeReports;
import org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.Conversation;
import org.subethamail.wiser.Wiser;
import testutil.ConfigLoaderTestHelper;

import javax.net.ServerSocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.IOException;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Properties;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.session;
import static org.simplejavamail.mailer.internal.SmtpCapabilityProbeCharacterizationTest.tlsContext;

/** Exercises the actual Mailer API, not the characterization subclass. Every endpoint is local and no email is submitted. */
@Timeout(30)
class SmtpConnectionProbeTest {
    private static final String PLAIN = "250-localhost\r\n250-SIZE 1024\r\n250 AUTH LOGIN";
    private static final String SECURE = "250-localhost\r\n250-SIZE 4096\r\n250-DSN\r\n250 AUTH LOGIN";
    private static final String PROXY_USERNAME = "proxy-user";
    private static final String PROXY_PASSWORD = "proxy-secret";
    private static final AtomicInteger CLASS_VERIFIER_CALLS = new AtomicInteger();

    @Test
    void realParserPreservesDuplicatesAndUnknownExtensionsAndSkipsMalformedLines() throws Exception {
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n250-size 100\r\n250-SIZE 200\r\n250-\r\n250-X-UNKNOWN one two\r\n250 DSN");
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session(false), server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isTrue();
            assertThat(report.getBeforeTls().orElseThrow().getExtensions().get("SIZE")).containsExactly("100", "200");
            assertThat(report.getBeforeTls().orElseThrow().getExtensions().get("X-UNKNOWN")).containsExactly("one two");
            assertThat(report.getBeforeTls().orElseThrow().getMaximumMessageSize()).isEmpty();
            assertThat(report.getWarnings()).contains("An invalid EHLO extension line was ignored.");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endpointDefaultsMatchAngusIncludingInheritedImplicitTls(final boolean implicitTls) {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        final Properties properties = new Properties(defaults);
        final Session session = Session.getInstance(properties);
        assertThat(SmtpProbeReports.begin(session, false).completedAt(Instant.now()).build().getPort()).isEqualTo(implicitTls ? 465 : 25);
        properties.setProperty("mail.smtp.port", "-1");
        assertThat(SmtpProbeReports.begin(session, false).completedAt(Instant.now()).build().getPort()).isEqualTo(implicitTls ? 465 : 25);
        properties.put("mail.smtp.port", 12345);
        assertThat(SmtpProbeReports.begin(session, false).completedAt(Instant.now()).build().getPort()).isEqualTo(12345);
        properties.put("mail.smtp.ssl.enable", Boolean.FALSE);
        properties.remove("mail.smtp.port");
        assertThat(SmtpProbeReports.begin(session, false).completedAt(Instant.now()).build().getPort()).isEqualTo(25);
    }

    @Test
    void anOversizedExtensionLineMakesCapabilitiesUnavailable() throws Exception {
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n250-X-UNKNOWN " + "x".repeat(2048) + "\r\n250 DSN");
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session(false), server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isTrue();
            assertThat(report.getEffectiveCapabilities()).isEmpty();
            assertThat(report.getWarnings()).anySatisfy(warning -> assertThat(warning).contains("diagnostic limit"));
        }
    }

    @Test
    void explicitProbeStillConnectsInLoggingOnlyMode() throws Exception {
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session(false), server)).withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void loggingOnlyRegularBuilderRetainsTheEndpointAndCredentialsForExplicitProbes(final boolean async, final boolean authenticate) throws Exception {
        try (Peer server = new Peer(false, peer -> {
            peer.greet(PLAIN);
            if (authenticate) {
                peer.authenticate();
            }
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user", "probe-secret")
                .withProperty("mail.smtp.localhost", "probe.example.test")
                .withTransportModeLoggingOnly(true).withOpportunisticTLS(false).buildMailer()) {
            final SmtpConnectionReport report = async ? mailer.async().probeConnection(authenticate).get(10, SECONDS)
                    : mailer.sync().probeConnection(authenticate);
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.getHost()).isEqualTo("localhost");
            assertThat(report.getPort()).isEqualTo(server.port());
            assertThat(report.isAuthenticated()).isEqualTo(authenticate);
            assertThat(mailer.getServerConfig().getHost()).isEqualTo("localhost");
            assertThat(mailer.getServerConfig().getPort()).isEqualTo(server.port());
            assertThat(mailer.getSession().getProperty("mail.smtp.port")).isEqualTo(Integer.toString(server.port()));
            assertThat(mailer.getOperationalConfig().isTransportModeLoggingOnly()).isTrue();
            assertThat(report.toString()).doesNotContain("probe-user", "probe-secret");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerVerifierCanRejectTlsWhetherSuppliedAsAnObjectOrClass(final boolean byClass) throws Exception {
        CLASS_VERIFIER_CALLS.set(0);
        final Session session = session(true);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        if (byClass) {
            session.getProperties().setProperty("mail.smtp.ssl.hostnameverifier.class", RejectingHostnameVerifier.class.getName());
        } else {
            session.getProperties().put("mail.smtp.ssl.hostnameverifier", new RejectingHostnameVerifier());
        }
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.upgradeToTls();
            peer.expectClosed();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.STARTTLS);
            assertThat(report.isStartTlsCompleted()).isFalse();
            assertThat(CLASS_VERIFIER_CALLS).hasValue(1);
            if (byClass) {
                assertThat(report.getTlsDetails()).isEmpty();
            } else {
                assertThat(report.getTlsDetails().orElseThrow().getCustomHostnameVerifierAccepted()).contains(false);
            }
        }
    }

    @Test
    void untrustedCertificateStillFailsWithTheMetadataObserverInstalled() throws Exception {
        final Session session = session(false);
        final KeyStore trust = KeyStore.getInstance("JKS");
        trust.load(null, null);
        session.getProperties().put("mail.smtp.ssl.socketFactory", tlsContext(trust).getSocketFactory());
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.STARTTLS);
            assertThat(report.getAfterTls()).isEmpty();
            assertThat(report.isTlsActive()).isFalse();
        }
    }

    @Test
    void nativeEndpointIdentificationStillRejectsAMismatchedPeerName() throws Exception {
        final Session session = session(true);
        final SSLSocketFactory trusted = (SSLSocketFactory) session.getProperties().get("mail.smtp.ssl.socketFactory");
        final SSLSocketFactory mismatchedPeer = mock(SSLSocketFactory.class);
        when(mismatchedPeer.createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean()))
                .thenAnswer(call -> trusted.createSocket(call.getArgument(0), "not-localhost.invalid", call.getArgument(2), call.getArgument(3)));
        session.getProperties().put("mail.smtp.ssl.socketFactory", mismatchedPeer);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            assertThatThrownBy(peer::upgradeToTls).isInstanceOf(IOException.class);
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.STARTTLS);
            assertThat(report.isStartTlsCompleted()).isFalse();
            assertThat(report.getTlsDetails()).isEmpty();
            verify(mismatchedPeer).createSocket(any(Socket.class), anyString(), anyInt(), anyBoolean());
        }
    }

    @Test
    void aLaterProbeCannotChangeAnEarlierSnapshot() throws Exception {
        final Session session = session(false);
        try (Peer first = new Peer(false, peer -> { peer.greet(PLAIN); peer.quit(); });
             Peer second = new Peer(false, peer -> { peer.greet(SECURE); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, first)).buildMailer()) {
            final SmtpConnectionReport earlier = mailer.sync().probeConnection();
            atEndpoint(session, second);
            final SmtpConnectionReport later = mailer.sync().probeConnection();
            assertThat(later.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize()).hasValue(4096);
            assertThat(earlier.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize()).hasValue(1024);
            assertThat(earlier.getBeforeTls().orElseThrow().supports("DSN")).isFalse();
        }
    }

    /** Public constructor is required by Angus's configured hostname-verifier class hook. */
    public static final class RejectingHostnameVerifier implements HostnameVerifier {
        public RejectingHostnameVerifier() {
        }

        @Override
        public boolean verify(final String host, final SSLSession session) {
            CLASS_VERIFIER_CALLS.incrementAndGet();
            return false;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerCanInspectSynchronouslyOrAsynchronouslyWithoutChangingSessionOrNotifyingObservers(final boolean async) throws Exception {
        final Session session = session(false);
        final AtomicInteger observations = new AtomicInteger();
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).withMailSendObserver(outcome -> observations.incrementAndGet()).buildMailer()) {
            final Properties before = (Properties) session.getProperties().clone();
            final Provider provider = session.getProvider("smtp");
            final SmtpConnectionReport report = async ? mailer.async().probeConnection().get(10, SECONDS) : mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.getGreeting()).contains("220 localhost capability test peer");
            assertThat(report.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize()).hasValue(1024);
            assertThat(report.getBeforeTls()).isPresent();
            assertThat(report.getAfterTls()).isEmpty();
            assertThat(report.getAuthenticationMechanism()).isEmpty();
            assertThat(report.getWarnings()).isEmpty();
            assertThat(report.isAuthenticated()).isFalse();
            assertThat(report.getCompletedAt()).isAfterOrEqualTo(report.getStartedAt());
            assertThat(session.getProperties()).isEqualTo(before);
            assertThat(session.getProvider("smtp")).isSameAs(provider);
            assertThat(observations).hasValue(0);
        }
    }

    @Test
    void authenticationIsOptInEvenWithAnAuthenticatorAndCachedCredentials() throws Exception {
        final AtomicInteger requested = new AtomicInteger();
        final Session session = Session.getInstance(session(false).getProperties(), new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                requested.incrementAndGet();
                return new PasswordAuthentication("probe-user", "probe-secret");
            }
        });
        session.getProperties().setProperty("mail.smtp.auth", "true");
        session.getProperties().setProperty("mail.smtp.user", "probe-user");
        final URLName cacheKey = new URLName("smtp", "localhost", -1, null, "probe-user", null);
        session.setPasswordAuthentication(cacheKey, new PasswordAuthentication("probe-user", "cached-secret"));
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(requested).hasValue(0);
            assertThat(session.getPasswordAuthentication(cacheKey).getPassword()).isEqualTo("cached-secret");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitAuthenticationUsesSessionAuthenticatorOrItsCache(final boolean cached) throws Exception {
        final AtomicInteger requested = new AtomicInteger();
        final Session session = Session.getInstance(session(false).getProperties(), new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                requested.incrementAndGet();
                return new PasswordAuthentication("probe-user", "probe-secret");
            }
        });
        session.getProperties().setProperty("mail.smtp.user", "probe-user");
        if (cached) {
            session.setPasswordAuthentication(new URLName("smtp", "localhost", -1, null, "probe-user", null),
                    new PasswordAuthentication("probe-user", "probe-secret"));
        }
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.authenticate(); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.isAuthenticationRequested()).isTrue();
            assertThat(report.isAuthenticated()).isTrue();
            assertThat(report.getAuthenticationMechanism()).contains("LOGIN");
            assertThat(requested).hasValue(cached ? 0 : 1);
            // Match the authentication exchange, not digits that can also occur in the random port or elapsed time.
            assertThat(report.toString()).doesNotContain("probe-user", "probe-secret",
                    Base64.getEncoder().encodeToString("probe-user".getBytes(StandardCharsets.UTF_8)),
                    Base64.getEncoder().encodeToString("probe-secret".getBytes(StandardCharsets.UTF_8)),
                    "334 VXNlcm5hbWU6", "334 UGFzc3dvcmQ6", "235 authenticated");
        }
    }

    @Test
    void regularBuilderPasswordReachesTheAuthenticatedProbe() throws Exception {
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.authenticate(); peer.quit(); });
             Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user", "probe-secret")
                     .withProperty("mail.smtp.localhost", "probe.example.test").withOpportunisticTLS(false).buildMailer()) {
            assertThat(mailer.sync().probeConnection(true).isAuthenticated()).isTrue();
        }
    }

    @Test
    void noAuthDoesNotResolveOAuth2TokenProvider() throws Exception {
        final AtomicInteger tokenRequests = new AtomicInteger();
        try (Peer server = new Peer(false, peer -> { peer.startTls(SECURE); peer.quit(); });
             Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user")
                     .withTransportStrategy(TransportStrategy.SMTP_OAUTH2).withOAuth2AccessTokenProvider(() -> {
                         tokenRequests.incrementAndGet();
                         return "secret-oauth-token";
                     }).withCustomSSLFactoryInstance((SSLSocketFactory) session(true).getProperties().get("mail.smtp.ssl.socketFactory"))
                     .withProperty("mail.smtp.localhost", "probe.example.test")
                     .buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            assertThat(tokenRequests).hasValue(0);
        }
    }

    @Test
    void explicitAuthenticationUsesTheNormalOAuth2TokenProviderWithoutRetainingTheExchange() throws Exception {
        final AtomicInteger tokenRequests = new AtomicInteger();
        final String initialResponse = Base64.getEncoder().encodeToString(
                "user=probe-user\u0001auth=Bearer secret-oauth-token\u0001\u0001".getBytes(StandardCharsets.UTF_8));
        try (Peer server = new Peer(false, peer -> {
            peer.startTls("250-localhost\r\n250 AUTH XOAUTH2");
            peer.expect("AUTH XOAUTH2 " + initialResponse);
            peer.reply("235 authenticated");
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2).withOAuth2AccessTokenProvider(() -> {
                    tokenRequests.incrementAndGet();
                    return "secret-oauth-token";
                }).withCustomSSLFactoryInstance((SSLSocketFactory) session(true).getProperties().get("mail.smtp.ssl.socketFactory"))
                .withProperty("mail.smtp.localhost", "probe.example.test")
                .buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.isAuthenticated()).isTrue();
            assertThat(report.getAuthenticationMechanism()).contains("XOAUTH2");
            assertThat(mailer.getTransportStrategy()).isEqualTo(TransportStrategy.SMTP_OAUTH2);
            assertThat(tokenRequests).hasValue(1);
            // Match the authentication reply, not digits that can also occur in the random port or elapsed time.
            assertThat(report.toString()).doesNotContain("probe-user", "secret-oauth-token", initialResponse, "235 authenticated",
                    "Configured strategy:", "SMTP_OAUTH2");
        }
    }

    @Test
    void tokenResolutionFailureIsSafeAndPrecedesAnyNetworkConnection() throws Exception {
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", 1, "probe-user")
                .withTransportStrategy(TransportStrategy.SMTP_OAUTH2).withOAuth2AccessTokenProvider(() -> {
                    throw new IllegalStateException("credential-service leaked secret-token");
                }).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.AUTHENTICATION);
            assertThat(report.getGreeting()).isEmpty();
            assertThat(report.toString()).doesNotContain("credential-service", "secret-token");
        }
    }

    @Test
    void incompatibleAuthMechanismsAreAnAuthenticationFailureEvenWithoutAnAuthCommand() throws Exception {
        try (Peer server = new Peer(false, peer -> { peer.greet("250-localhost\r\n250 AUTH X-NOT-SUPPORTED"); peer.expectClosed(); });
             Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user", "probe-secret")
                     .withOpportunisticTLS(false).withProperty("mail.smtp.localhost", "probe.example.test").buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.AUTHENTICATION);
            assertThat(report.getAuthenticationMechanism()).isEmpty();
            assertThat(report.getBeforeTls()).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {255, 256})
    void diagnosticLineBoundAcceptsItsExactLimitAndDoesNotInventMissingCapabilities(final int sizeLines) throws Exception {
        try (Peer server = new Peer(false, peer -> {
            peer.greet("250-localhost\r\n" + "250-SIZE 100\r\n".repeat(sizeLines) + "250 DSN");
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session(false), server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isTrue();
            if (sizeLines == 255) {
                assertThat(report.getEffectiveCapabilities().orElseThrow().supports("DSN")).isTrue();
                assertThat(report.getWarnings()).isEmpty();
            } else {
                assertThat(report.getEffectiveCapabilities()).isEmpty();
                assertThat(report.getWarnings()).anySatisfy(warning -> assertThat(warning).contains("more than 256"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void heloFallbackAndExplicitlyDisabledEhloLeaveCapabilitiesUnknown(final boolean disableEhlo, final boolean implicitTls) throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.ehlo", Boolean.toString(!disableEhlo));
        session.getProperties().setProperty("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        if (implicitTls) {
            session.getProperties().setProperty("mail.smtp.ssl.trust", "*"); // Ordering only; strict identity/trust failures have separate tests.
        }
        try (Peer server = new Peer(implicitTls, peer -> {
            if (disableEhlo) {
                peer.reply("220 localhost capability test peer");
            } else {
                peer.greet("500 EHLO not supported");
            }
            peer.expect("HELO probe.example.test");
            peer.reply("250 localhost");
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.getBeforeTls()).isEmpty();
            assertThat(report.getEffectiveCapabilities()).isEmpty();
            assertThat(report.isTlsActive()).isEqualTo(implicitTls);
            assertThat(report.getWarnings()).anySatisfy(warning -> assertThat(warning).contains("HELO instead of EHLO"));
        }
    }

    @Test
    void cleanupFailureRetainsNegotiatedFactsBeforeTheAsyncFutureCompletes() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.timeout", "500");
        try (Peer server = new Peer(false, peer -> {
            peer.greet(PLAIN);
            peer.expect("QUIT");
            peer.expectClosed();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.async().probeConnection().get(5, SECONDS);
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.isConnected()).isTrue();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.CLOSE);
            assertThat(report.getEffectiveCapabilities().orElseThrow().supports("SIZE")).isTrue();
        }
    }

    @Test
    void missingAuthAdvertisementDoesNotBecomeAnAuthenticationSuccess() throws Exception {
        try (Peer server = new Peer(false, peer -> { peer.greet("250-localhost\r\n250 SIZE 100"); peer.quit(); });
             Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user", "probe-secret")
                     .withOpportunisticTLS(false).withProperty("mail.smtp.localhost", "probe.example.test").buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isConnected()).isTrue();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.isAuthenticated()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.AUTHENTICATION);
        }
    }

    @Test
    void failedAuthenticationDoesNotLeakTheServersFailureText() throws Exception {
        try (Peer server = new Peer(false, peer -> {
            peer.greet(PLAIN);
            peer.expect("AUTH LOGIN");
            peer.reply("535 refused probe-user probe-secret bearer-token");
            peer.expectClosed();
        }); Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.port(), "probe-user", "probe-secret")
                .withOpportunisticTLS(false).withProperty("mail.smtp.localhost", "probe.example.test").buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection(true);
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.AUTHENTICATION);
            assertThat(report.getBeforeTls()).isPresent();
            assertThat(report.toString()).doesNotContain("probe-secret", "probe-user", "bearer-token", "535 refused");
        }
    }

    @Test
    void successfulTlsRecordsBothSnapshotsAndMetadataWithoutReplacingCallerVerifier() throws Exception {
        final Session session = session(true);
        final AtomicInteger verifications = new AtomicInteger();
        final HostnameVerifier verifier = (host, tls) -> { verifications.incrementAndGet(); return true; };
        session.getProperties().put("mail.smtp.ssl.hostnameverifier", verifier);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        try (Peer server = new Peer(false, peer -> { peer.startTls(SECURE); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.isStartTlsCompleted()).isTrue();
            assertThat(report.getBeforeTls().orElseThrow().getMaximumMessageSize()).hasValue(1024);
            assertThat(report.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize()).hasValue(4096);
            assertThat(report.getTlsDetails().orElseThrow().getProtocol()).startsWith("TLS");
            assertThat(report.getTlsDetails().orElseThrow().getCustomHostnameVerifierAccepted()).contains(true);
            assertThat(verifications).hasValue(1);
            assertThat(session.getProperties().get("mail.smtp.ssl.hostnameverifier")).isSameAs(verifier);
        }
    }

    @Test
    void rejectedPostTlsEhloCannotReusePreTlsFactsOrAuthenticate() throws Exception {
        final Session session = session(true);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        try (Peer server = new Peer(false, peer -> { peer.startTls("500 EHLO refused"); peer.expectClosed(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.isStartTlsCompleted()).isTrue();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.EHLO);
            assertThat(report.getBeforeTls()).isPresent();
            assertThat(report.getAfterTls()).isEmpty();
            assertThat(report.getEffectiveCapabilities()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrRefusedRequiredStartTlsPreservesEarlierFacts(final boolean offered) throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.starttls.required", "true");
        try (Peer server = new Peer(false, peer -> {
            peer.greet(offered ? "250-localhost\r\n250 STARTTLS" : PLAIN);
            if (offered) { peer.expect("STARTTLS"); peer.reply("454 unavailable"); }
            peer.expectClosed();
        }); Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.STARTTLS);
            assertThat(report.getBeforeTls()).isPresent();
            assertThat(report.isStartTlsCompleted()).isFalse();
        }
    }

    @Test
    void implicitTlsHasNoPlaintextSnapshot() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.ssl.enable", "true");
        session.getProperties().setProperty("mail.smtp.ssl.trust", "*"); // Ordering test; accommodates local TLS-inspecting mail shields.
        try (Peer server = new Peer(true, peer -> { peer.greet(SECURE); peer.quit(); });
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(report.getBeforeTls()).isEmpty();
            assertThat(report.getAfterTls()).isPresent();
            assertThat(report.isStartTlsAttempted()).isFalse();
            assertThat(report.isTlsActive()).isTrue();
        }
    }

    @Test
    void aStalledGreetingReturnsATimeoutReportAndCloses() throws Exception {
        final Session session = session(false);
        session.getProperties().setProperty("mail.smtp.timeout", "500");
        try (Peer server = new Peer(false, Conversation::expectClosed);
             Mailer mailer = factory().mailerBuilder(atEndpoint(session, server)).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.GREETING);
            assertThat(report.getFailureDescription()).hasValueSatisfying(description -> assertThat(description).contains("timed out"));
        }
    }

    @Test
    void customMailerAndUnsupportedProviderAreNotInvoked() throws Exception {
        try (Mailer mailer = factory().mailerBuilder().withCustomMailer(mock(CustomMailer.class)).buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSupported()).isFalse();
        }
        final Session session = session(false);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", "not.a.real.Provider", "test", null);
        session.addProvider(provider);
        session.setProvider(provider);
        try (Mailer mailer = factory().mailerBuilder(session).buildMailer()) {
            final SmtpConnectionReport report = mailer.sync().probeConnection();
            assertThat(report.isSupported()).isFalse();
            assertThat(report.getFailureDescription()).hasValueSatisfying(description -> assertThat(description).contains("not.a.real.Provider"));
        }
    }

    @Test
    void executorRejectionCompletesFutureExceptionallyWithoutOpeningAConnection() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", 25).withExecutorService(executor).buildMailer()) {
            assertThatThrownBy(() -> mailer.async().probeConnection().get(2, SECONDS)).hasCauseInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test
    void aClosedMailerRejectsNewProbes() throws Exception {
        final Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", 25).buildMailer();
        mailer.close();
        assertThatThrownBy(mailer.sync()::probeConnection).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mailer.async().probeConnection().get(2, SECONDS)).hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void probeDoesNotWaitForOrDisturbALeasedPoolSizeOneConnection() throws Exception {
        final Wiser server = Wiser.port(0);
        server.start();
        try (Mailer mailer = factory().mailerBuilder().withSMTPServer("localhost", server.getServer().getPortAllocated())
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(500)
                .withOpportunisticTLS(false).buildMailer()) {
            final LifecycleDelegatingTransport lease = ModuleLoader.loadBatchModule().acquireTransport(
                    mailer.getOperationalConfig().getClusterKey(), mailer.getSession(), true, null);
            try {
                final SmtpConnectionReport report = mailer.async().probeConnection().get(5, SECONDS);
                assertThat(report.isSuccessful()).as(report.toString()).isTrue();
                assertThat(lease.getTransport().isConnected()).isTrue();
            } finally {
                lease.signalTransportUsed();
            }
            assertThat(server.getMessages()).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void probeHonorsAnonymousSocksProxyConfiguration() throws Exception {
        final RunningSocksServer proxy = new SyncSocksServer().startServer(0);
        try (Peer server = new Peer(false, peer -> { peer.greet(PLAIN); peer.quit(); });
             Mailer mailer = factory().mailerBuilder().withProxy("localhost", proxy.getPort())
                     .withSMTPServer("localhost", server.port()).withOpportunisticTLS(false)
                     .withProperty("mail.smtp.localhost", "probe.example.test").buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
        } finally {
            proxy.stop();
        }
    }

    @ParameterizedTest(name = "async={0}, SMTP authentication={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void probeAuthenticatesWithSocksProxyIndependentlyOfSmtpAuthentication(final boolean asynchronous, final boolean authenticateSmtp) throws Exception {
        final AtomicInteger proxyAuthenticationAttempts = new AtomicInteger();
        final RunningSocksServer proxy = startAuthenticatedProxy(proxyAuthenticationAttempts);
        try (Peer server = new Peer(false, peer -> {
            peer.greet(PLAIN);
            if (authenticateSmtp) {
                peer.authenticate();
            }
            peer.quit();
        }); Mailer mailer = factory().mailerBuilder().withProxy("localhost", proxy.getPort(), PROXY_USERNAME, PROXY_PASSWORD)
                .withSMTPServer("localhost", server.port(), "probe-user", "probe-secret").withOpportunisticTLS(false)
                .withSessionTimeout(2000).withProperty("mail.smtp.localhost", "probe.example.test").buildMailer()) {
            final SmtpConnectionReport report = asynchronous ? mailer.async().probeConnection(authenticateSmtp).get(5, SECONDS)
                    : mailer.sync().probeConnection(authenticateSmtp);

            assertThat(report.isSuccessful()).as(report.toString()).isTrue();
            assertThat(proxyAuthenticationAttempts).hasValue(1);
            assertThat(report.isAuthenticationRequested()).isEqualTo(authenticateSmtp);
            assertThat(report.isAuthenticated()).isEqualTo(authenticateSmtp);
            assertThat(report.toString()).doesNotContain(PROXY_USERNAME, PROXY_PASSWORD, "probe-user", "probe-secret");
        } finally {
            proxy.stop();
        }
    }

    @ParameterizedTest(name = "async={0}, proxy credentials={1}/{2}")
    @CsvSource({"false, wrong-user, proxy-secret", "false, proxy-user, wrong-secret",
            "true, wrong-user, proxy-secret", "true, proxy-user, wrong-secret"})
    void probeRejectsInvalidSocksProxyCredentialsBeforeReachingSmtp(final boolean asynchronous, final String username, final String password) throws Exception {
        final AtomicInteger proxyAuthenticationAttempts = new AtomicInteger();
        final RunningSocksServer proxy = startAuthenticatedProxy(proxyAuthenticationAttempts);
        try (ServerSocket smtpEndpoint = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
             Mailer mailer = factory().mailerBuilder().withProxy("localhost", proxy.getPort(), username, password)
                     .withSMTPServer("localhost", smtpEndpoint.getLocalPort()).withOpportunisticTLS(false)
                     .withSessionTimeout(2000).buildMailer()) {
            final SmtpConnectionReport report = asynchronous ? mailer.async().probeConnection().get(5, SECONDS)
                    : mailer.sync().probeConnection();

            assertThat(proxyAuthenticationAttempts).hasValue(1);
            assertThat(report.isSupported()).isTrue();
            assertThat(report.isSuccessful()).isFalse();
            assertThat(report.isConnected()).isFalse();
            assertThat(report.getFailurePhase()).contains(SmtpConnectionPhase.CONNECT);
            assertThat(report.getGreeting()).isEmpty();
            assertThat(report.getEffectiveCapabilities()).isEmpty();
            assertThat(report.toString()).doesNotContain(PROXY_USERNAME, PROXY_PASSWORD, username, password);
            assertNoSmtpConnection(smtpEndpoint);
        } finally {
            proxy.stop();
        }
    }

    private static RunningSocksServer startAuthenticatedProxy(final AtomicInteger authenticationAttempts) {
        return new SyncSocksServer().startServer(0, ServerSocketFactory.getDefault(), new UsernamePasswordAuthenticator(false) {
            @Override
            public boolean validate(final String username, final String password) {
                authenticationAttempts.incrementAndGet();
                return PROXY_USERNAME.equals(username) && PROXY_PASSWORD.equals(password);
            }
        });
    }

    private static void assertNoSmtpConnection(final ServerSocket smtpEndpoint) throws IOException {
        smtpEndpoint.setSoTimeout(250);
        assertThatThrownBy(() -> {
            try (Socket unexpectedConnection = smtpEndpoint.accept()) {
                throw new AssertionError("Rejected proxy credentials must not reach the SMTP endpoint");
            }
        }).isInstanceOf(SocketTimeoutException.class);
    }

    private static SimpleJavaMail factory() { return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()); }

    private static Session atEndpoint(final Session session, final Peer peer) {
        session.getProperties().setProperty("mail.smtp.host", "localhost");
        session.getProperties().setProperty("mail.smtp.port", Integer.toString(peer.port()));
        return session;
    }

    @FunctionalInterface
    private interface Script { void run(Conversation peer) throws Exception; }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> serving;

        private Peer(final boolean implicitTls, final Script script) throws Exception {
            server = new ServerSocket(0, 1, InetAddress.getByName("localhost"));
            server.setSoTimeout(10000);
            serving = CompletableFuture.runAsync(() -> {
                try (Conversation peer = new Conversation(server.accept())) {
                    if (implicitTls) { peer.upgradeToTls(); }
                    script.run(peer);
                } catch (Exception failure) {
                    throw new AssertionError("Scripted probe peer failed", failure);
                }
            }, worker);
        }

        private int port() { return server.getLocalPort(); }

        @Override
        public void close() throws Exception {
            try {
                serving.get(15, SECONDS);
            } finally {
                server.close();
                worker.shutdownNow();
                assertThat(worker.awaitTermination(15, SECONDS)).isTrue();
            }
        }
    }
}
