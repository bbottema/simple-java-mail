package org.simplejavamail.mailer;

import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import testutil.ConfigLoaderTestHelper;
import testutil.socks.AuthenticatedSocksTestPeer;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static testutil.socks.AuthenticatedSocksRouteTestSupport.requireReleasedBridge;

/** Released Angus remains a supported bridge route while the merged-source lane is opt-in. */
@Timeout(30)
class AuthenticatedSocksReleasedBridgeTest {
    @BeforeEach
    void requiresTheReleasedDependencyLane() { requireReleasedBridge(); }

    @ParameterizedTest(name = "released bridge {0}, suppliedSession={1}")
    @MethodSource("routes")
    void fixedBridgePortKeepsRealSendsProbesAndRetainedReuseWorking(final AuthenticatedSocksTestPeer.Protocol protocol,
            final boolean supplied) throws Exception {
        final String transport = protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS ? "smtps" : "smtp";
        final String prefix = "mail." + transport;
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", transport);
        properties.setProperty(prefix + ".host", "localhost");
        properties.setProperty(prefix + ".port", "2525");
        if (protocol == AuthenticatedSocksTestPeer.Protocol.STARTTLS) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
        }
        final Session original = supplied ? Session.getInstance(properties) : null;
        final Provider provider = original == null ? null : original.getProvider(transport);
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        final int bridgePort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            bridgePort = reservation.getLocalPort();
        }
        try (AuthenticatedSocksTestPeer proxy = new AuthenticatedSocksTestPeer(protocol)) {
            final MailerGenericBuilder<?> builder = original == null
                    ? mail.mailerBuilder().withSMTPServer("localhost", 2525).withTransportStrategy(protocol == AuthenticatedSocksTestPeer.Protocol.SMTPS
                            ? TransportStrategy.SMTPS : protocol == AuthenticatedSocksTestPeer.Protocol.STARTTLS
                            ? TransportStrategy.SMTP_TLS : TransportStrategy.SMTP)
                    : mail.mailerBuilder(original);
            try (Mailer mailer = builder.withConnectionConfig(ConnectionConfig.builder().withConnectTimeout(Duration.ofSeconds(5))
                            .withReadTimeout(Duration.ofSeconds(5)).withSmtpClientHostname("released-bridge.example.test").build())
                    .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(2).build())
                    .withTlsConfig(TlsConfig.builder().trustingSSLHosts("localhost").verifyingServerIdentity(true).build())
                    .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(proxy.port())
                            .withUsername("proxy-user").withPassword("proxy-secret").withBridgePort(bridgePort).build()).buildMailer()) {
                assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(mailer.getSession()))
                        .as("released Angus has no merged transport hook").isFalse();
                assertThat(proxy.accepted()).as("offline bridge construction opens no remote socket").isZero();
                assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(proxy.port());
                assertThat(mailer.getProxyConfig().getProxyBridgePort()).isEqualTo(bridgePort);
                if (original != null) {
                    assertThat(mailer.getSession()).isSameAs(original);
                    assertThat(original.getProvider(transport)).isSameAs(provider);
                }
                final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                        .withSubject("Released bridge control").withPlainText("A retained bridge route remains supported").buildEmail();
                assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
                assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
                assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
                assertThat(proxy.accepted()).as("the independent probe leaves the pooled sender reusable").isEqualTo(2);
                mailer.withOpenConnection(sender -> {
                    assertThat(sender.sendMail(email).getStatus()).isEqualTo(ACCEPTED);
                    assertThat(sender.sendMail(email).getStatus()).isEqualTo(ACCEPTED);
                });
                assertThat(proxy.messages()).isEqualTo(4);
                assertThat(proxy.accepted()).isEqualTo(3);
                assertThat(mailer.getSession().getProperty(prefix + ".socks.host")).isEqualTo("127.0.0.1");
                assertThat(mailer.getSession().getProperty(prefix + ".socks.port")).isEqualTo(Integer.toString(bridgePort));
                assertThat(mailer.getSession().getProperties()).doesNotContainKey(prefix + ".socketFactory.transport");
                if (original != null) { assertThat(original.getProvider(transport)).isSameAs(provider); }
                proxy.assertHealthy();
            }
        }
    }

    private static Stream<Arguments> routes() {
        return Stream.of(AuthenticatedSocksTestPeer.Protocol.values()).flatMap(protocol ->
                Stream.of(Arguments.of(protocol, false), Arguments.of(protocol, true)));
    }
}
