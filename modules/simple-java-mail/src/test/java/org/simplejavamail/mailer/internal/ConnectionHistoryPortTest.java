package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.ConfigLoader;
import org.subethamail.wiser.Wiser;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/** Failure history must agree with the native connection, without turning unspecified ports into guessed defaults. */
@Timeout(30)
class ConnectionHistoryPortTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @EnumSource(PortRepresentation.class)
    void failedAndSkippedHistoryRetainThePortActuallyAttempted(final PortRepresentation representation) throws Exception {
        final int unavailablePort;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unavailablePort = reserved.getLocalPort();
        }
        final Wiser backupServer = Wiser.port(0);
        backupServer.start();
        final RecordingSocketFactory sockets = new RecordingSocketFactory();
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.port", "2525");
        final Properties properties = properties(defaults);
        representation.configure(properties, defaults, unavailablePort);
        properties.put("mail.smtp.socketFactory", sockets);
        properties.setProperty("mail.smtp.socketFactory.fallback", "false");
        final Properties original = (Properties) properties.clone();
        final Properties originalDefaults = (Properties) defaults.clone();
        final Session session = Session.getInstance(properties);
        final List<MailSendOutcome> outcomes = new ArrayList<>();

        try (Mailer backup = mail.mailerBuilder().withSMTPServer("localhost", backupServer.getServer().getPortAllocated())
                .withTlsConfig(TlsConfig.builder().withOpportunisticTLS(false).build())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("localhost").build())
                .withConnectionPoolConfig(pool()).buildMailer();
             Mailer primary = mail.mailerBuilder(session).withConnectionPoolConfig(pool())
                .withFailoverMailer(backup, FailoverPolicy.builder().withPrimaryConnectionAttempts(1)
                        .withPrimaryRecheckInterval(Duration.ofMinutes(10)).build())
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build()).buildMailer()) {
            assertThat(primary.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(primary.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);

            assertThat(sockets.ports).containsExactly(unavailablePort);
            assertThat(outcomes).hasSize(2);
            final List<MailSendConnectionStep> first = outcomes.get(0).getDiagnostics().orElseThrow().getConnectionHistory();
            final List<MailSendConnectionStep> second = outcomes.get(1).getDiagnostics().orElseThrow().getConnectionHistory();
            assertThat(first).hasSize(2);
            assertThat(first.get(0).isConnectionAttempted()).isTrue();
            assertThat(first.get(0).isConnectionAcquired()).isFalse();
            assertThat(first.get(0).getSmtpPort()).isEqualTo(unavailablePort);
            assertThat(second).hasSize(2);
            assertThat(second.get(0).isConnectionAttempted()).isFalse();
            assertThat(second.get(0).isConnectionAcquired()).isFalse();
            assertThat(second.get(0).getSmtpPort()).isEqualTo(unavailablePort);
            assertThat(second.get(0).getElapsed()).isNull();
            for (List<MailSendConnectionStep> history : List.of(first, second)) {
                assertThat(history.get(1).isConnectionAcquired()).isTrue();
                assertThat(history.get(1).getSmtpPort()).isEqualTo(backupServer.getServer().getPortAllocated());
                assertThat(history.get(0).toString()).doesNotContain("smtpPort", "localhost");
            }
            assertThat(backupServer.getMessages()).hasSize(2);
            assertThat(session.getProperties()).isSameAs(properties);
            original.forEach((key, value) -> assertThat(properties.get(key)).isSameAs(value));
            assertThat(defaults).isEqualTo(originalDefaults);
        } finally {
            backupServer.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("unknownPorts")
    void unknownDirectValuesDoNotReviveInheritedPortsOrGuessDefaults(final Object direct) throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.port", "2525");
        final Properties properties = properties(defaults);
        properties.put("mail.smtp.port", direct);
        try (Mailer mailer = mail.mailerBuilder(Session.getInstance(properties)).withConnectionPoolConfig(pool()).buildMailer()) {
            assertThat(((MailerImpl) mailer).logicalPort()).isNull();
            assertThat(properties.get("mail.smtp.port")).isSameAs(direct);
            assertThat(defaults.getProperty("mail.smtp.port")).isEqualTo("2525");
        }
    }

    private static Stream<Arguments> unknownPorts() {
        return Stream.of(new Object(), Long.valueOf(2526), Integer.valueOf(-1), Integer.valueOf(0), Integer.valueOf(65536),
                "-1", "0", "65536", "bad", "0X9de", " 2526", "2147483648").map(Arguments::of);
    }

    @Test
    void anAbsentPortStaysUnknownWhileRegularBuilderPortsStayExplicit() throws Exception {
        try (Mailer supplied = mail.mailerBuilder(Session.getInstance(properties(new Properties())))
                .withConnectionPoolConfig(pool()).buildMailer();
             Mailer regular = mail.mailerBuilder().withSMTPServer("localhost", 2527).withConnectionPoolConfig(pool()).buildMailer()) {
            assertThat(((MailerImpl) supplied).logicalPort()).isNull();
            assertThat(((MailerImpl) regular).logicalPort()).isEqualTo(2527);
        }
    }

    private static Properties properties(final Properties defaults) {
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.localhost", "localhost");
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        return properties;
    }

    private static ConnectionPoolConfig pool() {
        return ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build();
    }

    private Email email() {
        return mail.emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                .withPlainText("Port history").buildEmail();
    }

    private enum PortRepresentation {
        DIRECT_INTEGER { void configure(Properties p, Properties d, int port) { p.put("mail.smtp.port", port); } },
        DIRECT_HEXADECIMAL { void configure(Properties p, Properties d, int port) { p.setProperty("mail.smtp.port", "0x" + Integer.toHexString(port)); } },
        INHERITED_HEXADECIMAL { void configure(Properties p, Properties d, int port) { d.setProperty("mail.smtp.port", "0x" + Integer.toHexString(port)); } },
        DIRECT_DECIMAL { void configure(Properties p, Properties d, int port) { p.setProperty("mail.smtp.port", Integer.toString(port)); } },
        INHERITED_DECIMAL { void configure(Properties p, Properties d, int port) { d.setProperty("mail.smtp.port", Integer.toString(port)); } };
        abstract void configure(Properties direct, Properties defaults, int port);
    }

    /** Observes Angus's actual SocketFetcher endpoint before the real loopback connection fails. */
    public static final class RecordingSocketFactory extends SocketFactory {
        private final List<Integer> ports = new CopyOnWriteArrayList<>();

        @Override public Socket createSocket() {
            return new Socket() {
                @Override public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
                    ports.add(((InetSocketAddress) endpoint).getPort());
                    super.connect(endpoint, timeout);
                }
            };
        }
        @Override public Socket createSocket(String host, int port) throws IOException { return connect(host, port, null, 0); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return connect(host, port, local, localPort); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return connect(host.getHostAddress(), port, null, 0); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return connect(host.getHostAddress(), port, local, localPort); }
        private Socket connect(String host, int port, InetAddress local, int localPort) throws IOException {
            final Socket socket = createSocket();
            if (local != null) socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(host, port), 2000);
            return socket;
        }
    }
}
