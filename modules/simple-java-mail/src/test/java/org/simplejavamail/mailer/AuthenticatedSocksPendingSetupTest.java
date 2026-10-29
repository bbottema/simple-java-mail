package org.simplejavamail.mailer;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/** Real pending SOCKS exchanges remain owned by the exact physical Mailer connection. */
@Timeout(25)
class AuthenticatedSocksPendingSetupTest {

    @Test
    void cancellationClosesSilentMethodSelectionSocket() throws Exception {
        assertCancellationClosesRemote(Phase.METHOD);
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"AUTHENTICATION", "CONNECT"})
    void cancellationClosesTheExactSilentRemoteExchange(final Phase phase) throws Exception {
        assertCancellationClosesRemote(phase);
    }

    private void assertCancellationClosesRemote(final Phase phase) throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(ignored -> phase);
             Mailer mailer = builder(proxy, Duration.ZERO).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("cancel"));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).as("remote proxy reached %s", phase).isTrue();
                send.requestCancellation();

                assertThat(failure(send)).isInstanceOf(MailSendCancelledException.class);
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS))
                        .as("cancellation closes the remote %s socket", phase).isTrue();
            } finally {
                send.requestCancellation();
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"METHOD", "AUTHENTICATION", "CONNECT"})
    void totalDeadlineClosesTheRemoteSocketWithUnlimitedSetupTimeouts(final Phase phase) throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(ignored -> phase);
             Mailer mailer = builder(proxy, Duration.ZERO).withMailSendTimeout(Duration.ofSeconds(2)).buildMailer()) {
            mailer.rehearse(email("warm-up"));
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("deadline"));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).as("remote proxy reached %s", phase).isTrue();

                assertThat(failure(send)).isInstanceOf(MailSendTimeoutException.class);
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS))
                        .as("deadline closes the remote %s socket", phase).isTrue();
            } finally {
                send.requestCancellation();
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"METHOD", "AUTHENTICATION", "CONNECT"})
    void theEffectiveRawSessionConnectBudgetBoundsRemoteSetup(final Phase phase) throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(ignored -> phase);
             Mailer mailer = builder(proxy, null, Duration.ZERO)
                     .withProperty("mail.smtp.connectiontimeout", "300").buildMailer()) {
            assertThat(mailer.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("300");
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("connect-budget"));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).as("remote proxy reached %s", phase).isTrue();

                assertThat(failure(send)).isInstanceOf(MailException.class)
                        .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONNECTION));
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS))
                        .as("connect budget closes the remote %s socket", phase).isTrue();
            } finally {
                send.requestCancellation();
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"METHOD", "AUTHENTICATION", "CONNECT"})
    void aShortSmtpReadTimeoutDoesNotCreateAnEarlierRemoteSetupDeadline(final Phase phase) throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(ignored -> phase);
             Mailer mailer = builder(proxy, Duration.ofMillis(300)).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("unlimited-connect"));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).as("remote proxy reached %s", phase).isTrue();
                assertThatThrownBy(() -> send.getCompletion().get(600, MILLISECONDS))
                        .as("SMTP read timeout must not bound SOCKS setup when connect timeout is unlimited")
                        .isInstanceOf(TimeoutException.class);
                send.requestCancellation();
                assertThat(failure(send)).isInstanceOf(MailSendCancelledException.class);
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS)).isTrue();
            } finally {
                send.requestCancellation();
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smtp", "smtps"})
    void suppliedSessionNativeConnectBudgetClosesItsPendingSocket(final String protocol) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", protocol);
        properties.setProperty("mail." + protocol + ".host", "localhost");
        properties.setProperty("mail." + protocol + ".port", "2525");
        properties.setProperty("mail." + protocol + ".connectiontimeout", "300");
        properties.setProperty("mail." + protocol + ".timeout", "0");
        final Session session = Session.getInstance(properties);
        try (ProxyFixture proxy = new ProxyFixture(ignored -> Phase.METHOD);
             Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(session)
                     .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build())
                     .withProxyConfig(SocksProxyConfig.builder().withHost("localhost").withPort(proxy.port())
                             .withUsername("proxy-user").withPassword("proxy-secret").build())
                     .buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("supplied-session-" + protocol));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).as("supplied %s Session reached proxy setup", protocol).isTrue();
                assertThat(failure(send)).isInstanceOf(MailException.class)
                        .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONNECTION));
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS))
                        .as("native %s connect timeout closes its remote peer", protocol).isTrue();
            } finally {
                // A borrowed Session has no manufactured physical-abort support. Fixture teardown also bounds a failing old implementation.
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    @Test
    void cancellingPendingSetupLeavesOtherRetainedAndPooledTunnelsHealthy() throws Exception {
        final ExecutorService retainedCaller = Executors.newSingleThreadExecutor();
        final CountDownLatch healthyReady = new CountDownLatch(1);
        final CountDownLatch sendWhilePending = new CountDownLatch(1);
        final CountDownLatch healthySentWhilePending = new CountDownLatch(1);
        final CountDownLatch sendAfterCancellation = new CountDownLatch(1);
        try (ProxyFixture proxy = new ProxyFixture(number -> number == 2 ? Phase.AUTHENTICATION : Phase.HEALTHY);
             Mailer mailer = builder(proxy, Duration.ZERO).withMailSendTimeout(Duration.ofSeconds(10)).buildMailer()) {
            final CompletableFuture<Void> retained = CompletableFuture.runAsync(() -> mailer.withOpenConnection(sender -> {
                sender.sendMail(email("healthy-first"));
                healthyReady.countDown();
                await(sendWhilePending);
                sender.sendMail(email("healthy-while-another-connection-is-pending"));
                healthySentWhilePending.countDown();
                await(sendAfterCancellation);
                sender.sendMail(email("healthy-after-cancellation"));
            }), retainedCaller);
            MailSend<MailSubmissionReceipt> pending = null;
            try {
                assertThat(healthyReady.await(5, SECONDS)).isTrue();
                pending = mailer.async().sendMail(email("pending"));
                assertThat(proxy.blocked.await(5, SECONDS)).isTrue();
                sendWhilePending.countDown();
                assertThat(healthySentWhilePending.await(5, SECONDS)).isTrue();

                pending.requestCancellation();
                assertThat(failure(pending)).isInstanceOf(MailSendCancelledException.class);
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS)).isTrue();

                sendAfterCancellation.countDown();
                retained.get(5, SECONDS);
                assertThat(proxy.connections).as("one retained connection plus one pending connection").hasValue(2);
                assertThat(mailer.sync().sendMail(email("healthy-pooled-first")).getStatus()).isEqualTo(ACCEPTED);
                assertThat(mailer.sync().sendMail(email("healthy-pooled-reused")).getStatus()).isEqualTo(ACCEPTED);
                assertThat(proxy.connections).as("later pooled sends reuse their one healthy connection").hasValue(3);
                assertThat(proxy.messages).hasValue(5);
            } finally {
                if (pending != null) { pending.requestCancellation(); }
                proxy.releasePendingConnections();
                sendWhilePending.countDown();
                sendAfterCancellation.countDown();
                retained.handle((value, failure) -> failure).get(5, SECONDS);
                if (pending != null) { failure(pending); }
            }
        } finally {
            retainedCaller.shutdownNow();
            assertThat(retainedCaller.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Phase.class, names = {"REJECT_METHOD", "REJECT_AUTHENTICATION", "REJECT_CONNECT"})
    void remoteSetupRejectionsCloseTheirSocket(final Phase phase) throws Exception {
        try (ProxyFixture proxy = new ProxyFixture(ignored -> phase);
             Mailer mailer = builder(proxy, Duration.ZERO).buildMailer()) {
            final MailSend<MailSubmissionReceipt> send = mailer.async().sendMail(email("rejected-auth"));
            try {
                assertThat(proxy.blocked.await(5, SECONDS)).isTrue();
                assertThat(failure(send)).isInstanceOf(MailException.class);
                assertThat(proxy.blockedConnection().closedByPeer.await(3, SECONDS)).isTrue();
            } finally {
                send.requestCancellation();
                proxy.releasePendingConnections();
                failure(send);
            }
        }
    }

    private static MailerRegularBuilder<?> builder(final ProxyFixture proxy, final Duration readTimeout) {
        return builder(proxy, Duration.ZERO, readTimeout);
    }

    private static MailerRegularBuilder<?> builder(final ProxyFixture proxy, final Duration connectTimeout, final Duration readTimeout) {
        final ConnectionConfig.Builder connection = ConnectionConfig.builder()
                .withSmtpClientHostname("probe.example.test");
        if (connectTimeout != null) { connection.withConnectTimeout(connectTimeout); }
        if (readTimeout != null) { connection.withReadTimeout(readTimeout); }
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("localhost", 2525)
                .withConnectionConfig(connection.build())
                .withAsyncExecutionConfig(AsyncExecutionConfig.builder().withThreadPoolSize(2).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder()
                        .withCoreSize(0).withMaxSize(2).withExpireAfter(Duration.ofMinutes(1)).build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("localhost").withPort(proxy.port())
                        .withUsername("proxy-user").withPassword("proxy-secret").build());
    }

    private static Email email(final String subject) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.test").to("recipient@example.test")
                .withSubject(subject).withPlainText("Pending proxy setup").buildEmail();
    }

    private static Throwable failure(final MailSend<?> send) throws Exception {
        return send.getCompletion().handle((value, failure) -> failure).get(5, SECONDS);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(10, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating retained sends", interrupted);
        }
    }

    private enum Phase { METHOD, AUTHENTICATION, CONNECT, HEALTHY, REJECT_METHOD, REJECT_AUTHENTICATION, REJECT_CONNECT }

    /** Demands real username/password SOCKS exchanges, then acts as the destination SMTP peer. */
    private static final class ProxyFixture implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final ExecutorService workers = Executors.newFixedThreadPool(5);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final List<Connection> accepted = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger messages = new AtomicInteger();
        private final AtomicReference<Connection> pending = new AtomicReference<>();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final IntFunction<Phase> phases;

        private ProxyFixture(final IntFunction<Phase> phases) throws IOException {
            this.phases = phases;
            workers.execute(this::accept);
        }

        private int port() { return listener.getLocalPort(); }

        private void accept() {
            try {
                while (!closing.get()) {
                    final Socket socket = listener.accept();
                    sockets.add(socket);
                    final Connection connection = new Connection(socket, phases.apply(connections.incrementAndGet()));
                    accepted.add(connection);
                    workers.execute(() -> serve(connection));
                }
            } catch (IOException failure) {
                if (!closing.get()) { failures.add(failure); }
            }
        }

        private void serve(final Connection connection) {
            try (Socket socket = connection.socket) {
                socket.setSoTimeout(5000);
                final DataInputStream input = new DataInputStream(socket.getInputStream());
                final OutputStream output = socket.getOutputStream();
                assertThat(input.readUnsignedByte()).isEqualTo(5);
                input.readFully(new byte[input.readUnsignedByte()]);
                if (connection.phase == Phase.METHOD) { withholdReply(connection, input); return; }
                if (connection.phase == Phase.REJECT_METHOD) {
                    output.write(new byte[] {5, (byte) 0xff});
                    output.flush();
                    withholdReply(connection, input);
                    return;
                }
                output.write(new byte[] {5, 2});
                output.flush();

                assertThat(input.readUnsignedByte()).isEqualTo(1);
                input.readFully(new byte[input.readUnsignedByte()]);
                input.readFully(new byte[input.readUnsignedByte()]);
                if (connection.phase == Phase.AUTHENTICATION) { withholdReply(connection, input); return; }
                if (connection.phase == Phase.REJECT_AUTHENTICATION) {
                    output.write(new byte[] {1, 1});
                    output.flush();
                    withholdReply(connection, input);
                    return;
                }
                output.write(new byte[] {1, 0});
                output.flush();

                assertThat(input.readUnsignedByte()).isEqualTo(5);
                assertThat(input.readUnsignedByte()).isEqualTo(1);
                assertThat(input.readUnsignedByte()).isZero();
                final int type = input.readUnsignedByte();
                input.readFully(new byte[type == 1 ? 4 : type == 4 ? 16 : input.readUnsignedByte()]);
                input.readUnsignedShort();
                if (connection.phase == Phase.CONNECT) { withholdReply(connection, input); return; }
                if (connection.phase == Phase.REJECT_CONNECT) {
                    output.write(new byte[] {5, 5, 0, 1, 127, 0, 0, 1, 0, 25});
                    output.flush();
                    withholdReply(connection, input);
                    return;
                }
                output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 25});
                output.flush();
                serveSmtp(socket);
            } catch (Throwable failure) {
                if (!closing.get() && !connection.releasedByFixture.get()) { failures.add(failure); }
            } finally {
                sockets.remove(connection.socket);
            }
        }

        private void withholdReply(final Connection connection, final DataInputStream input) throws IOException {
            connection.socket.setSoTimeout(0);
            pending.set(connection);
            blocked.countDown();
            try {
                while (input.read() != -1) { /* Wait for this exact client-owned connection to close. */ }
                if (!connection.releasedByFixture.get()) { connection.closedByPeer.countDown(); }
            } catch (SocketException | EOFException closed) {
                if (!closing.get() && !connection.releasedByFixture.get()) { connection.closedByPeer.countDown(); }
            }
        }

        private void serveSmtp(final Socket socket) throws Exception {
            final SmtpConversation peer = new SmtpConversation(socket);
            peer.setReadTimeoutMillis(10000);
            peer.greet("250 localhost");
            String command;
            while ((command = peer.readLine()) != null) {
                if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")
                        || command.equals("NOOP") || command.equals("RSET")) {
                    peer.reply("250 ok");
                } else if (command.equals("DATA")) {
                    peer.reply("354 send content");
                    String line;
                    do { line = peer.readLine(); } while (line != null && !line.equals("."));
                    assertThat(line).isEqualTo(".");
                    messages.incrementAndGet();
                    peer.reply("250 queued");
                } else if (command.equals("QUIT")) {
                    peer.reply("221 bye");
                    return;
                } else {
                    throw new AssertionError("Unexpected SMTP command: " + command);
                }
            }
        }

        private Connection blockedConnection() {
            return pending.get();
        }

        private void releasePendingConnections() throws IOException {
            for (final Connection connection : accepted) {
                if (connection.phase != Phase.HEALTHY) {
                    connection.releasedByFixture.set(true);
                    connection.socket.close();
                }
            }
        }

        @Override public void close() throws Exception {
            closing.set(true);
            listener.close();
            for (final Socket socket : sockets) { socket.close(); }
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).as("proxy fixture workers stopped").isTrue();
            assertThat(failures).as("proxy fixture failures").isEmpty();
        }

        private static final class Connection {
            private final Socket socket;
            private final Phase phase;
            private final AtomicBoolean releasedByFixture = new AtomicBoolean();
            private final CountDownLatch closedByPeer = new CountDownLatch(1);

            private Connection(final Socket socket, final Phase phase) {
                this.socket = socket;
                this.phase = phase;
            }
        }
    }
}
