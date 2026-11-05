package org.simplejavamail.mailer.failover;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;
import testutil.socks.AuthenticatedSocksTestPeer;
import testutil.smtp.RecordingSmtpServer;

import java.util.ArrayDeque;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.mailer.MailFailureKind.CONNECTION;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.builder;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.email;
import static org.simplejavamail.mailer.failover.FailoverContractSupport.send;
import static testutil.socks.AuthenticatedSocksRouteTestSupport.requireDirectRoute;
import static java.util.concurrent.TimeUnit.SECONDS;

/** A real proxy credential rejection must retain the invoking failover policy before SMTP or TLS starts. */
@Timeout(40)
class SocksProxyAuthenticationFailoverTest {

    @ParameterizedTest
    @MethodSource("routes")
    void proxyCredentialRejectionRequiresTheAuthenticationOptIn(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean asynchronous, final boolean permitAuthenticationFailure, final boolean permitCertificateFailure) throws Exception {
        requireDirectRoute();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final FailoverPolicy.Builder policyBuilder = FailoverPolicy.builder().withPrimaryConnectionAttempts(1)
                .withFailoverOnCertificateVerificationFailure(permitCertificateFailure);
        if (permitAuthenticationFailure) { policyBuilder.withFailoverOnAuthenticationFailure(true); }
        final FailoverPolicy policy = policyBuilder.build();
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol, false);
             RecordingSmtpServer backup = new RecordingSmtpServer();
             Mailer alternative = builder(factory, backup.port()).buildMailer();
             Mailer primary = proxyBuilder(factory, proxy, protocol)
                     .withFailoverMailer(alternative, policy)
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            if (permitAuthenticationFailure) {
                assertThat(send(primary, email(), asynchronous).getStatus()).isEqualTo(ACCEPTED);
                assertThat(backup.messages()).hasSize(1);
                assertThat(outcomes).singleElement().satisfies(outcome -> {
                    assertThat(outcome.isSuccessful()).isTrue();
                    assertThat(outcome.getDiagnostics().orElseThrow().getConnectionHistory()).hasSize(2);
                });
            } else {
                final Throwable failure = catchThrowable(() -> send(primary, email(), asynchronous));
                assertThat(failure).isInstanceOf(MailException.class);
                assertThat(((MailException) failure).getKind()).isEqualTo(CONNECTION);
                assertThat(causalFailures(failure)).anyMatch(AuthenticationFailedException.class::isInstance);
                assertThat(backup.connectionCount()).isZero();
                assertThat(backup.messages()).isEmpty();
                assertThat(outcomes).singleElement().satisfies(outcome -> assertThat(outcome.isSuccessful()).isFalse());
            }
            assertThat(proxy.accepted()).isEqualTo(1);
            assertThat(proxy.authenticationAttempts()).isEqualTo(1);
            assertThat(proxy.authenticated()).isZero();
            assertThat(proxy.messages()).isZero();
            assertThat(proxy.tlsHandshakes()).isZero();
            proxy.assertHealthy();
        }
    }

    @ParameterizedTest
    @EnumSource(AuthenticatedSocksTestPeer.Protocol.class)
    void authenticationPermissionDoesNotAuthorizeMalformedProxyReplies(final AuthenticatedSocksTestPeer.Protocol protocol) throws Exception {
        requireDirectRoute();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol, false, 0, 2, null);
             RecordingSmtpServer backup = new RecordingSmtpServer();
             Mailer alternative = builder(factory, backup.port()).buildMailer();
             Mailer primary = proxyBuilder(factory, proxy, protocol).withFailoverMailer(alternative,
                     FailoverPolicy.builder().withPrimaryConnectionAttempts(1).withFailoverOnAuthenticationFailure(true)
                             .withFailoverOnCertificateVerificationFailure(true).build()).buildMailer()) {
            final Throwable failure = catchThrowable(() -> primary.sync().sendMail(email()));
            assertThat(failure).isInstanceOf(MailException.class);
            assertThat(causalFailures(failure)).noneMatch(AuthenticationFailedException.class::isInstance);
            assertThat(backup.connectionCount()).isZero();
            assertThat(proxy.authenticationAttempts()).isEqualTo(1);
            assertThat(proxy.messages()).isZero();
            proxy.assertHealthy();
        }
    }

    @Test
    void cachedProxyRejectionRetainsAuthenticationEvidenceWithoutAnotherConnection() throws Exception {
        requireDirectRoute();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(AuthenticatedSocksTestPeer.Protocol.SMTP, false);
             RecordingSmtpServer backup = new RecordingSmtpServer();
             Mailer alternative = builder(factory, backup.port()).buildMailer();
             Mailer primary = proxyBuilder(factory, proxy, AuthenticatedSocksTestPeer.Protocol.SMTP)
                     .withFailoverMailer(alternative, FailoverPolicy.builder().withPrimaryConnectionAttempts(1)
                             .withPrimaryRecheckInterval(Duration.ofHours(1)).withFailoverOnAuthenticationFailure(true).build())
                     .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            assertThat(primary.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.authenticationAttempts()).isEqualTo(1);
            assertThat(backup.messages()).hasSize(2);
            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory().get(0).isConnectionAttempted()).isTrue();
            assertThat(outcomes.get(1).getDiagnostics().orElseThrow().getConnectionHistory().get(0).isConnectionAttempted()).isFalse();
            proxy.assertHealthy();
        }
    }

    @Test
    void concurrentProxyRejectionsDoNotMixAuthenticationAndUnknownFailures() throws Exception {
        requireDirectRoute();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final CyclicBarrier authenticationBarrier = new CyclicBarrier(2);
        final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(1)
                .withFailoverOnAuthenticationFailure(true).build();
        try (AuthenticatedSocksTestPeer rejected = new AuthenticatedSocksTestPeer(AuthenticatedSocksTestPeer.Protocol.SMTP, false, 0, 1, authenticationBarrier);
             AuthenticatedSocksTestPeer malformed = new AuthenticatedSocksTestPeer(AuthenticatedSocksTestPeer.Protocol.SMTP, false, 0, 2, authenticationBarrier);
             RecordingSmtpServer allowedBackup = new RecordingSmtpServer();
             RecordingSmtpServer forbiddenBackup = new RecordingSmtpServer();
             Mailer permitted = builder(factory, allowedBackup.port()).buildMailer();
             Mailer forbidden = builder(factory, forbiddenBackup.port()).buildMailer();
             Mailer known = proxyBuilder(factory, rejected, AuthenticatedSocksTestPeer.Protocol.SMTP).withFailoverMailer(permitted, policy).buildMailer();
             Mailer unknown = proxyBuilder(factory, malformed, AuthenticatedSocksTestPeer.Protocol.SMTP).withFailoverMailer(forbidden, policy).buildMailer()) {
            final var knownSend = known.async().sendMail(email());
            final var unknownSend = unknown.async().sendMail(email());
            assertThat(knownSend.getCompletion().get(12, SECONDS).getStatus()).isEqualTo(ACCEPTED);
            final Throwable failure = catchThrowable(() -> unknownSend.getCompletion().get(12, SECONDS));
            assertThat(failure).isNotNull();
            assertThat(causalFailures(failure)).noneMatch(AuthenticationFailedException.class::isInstance);
            assertThat(allowedBackup.messages()).hasSize(1);
            assertThat(forbiddenBackup.connectionCount()).isZero();
            assertThat(rejected.authenticationAttempts()).isEqualTo(1);
            assertThat(malformed.authenticationAttempts()).isEqualTo(1);
            rejected.assertHealthy();
            malformed.assertHealthy();
        }
    }

    private static MailerRegularBuilder<?> proxyBuilder(final SimpleJavaMail factory, final AuthenticatedSocksTestPeer proxy,
            final AuthenticatedSocksTestPeer.Protocol protocol) {
        return builder(factory, 2525).withSMTPServer("primary.example.test", 2525).withTransportStrategy(strategy(protocol))
                .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(proxy.port())
                        .withUsername("proxy-user").withPassword("proxy-secret").build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build());
    }

    private static Set<Throwable> causalFailures(final Throwable failure) {
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            final Throwable current = pending.removeFirst();
            if (!visited.add(current)) { continue; }
            if (current.getCause() != null) { pending.add(current.getCause()); }
            if (current instanceof MessagingException && ((MessagingException) current).getNextException() != null) {
                pending.add(((MessagingException) current).getNextException());
            }
        }
        return visited;
    }

    private static TransportStrategy strategy(final AuthenticatedSocksTestPeer.Protocol protocol) {
        switch (protocol) {
            case SMTP: return TransportStrategy.SMTP;
            case STARTTLS: return TransportStrategy.SMTP_TLS;
            case SMTPS: return TransportStrategy.SMTPS;
            default: throw new AssertionError(protocol);
        }
    }

    private static Stream<Arguments> routes() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol ->
                Stream.of(false, true).flatMap(asynchronous -> Stream.of(false, true).flatMap(permitted -> Stream.of(false, true)
                        .map(certificate -> Arguments.of(protocol, asynchronous, permitted, certificate)))));
    }
}
