package org.simplejavamail.mailer.internal;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.util.Properties;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;
import static testutil.smtp.SmtpTestTls.testTrustStoreWithSystemRoots;
import static testutil.smtp.SmtpTestTls.tlsContext;

/** Guards the owned-session correction while retaining deliberate advanced factory fallback. All SMTP peers are local. */
@Timeout(30)
class SmtpSocketFactoryFallbackCharacterizationTest {

    @Test
    void theOwnedTrackedFactoryAlwaysDisablesFallback() throws Exception {
        try (Mailer mailer = builder(12345, SMTP).withProperty("mail.smtp.socketFactory.fallback", "true").buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.socketFactory.fallback")).isEqualTo("false");
        }
    }

    @Test
    void aContradictoryFallbackLockIsRejectedBeforeTheTrackedFactoryCanAcquireAConnection() {
        assertThatThrownBy(() -> locked(Map.of("extraproperties.mail.smtp.socketFactory.fallback", "true"))
                .mailerBuilder().withSMTPServer("localhost", 25).buildMailer())
                .hasMessageContaining("locked.extraproperties.mail.smtp.socketFactory.fallback");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customFactoryFallbackLocksArePreserved(final boolean fallback) throws Exception {
        final SocketFactory custom = SocketFactory.getDefault();
        try (Mailer mailer = locked(Map.of("extraproperties.mail.smtp.socketFactory.fallback", Boolean.toString(fallback)))
                .mailerBuilder().withSMTPServer("localhost", 25).withProperties(factoryProperties(custom, "absent")).buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.socketFactory.fallback")).isEqualTo(Boolean.toString(fallback));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void booleanFallbackValuesAreHonoredOnTheWire(final boolean fallback) throws Exception {
        final IOException rejected = new IOException("synthetic Boolean-configured factory rejection");
        final Properties properties = factoryProperties(failingPlainFactory(rejected), "absent");
        properties.put("mail.smtp.socketFactory.fallback", fallback);
        if (fallback) {
            try (ScriptedSmtpServer server = acceptingPeer(); Mailer mailer = builder(server.port(), SMTP).withProperties(properties).buildMailer()) {
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            }
        } else {
            try (ServerSocket server = unusedPeer(); Mailer mailer = builder(server.getLocalPort(), SMTP).withProperties(properties).buildMailer()) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasRootCause(rejected);
                assertNoConnection(server);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true"})
    void aFailingCallerFactoryIsAbandonedAndTheMessageIsSentThroughTheDefaultFactory(final String fallback) throws Exception {
        final SocketFactory factory = failingPlainFactory(new IOException("Synthetic application factory rejection"));
        try (ScriptedSmtpServer server = acceptingPeer();
             Mailer mailer = builder(server.port(), SMTP).withProperties(factoryProperties(factory, fallback)).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get("mail.smtp.socketFactory")).isSameAs(factory);
            assertThat(mailer.getSession().getProperty("mail.smtp.socketFactory.fallback"))
                    .isEqualTo("absent".equals(fallback) ? null : fallback);
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            verify(factory, times(1)).createSocket();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void defaultOrDisabledFallbackRetainsTheCallerFactoryFailureWithoutOpeningAReplacementConnection(final String fallback) throws Exception {
        final IOException rejection = new IOException("Synthetic application factory rejection");
        final SocketFactory factory = failingPlainFactory(rejection);
        try (ServerSocket server = unusedPeer();
             Mailer mailer = builder(server.getLocalPort(), SMTP).withProperties(factoryProperties(factory, fallback)).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasRootCause(rejection);
            verify(factory, times(1)).createSocket();
            assertNoConnection(server);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true"})
    void anUnloadableCallerFactoryClassAlsoFallsBackToSending(final String fallback) throws Exception {
        try (ScriptedSmtpServer server = acceptingPeer()) {
            final MailerRegularBuilder<?> builder = builder(server.port(), SMTP)
                    .withProperty("mail.smtp.socketFactory.class", "application.DoesNotExistSocketFactory");
            if (!"absent".equals(fallback)) {
                builder.withProperty("mail.smtp.socketFactory.fallback", fallback);
            }
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "false"})
    void defaultOrDisabledFallbackAlsoRejectsAnUnloadableFactoryClass(final String fallback) throws Exception {
        try (ServerSocket server = unusedPeer()) {
            final MailerRegularBuilder<?> builder = builder(server.getLocalPort(), SMTP)
                    .withProperty("mail.smtp.socketFactory.class", "application.DoesNotExistSocketFactory");
            if (!"absent".equals(fallback)) {
                builder.withProperty("mail.smtp.socketFactory.fallback", fallback);
            }
            try (Mailer mailer = builder.buildMailer()) {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasRootCauseInstanceOf(ClassNotFoundException.class);
                assertNoConnection(server);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "true", "false"})
    void aFactorySocketTimeoutIsNeverConvertedToFallback(final String fallback) throws Exception {
        final SocketTimeoutException timeout = new SocketTimeoutException("Synthetic application factory timeout");
        final SocketFactory factory = failingPlainFactory(timeout);
        try (ServerSocket server = unusedPeer();
             Mailer mailer = builder(server.getLocalPort(), SMTP).withProperties(factoryProperties(factory, fallback)).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasRootCause(timeout);
            verify(factory, times(1)).createSocket();
            assertNoConnection(server);
        }
    }

    @ParameterizedTest
    @CsvSource({"absent, false", "true, true", "false, false"})
    void implicitTlsFactoryFailureCleansUpBeforeAnyDeliberateFallback(final String fallback,
            final boolean attemptsFallback) throws Exception {
        final IOException rejection = new IOException("Synthetic TLS identity factory rejection");
        final SSLSocketFactory factory = mock(SSLSocketFactory.class);
        final AtomicReference<Socket> rejectedSocket = new AtomicReference<>();
        final CountDownLatch rejectedPeerAccepted = new CountDownLatch(1);
        when(factory.createSocket(any(Socket.class), anyString(), anyInt(), eq(true))).thenAnswer(invocation -> {
            rejectedSocket.set(invocation.getArgument(0));
            assertThat(rejectedPeerAccepted.await(5, SECONDS)).isTrue();
            throw rejection;
        });
        final AtomicInteger connections = new AtomicInteger();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(attemptsFallback ? 2 : 1, peer -> {
            if (connections.incrementAndGet() == 1) {
                rejectedPeerAccepted.countDown();
                peer.expectClosed();
            } else {
                // Observe the replacement TCP connection without changing the JVM's default TLS trust store.
                peer.close();
            }
        })) {
            try {
                final MailerRegularBuilder<?> builder = builder(server.port(), SMTPS).withCustomSSLFactoryInstance(factory)
                        .withProperty("mail.smtps.ssl.protocols", "TLSv1.2");
                if (!"absent".equals(fallback)) {
                    builder.withProperty("mail.smtps.socketFactory.fallback", fallback);
                }
                try (Mailer mailer = builder.buildMailer()) {
                    final Throwable failure = catchThrowable(() -> mailer.sync().sendMail(email()));
                    assertThat(failure).isNotNull();
                    if (attemptsFallback) {
                        assertThat(rootCause(failure)).as("the replacement, not the selected factory, supplies the final error")
                                .isNotSameAs(rejection);
                    } else {
                        assertThat(rootCause(failure)).isSameAs(rejection);
                    }
                    verify(factory, times(1)).createSocket(any(Socket.class), anyString(), anyInt(), eq(true));
                }
                assertThat(rejectedSocket.get()).isNotNull();
                assertThat(rejectedSocket.get().isClosed()).as("the failed TLS wrapper closes its connected socket before handoff or fallback").isTrue();
            } finally {
                closeRejectedSocket(rejectedSocket);
            }
        }
        assertThat(connections.get()).isEqualTo(attemptsFallback ? 2 : 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aStartTlsFactoryFailureNeverFallsBackAndTheAssignedSocketIsClosed(final String fallback) throws Exception {
        final IOException rejection = new IOException("Synthetic STARTTLS identity factory rejection");
        final SSLSocketFactory factory = mock(SSLSocketFactory.class);
        final AtomicReference<Socket> rejectedSocket = new AtomicReference<>();
        when(factory.createSocket(any(Socket.class), anyString(), anyInt(), eq(true))).thenAnswer(invocation -> {
            rejectedSocket.set(invocation.getArgument(0));
            throw rejection;
        });
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
            peer.expectClosed();
        }); Mailer mailer = builder(server.port(), SMTP_TLS).withCustomSSLFactoryInstance(factory)
                .withProperty("mail.smtp.socketFactory.fallback", fallback).buildMailer()) {
            try {
                assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasRootCause(rejection);
                verify(factory, times(1)).createSocket(any(Socket.class), anyString(), anyInt(), eq(true));
                assertThat(rejectedSocket.get().isClosed()).isTrue();
            } finally {
                closeRejectedSocket(rejectedSocket);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("trustedHostConfigurations")
    void customTrustedHostsRemainEnforcedAfterTlsWrapping(final TransportStrategy strategy, final boolean classFactory,
            final boolean trusted) throws Exception {
        final MailSSLSocketFactory application = new MailSSLSocketFactory();
        application.setTrustedHosts(trusted ? "localhost" : "other.example.test");
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            beginTls(peer, strategy);
            if (trusted) {
                acceptAfterTls(peer, strategy);
            } else {
                peer.expectClosed();
            }
        })) {
            final MailerRegularBuilder<?> settings = builder(server.port(), strategy)
                    .withProperty(strategy == SMTPS ? "mail.smtps.ssl.protocols" : "mail.smtp.ssl.protocols", "TLSv1.2");
            if (classFactory) {
                TrustedSslFactory.application = application;
                settings.withCustomSSLFactoryClass(TrustedSslFactory.class.getName());
            } else {
                settings.withCustomSSLFactoryInstance(application);
            }
            try (Mailer mailer = settings.buildMailer()) {
                if (trusted) {
                    assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                } else {
                    assertThatThrownBy(() -> mailer.sync().sendMail(email()))
                            .hasRootCauseInstanceOf(IOException.class).hasStackTraceContaining("Server is not trusted: localhost");
                }
            }
        }
    }

    private static Stream<Arguments> trustedHostConfigurations() {
        return Stream.of(SMTPS, SMTP_TLS).flatMap(strategy -> Stream.of(false, true).flatMap(classFactory ->
                Stream.of(false, true).map(trusted -> Arguments.of(strategy, classFactory, trusted))));
    }

    @Test
    void ordinaryStartTlsFactoriesStillUseTheirCertificateTrustManager() throws Exception {
        assertOrdinaryFactoryAcceptsTrustedCertificate(SMTP_TLS);
    }

    @Test
    void ordinaryImplicitTlsFactoriesStillUseTheirCertificateTrustManager() throws Exception {
        assertOrdinaryFactoryAcceptsTrustedCertificate(SMTPS);
    }

    private static void assertOrdinaryFactoryAcceptsTrustedCertificate(final TransportStrategy strategy) throws Exception {
        final SSLSocketFactory application = tlsContext(testTrustStoreWithSystemRoots()).getSocketFactory();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            beginTls(peer, strategy);
            acceptAfterTls(peer, strategy);
        }); Mailer mailer = builder(server.port(), strategy).withCustomSSLFactoryInstance(application)
                .withProperty(strategy == SMTPS ? "mail.smtps.ssl.protocols" : "mail.smtp.ssl.protocols", "TLSv1.2").buildMailer()) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTPS", "SMTP_TLS"})
    void cleanupWrappingCannotBypassAnOrdinaryFactorysCertificateValidation(final String strategyName) throws Exception {
        final TransportStrategy strategy = TransportStrategy.valueOf(strategyName);
        final KeyStore emptyTrustStore = KeyStore.getInstance("JKS");
        emptyTrustStore.load(null, null);
        final SSLSocketFactory application = tlsContext(emptyTrustStore).getSocketFactory();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            try {
                beginTls(peer, strategy);
                peer.expectClosed();
            } catch (SSLHandshakeException | SocketException expectedRejection) {
                // The client's rejection can arrive as a TLS alert or a connection reset during the peer's handshake.
            }
        }); Mailer mailer = builder(server.port(), strategy).withCustomSSLFactoryInstance(application)
                .withProperty(strategy == SMTPS ? "mail.smtps.ssl.protocols" : "mail.smtp.ssl.protocols", "TLSv1.2").buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasStackTraceContaining("trustAnchors parameter must be non-empty");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SMTPS", "SMTP_TLS"})
    void cleanupWrappingPreservesTheConfiguredHostnameVerifier(final String strategyName) throws Exception {
        final TransportStrategy strategy = TransportStrategy.valueOf(strategyName);
        final MailSSLSocketFactory application = new MailSSLSocketFactory();
        application.setTrustedHosts("localhost");
        final String prefix = strategy == SMTPS ? "mail.smtps" : "mail.smtp";
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
            beginTls(peer, strategy);
            peer.expectClosed();
        }); Mailer mailer = builder(server.port(), strategy).withCustomSSLFactoryInstance(application)
                .withProperty(prefix + ".ssl.protocols", "TLSv1.2")
                .withProperty(prefix + ".ssl.hostnameverifier", (HostnameVerifier) (host, session) -> false).buildMailer()) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).hasStackTraceContaining("Unable to check server identity for: localhost");
        }
    }

    private static void beginTls(final SmtpConversation peer, final TransportStrategy strategy) throws Exception {
        if (strategy == SMTP_TLS) {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
        }
        peer.upgradeToTls();
    }

    private static void acceptAfterTls(final SmtpConversation peer, final TransportStrategy strategy) throws Exception {
        if (strategy == SMTPS) {
            peer.greet("250 localhost");
        } else {
            peer.expect("EHLO probe.example.test");
            peer.reply("250 localhost");
        }
        acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
        finishConnection(peer);
    }

    /** A generic SSL factory class may select Angus's trusted-host factory only when getDefault() is invoked. */
    public abstract static class TrustedSslFactory extends SSLSocketFactory {
        private static SSLSocketFactory application;

        public static SSLSocketFactory getDefault() {
            return application;
        }
    }

    private static MailerRegularBuilder<?> builder(final int port, final TransportStrategy strategy) {
        return factory().mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(strategy)
                .withSmtpClientHostname("probe.example.test").withSessionTimeout(5000)
                .withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withConnectionPoolClaimTimeoutMillis(5000);
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static Email email() {
        return factory().emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(new Recipient(null, "receiver@example.test", TO, null))
                .fixingMessageId("socket-factory-characterization@example.test")
                .withSubject("Local socket-factory characterization").withPlainText("Synthetic loopback test only.").buildEmail();
    }

    private static SocketFactory failingPlainFactory(final IOException failure) throws Exception {
        final SocketFactory factory = mock(SocketFactory.class);
        when(factory.createSocket()).thenThrow(failure);
        return factory;
    }

    private static Properties factoryProperties(final SocketFactory factory, final String fallback) {
        final Properties properties = new Properties();
        properties.put("mail.smtp.socketFactory", factory);
        if (!"absent".equals(fallback)) {
            properties.setProperty("mail.smtp.socketFactory.fallback", fallback);
        }
        return properties;
    }

    private static ScriptedSmtpServer acceptingPeer() throws Exception {
        return new ScriptedSmtpServer(1, SmtpSocketFactoryFallbackCharacterizationTest::acceptAndQuit);
    }

    private static void acceptAndQuit(final SmtpConversation peer) throws Exception {
        peer.greet("250 localhost");
        acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
        finishConnection(peer);
    }

    private static ServerSocket unusedPeer() throws Exception {
        return new ServerSocket(0, 1, InetAddress.getByName("localhost"));
    }

    private static void assertNoConnection(final ServerSocket server) throws Exception {
        server.setSoTimeout(100);
        assertThatThrownBy(() -> {
            try (Socket ignored = server.accept()) {
                throw new AssertionError("The rejected custom factory must not open a replacement connection");
            }
        }).isInstanceOf(SocketTimeoutException.class);
    }

    private static void closeRejectedSocket(final AtomicReference<Socket> rejectedSocket) throws IOException {
        if (rejectedSocket.get() != null) {
            rejectedSocket.get().close();
        }
    }

    private static Throwable rootCause(final Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
