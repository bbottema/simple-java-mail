package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessage;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static testutil.smtp.SmtpTestTls.testTrustStoreWithSystemRoots;
import static testutil.smtp.SmtpTestTls.tlsContext;

/** Guards hostname rejection, failover permissions and independent TLS policies through real SMTP connections. */
@Timeout(40)
class SmtpHostnameVerifierFailoverTest {
    private static final AtomicInteger rejections = new AtomicInteger();

    static Stream<Arguments> configurations() {
        return Stream.of(SMTP_TLS, SMTPS).flatMap(strategy ->
                Stream.of(false, true).flatMap(pooled ->
                        Stream.of(false, true).flatMap(byClass ->
                                Stream.of(false, true).map(allowCertificate ->
                                        Arguments.of(strategy, pooled, byClass, allowCertificate)))));
    }

    static Stream<Arguments> tlsConfigurations() {
        return Stream.of(SMTP_TLS, SMTPS).flatMap(strategy ->
                Stream.of(false, true).map(pooled -> Arguments.of(strategy, pooled)));
    }

    @ParameterizedTest(name = "{0}, pooled={1}, class={2}, certificate opt-in={3}")
    @MethodSource("configurations")
    void rejectionOnlyReachesBackupWithCertificatePermission(TransportStrategy primaryStrategy,
            boolean pooled, boolean byClass, boolean allowCertificate) throws Exception {
        rejections.set(0);
        AtomicInteger primaryConnections = new AtomicInteger();
        AtomicInteger backupConnections = new AtomicInteger();
        AtomicInteger backupVerifications = new AtomicInteger();
        AtomicInteger acceptedMessages = new AtomicInteger();
        TransportStrategy backupStrategy = primaryStrategy == SMTPS ? SMTP_TLS : SMTPS;
        ExecutorService workers = workers(pooled);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(pooled);
            try (ScriptedSmtpServer primary = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                primaryConnections.incrementAndGet();
                beginTls(peer, primaryStrategy);
                peer.expectClosed();
            }); ScriptedSmtpServer backup = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                backupConnections.incrementAndGet();
                beginTls(peer, backupStrategy);
                readyAfterTls(peer, backupStrategy);
                for (int message = 0; message < (pooled ? 2 : 1); message++) {
                    acceptMessage(peer, "MAIL FROM:<sender@example.test>", "", "receiver@example.test");
                    acceptedMessages.incrementAndGet();
                }
                finishConnection(peer);
            }); Mailer alternative = builder(backup.port(), backupStrategy, workers)
                    .withProperties(verifierProperties(backupStrategy, (host, session) -> {
                        verifyTrustedSession(host, session);
                        backupVerifications.incrementAndGet();
                        return true;
                    })).buildMailer()) {
                MailerRegularBuilder<?> settings = builder(primary.port(), primaryStrategy, workers)
                        .withFailoverMailer(alternative, FailoverPolicy.builder()
                                .withPrimaryConnectionAttempts(1)
                                .withPrimaryRecheckInterval(Duration.ofHours(1))
                                .withFailoverOnCertificateVerificationFailure(allowCertificate).build());
                if (byClass) {
                    settings.withProperty(prefix(primaryStrategy) + ".ssl.hostnameverifier.class",
                            RejectingVerifier.class.getName());
                } else {
                    settings.withProperties(verifierProperties(primaryStrategy, new RejectingVerifier()));
                }
                try (Mailer initiating = settings.buildMailer()) {
                    assertThat(initiating.getSession().getProperty(prefix(primaryStrategy) + ".ssl.checkserveridentity"))
                            .isEqualTo("true");
                    assertThat(alternative.getSession().getProperty(prefix(backupStrategy) + ".ssl.checkserveridentity"))
                            .isEqualTo("true");
                    if (allowCertificate) {
                        assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                        // An immediate second send uses the backup while primary recovery is deferred.
                        assertThat(initiating.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
                        assertThat(acceptedMessages).hasValue(2);
                        assertThat(backupConnections).hasValue(pooled ? 1 : 2);
                        assertThat(backupVerifications).hasValue(pooled ? 1 : 2);
                    } else {
                        assertThatThrownBy(() -> initiating.sync().sendMail(email()))
                                .isInstanceOf(ConnectionSetupFailure.class).hasRootCauseInstanceOf(SSLException.class);
                        assertThat(backupConnections).hasValue(0);
                        assertThat(backupVerifications).hasValue(0);
                    }
                    assertThat(rejections).hasValue(1);
                    assertThat(primaryConnections).hasValue(1);
                }
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("tlsConfigurations")
    void callbackFaultDoesNotEnableFailover(TransportStrategy strategy, boolean pooled) throws Exception {
        assertPrimaryFaultStops(strategy, pooled, false);
    }

    @ParameterizedTest
    @MethodSource("tlsConfigurations")
    void genericTlsFailureDoesNotEnableFailover(TransportStrategy strategy, boolean pooled) throws Exception {
        assertPrimaryFaultStops(strategy, pooled, true);
    }

    private static void assertPrimaryFaultStops(TransportStrategy strategy, boolean pooled,
                                                boolean incompatibleProtocols) throws Exception {
        AtomicInteger backupConnections = new AtomicInteger();
        AtomicInteger verifierCalls = new AtomicInteger();
        IllegalStateException callbackFailure = new IllegalStateException("application verifier failed");
        ExecutorService workers = workers(pooled);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(pooled);
            try (ScriptedSmtpServer primary = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                try {
                    beginTls(peer, strategy);
                    peer.expectClosed();
                } catch (SSLException expected) {
                    if (!incompatibleProtocols)
                        throw expected;
                }
            }); ScriptedSmtpServer backup = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                backupConnections.incrementAndGet();
                throw new AssertionError("An undecided identity must not authorize failover");
            }); Mailer alternative = builder(backup.port(), strategy, workers).buildMailer();
                 Mailer initiating = builder(primary.port(), strategy, workers)
                         .withProperties(verifierProperties(strategy, (host, session) -> {
                             verifyTrustedSession(host, session);
                             verifierCalls.incrementAndGet();
                             throw callbackFailure;
                         }))
                         .withProperty(prefix(strategy) + ".ssl.protocols", incompatibleProtocols ? "TLSv1.3" : "TLSv1.2")
                         .withFailoverMailer(alternative, FailoverPolicy.builder()
                                 .withFailoverOnCertificateVerificationFailure(true).build()).buildMailer()) {
                Throwable failure = catchThrowable(() -> initiating.sync().sendMail(email()));
                assertThat(failure).isInstanceOf(ConnectionSetupFailure.class);
                if (incompatibleProtocols) {
                    assertThat(failure).hasRootCauseInstanceOf(SSLException.class);
                    assertThat(verifierCalls).hasValue(0);
                } else {
                    assertThat(failure).hasRootCause(callbackFailure);
                    assertThat(verifierCalls).hasValue(1);
                }
                assertThat(backupConnections).hasValue(0);
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("tlsConfigurations")
    void backupStillEnforcesItsOwnVerifier(TransportStrategy strategy, boolean pooled) throws Exception {
        AtomicInteger primaryVerifications = new AtomicInteger();
        AtomicInteger backupVerifications = new AtomicInteger();
        ExecutorService workers = workers(pooled);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(pooled);
            try (ScriptedSmtpServer primary = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                beginTls(peer, strategy);
                peer.expectClosed();
            }); ScriptedSmtpServer backup = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                beginTls(peer, strategy);
                peer.expectClosed();
            }); Mailer alternative = builder(backup.port(), strategy, workers)
                    .withProperties(verifierProperties(strategy, (host, session) -> {
                        verifyTrustedSession(host, session);
                        backupVerifications.incrementAndGet();
                        return false;
                    })).buildMailer();
                 Mailer initiating = builder(primary.port(), strategy, workers)
                         .withProperties(verifierProperties(strategy, (host, session) -> {
                             verifyTrustedSession(host, session);
                             primaryVerifications.incrementAndGet();
                             return false;
                         }))
                         .withFailoverMailer(alternative, FailoverPolicy.builder()
                                 .withFailoverOnCertificateVerificationFailure(true).build()).buildMailer()) {
                assertThatThrownBy(() -> initiating.sync().sendMail(email()))
                        .hasRootCauseInstanceOf(SSLPeerUnverifiedException.class);
                assertThat(primaryVerifications).hasValue(1);
                assertThat(backupVerifications).hasValue(1);
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("tlsConfigurations")
    void cancellationBeforeRejectionDoesNotUseBackup(TransportStrategy strategy, boolean pooled) throws Exception {
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch finishVerification = new CountDownLatch(1);
        AtomicInteger backupConnections = new AtomicInteger();
        ExecutorService workers = workers(pooled);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(pooled);
            try (ScriptedSmtpServer primary = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                beginTls(peer, strategy);
                peer.expectClosed();
            }); ScriptedSmtpServer backup = new ScriptedSmtpServer(StandardCharsets.US_ASCII, peer -> {
                backupConnections.incrementAndGet();
                throw new AssertionError("A cancelled operation must not use backup");
            }); Mailer alternative = builder(backup.port(), strategy, workers).buildMailer();
                 Mailer initiating = builder(primary.port(), strategy, workers)
                         .withProperties(verifierProperties(strategy, (host, session) -> {
                             verifyTrustedSession(host, session);
                             verifying.countDown();
                             try {
                                 assertThat(finishVerification.await(5, SECONDS)).isTrue();
                             } catch (InterruptedException interrupted) {
                                 Thread.currentThread().interrupt();
                             }
                             return false;
                         }))
                         .withFailoverMailer(alternative, FailoverPolicy.builder()
                                 .withFailoverOnCertificateVerificationFailure(true).build()).buildMailer()) {
                try {
                    var sending = initiating.async().sendMail(email());
                    assertThat(verifying.await(5, SECONDS)).isTrue();
                    sending.requestCancellation();
                    finishVerification.countDown();
                    assertThatThrownBy(() -> sending.getCompletion().get(10, SECONDS))
                            .hasCauseInstanceOf(MailSendCancelledException.class);
                    assertThat(backupConnections).hasValue(0);
                } finally {
                    finishVerification.countDown();
                }
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    /** Give both the initiating thread and SJM workers the same optional module boundary. */
    private static ExecutorService workers(boolean pooled) {
        return Executors.newFixedThreadPool(2, task -> new Thread(() -> {
            try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
                modules.when(ModuleLoader::batchModuleAvailable).thenReturn(pooled);
                task.run();
            }
        }, "hostname-verifier-downstream"));
    }

    private static MailerRegularBuilder<?> builder(int port, TransportStrategy strategy,
                                                   ExecutorService workers) throws Exception {
        SSLSocketFactory application = tlsContext(testTrustStoreWithSystemRoots()).getSocketFactory();
        return factory().mailerBuilder().withSMTPServer("localhost", port).withTransportStrategy(strategy)
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test")
                        .withSessionTimeout(Duration.ofSeconds(5)).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1)
                        .withClaimTimeout(Duration.ofSeconds(5)).build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withExecutorService(workers).build())
                .withTlsConfig(TlsConfig.builder().withCustomSSLFactoryInstance(application).build())
                .withProperty(prefix(strategy) + ".ssl.protocols", "TLSv1.2")
                .withProperty(prefix(strategy) + ".ssl.checkserveridentity", "true");
    }

    private static String prefix(TransportStrategy strategy) {
        return strategy == SMTPS ? "mail.smtps" : "mail.smtp";
    }

    /** withProperty converts values to text; Properties retains verifier objects. */
    private static Properties verifierProperties(TransportStrategy strategy, HostnameVerifier verifier) {
        Properties properties = new Properties();
        properties.put(prefix(strategy) + ".ssl.hostnameverifier", verifier);
        return properties;
    }

    private static void beginTls(SmtpConversation peer, TransportStrategy strategy) throws Exception {
        if (strategy == SMTP_TLS) {
            peer.greet("250-localhost\r\n250 STARTTLS");
            peer.expect("STARTTLS");
            peer.reply("220 begin TLS");
        }
        peer.upgradeToTls();
    }

    private static void readyAfterTls(SmtpConversation peer, TransportStrategy strategy) throws Exception {
        if (strategy == SMTPS) {
            peer.greet("250 localhost");
        } else {
            peer.expect("EHLO probe.example.test");
            peer.reply("250 localhost");
        }
    }

    private static void verifyTrustedSession(String host, SSLSession session) {
        assertThat(host).isEqualTo("localhost");
        assertThat(session.isValid()).isTrue();
        try {
            ((X509Certificate) session.getPeerCertificates()[0]).checkValidity();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }


    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }

    private static Email email() {
        return factory().emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                .fixingMessageId("<hostname-verifier-probe@example.test>").withPlainText("loopback TLS probe").buildEmail();
    }

    public static class RejectingVerifier implements HostnameVerifier {
        public RejectingVerifier() { }

        @Override
        public boolean verify(String host, SSLSession session) {
            verifyTrustedSession(host, session);
            rejections.incrementAndGet();
            return false;
        }
    }
}
