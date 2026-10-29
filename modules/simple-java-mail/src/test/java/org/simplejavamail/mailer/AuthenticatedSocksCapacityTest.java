package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/** Exercises setup capacity independently of the number of live retained forwarding tunnels. */
@Timeout(90)
class AuthenticatedSocksCapacityTest {
    private static final int RETAINED_CONNECTIONS = 100;
    private static final String PROXY_USER = "capacity-user";
    private static final String CONTROL_USER = "fixture-control";
    private static final String PROXY_PASSWORD = "capacity-secret";
    private static final int SMTP_PORT = 2525;

    @Test
    void anotherSetupCanProgressWhileOneHundredRetainedConnectionsRemainOpen() throws Exception {
        final ExecutorService callers = Executors.newFixedThreadPool(RETAINED_CONNECTIONS + 1);
        final CountDownLatch reuseRetained = new CountDownLatch(1);
        final CountDownLatch releaseRetained = new CountDownLatch(1);
        final CountDownLatch reused = new CountDownLatch(RETAINED_CONNECTIONS);
        final AtomicInteger liveCallbacks = new AtomicInteger();
        final Set<Integer> bridgePorts = ConcurrentHashMap.newKeySet();
        final List<CompletableFuture<Void>> retained = new ArrayList<>();
        try (ProxySmtpFixture fixture = new ProxySmtpFixture()) {
            final Mailer mailer = mailer(fixture);
            Throwable primary = null;
            try {
                final long establishmentDeadline = System.nanoTime() + SECONDS.toNanos(30);
                for (int index = 0; index < RETAINED_CONNECTIONS; index++) {
                    final int connection = index;
                    final CompletableFuture<Void> opened = new CompletableFuture<>();
                    retained.add(CompletableFuture.runAsync(() -> {
                        try {
                            mailer.withOpenConnection(sender -> {
                                assertThat(sender.sendMail(email(connection, "first")).getStatus()).isEqualTo(ACCEPTED);
                                liveCallbacks.incrementAndGet();
                                bridgePorts.add(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port")));
                                opened.complete(null);
                                try {
                                    assertThat(reuseRetained.await(60, SECONDS)).as("retained scope receives its reuse signal").isTrue();
                                    assertThat(sender.sendMail(email(connection, "reuse")).getStatus()).isEqualTo(ACCEPTED);
                                    reused.countDown();
                                    assertThat(releaseRetained.await(30, SECONDS)).as("retained scope receives its release signal").isTrue();
                                } finally {
                                    liveCallbacks.decrementAndGet();
                                }
                            });
                        } catch (Exception | Error failure) {
                            opened.completeExceptionally(failure);
                            throw new CompletionException(failure);
                        }
                    }, callers));
                    // Establish scopes individually: pending-admission burst limits are a separate concern.
                    opened.get(Math.max(1, establishmentDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }

                assertThat(liveCallbacks).hasValue(RETAINED_CONNECTIONS);
                assertThat(fixture.sjmConnections).hasValue(RETAINED_CONNECTIONS);
                assertThat(fixture.sjmMessages).hasValue(RETAINED_CONNECTIONS);
                assertThat(bridgePorts).as("one bridge listener generation remains held").hasSize(1);
                fixture.assertHealthy();
                System.out.println("SOCKS_CAPACITY retained-ready " + fixture.counts(liveCallbacks, reused));

                fixture.sendDirectControlMessage();
                assertThat(fixture.controlMessages).hasValue(1);
                assertThat(fixture.peakPeers.get()).as("the independent fixture serves more than100 simultaneous peers")
                        .isGreaterThanOrEqualTo(RETAINED_CONNECTIONS + 1);
                assertThat(liveCallbacks).hasValue(RETAINED_CONNECTIONS);
                fixture.assertHealthy();
                System.out.println("SOCKS_CAPACITY fixture-headroom " + fixture.counts(liveCallbacks, reused));

                final CompletableFuture<Void> extra = CompletableFuture.runAsync(() -> {
                    try {
                        mailer.withOpenConnection(sender -> {
                            bridgePorts.add(Integer.parseInt(mailer.getSession().getProperty("mail.smtp.socks.port")));
                            assertThat(sender.sendMail(email(RETAINED_CONNECTIONS, "extra")).getStatus()).isEqualTo(ACCEPTED);
                        });
                    } catch (Exception | Error failure) {
                        throw new CompletionException(failure);
                    }
                }, callers);
                Throwable extraFailure = null;
                try {
                    extra.get(12, SECONDS);
                } catch (ExecutionException failure) {
                    extraFailure = failure.getCause();
                } catch (TimeoutException failure) {
                    extraFailure = failure;
                }
                assertThat(liveCallbacks).as("all original scopes stay held through the101st attempt").hasValue(RETAINED_CONNECTIONS);
                assertThat(retained).allSatisfy(scope -> assertThat(scope.isDone()).isFalse());

                reuseRetained.countDown();
                assertThat(reused.await(15, SECONDS)).as("all original SMTP connections remain usable").isTrue();
                assertThat(liveCallbacks).hasValue(RETAINED_CONNECTIONS);
                assertThat(retained).allSatisfy(scope -> assertThat(scope.isDone()).isFalse());
                assertThat(bridgePorts).as("the same listener generation serves the extra setup and retained reuse").hasSize(1);
                fixture.assertHealthy();
                System.out.println("SOCKS_CAPACITY retained-reused extraSucceeded=" + (extraFailure == null) + " "
                        + fixture.counts(liveCallbacks, reused));
                if (extraFailure != null) {
                    throw new AssertionError("The101st SJM setup failed while100 healthy retained SMTP scopes remained open; "
                            + fixture.counts(liveCallbacks, reused), extraFailure);
                }
                assertThat(fixture.sjmConnections).hasValue(RETAINED_CONNECTIONS + 1);
                assertThat(fixture.sjmMessages).hasValue(RETAINED_CONNECTIONS * 2 + 1);
            } catch (Exception | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                reuseRetained.countDown();
                releaseRetained.countDown();
                finishScopes(mailer, callers, retained, fixture, primary);
            }
        } finally {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, SECONDS)).as("application scope workers stop").isTrue();
        }
    }

    private static void finishScopes(final Mailer mailer, final ExecutorService callers,
            final List<CompletableFuture<Void>> scopes, final ProxySmtpFixture fixture, final Throwable primary) throws Exception {
        Exception cleanup = null;
        try {
            CompletableFuture.allOf(scopes.toArray(new CompletableFuture<?>[0])).get(15, SECONDS);
        } catch (Exception failure) {
            cleanup = failure;
            fixture.close();
        }
        try {
            mailer.closeAsync().get(15, SECONDS);
        } catch (Exception failure) {
            if (cleanup == null) { cleanup = failure; } else { cleanup.addSuppressed(failure); }
        } finally {
            callers.shutdownNow();
        }
        if (cleanup != null) {
            if (primary != null) { primary.addSuppressed(cleanup); } else { throw cleanup; }
        }
    }

    private static Mailer mailer(final ProxySmtpFixture fixture) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer("127.0.0.1", SMTP_PORT)
                .withConnectionConfig(ConnectionConfig.builder()
                        .withSmtpClientHostname("capacity-client.example.test")
                        .withConnectTimeout(Duration.ofSeconds(8))
                        .withReadTimeout(Duration.ofSeconds(8)).build())
                .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(fixture.listener.getLocalPort())
                        .withUsername(PROXY_USER).withPassword(PROXY_PASSWORD).build())
                .buildMailer();
    }

    private static Email email(final int connection, final String phase) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .from("sender@example.org").to("recipient@example.org")
                .withSubject("SOCKS capacity " + connection + " " + phase).withPlainText("Retained SMTP connection").buildEmail();
    }

    /** Each accepted peer receives its own worker; the direct control exercises actual fixture headroom. */
    private static final class ProxySmtpFixture implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"));
        private final AtomicInteger workerNames = new AtomicInteger();
        private final ExecutorService peers = Executors.newCachedThreadPool(task -> {
            final Thread thread = new Thread(task, "capacity-proxy-peer-" + workerNames.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        private final AtomicInteger activePeers = new AtomicInteger();
        private final AtomicInteger peakPeers = new AtomicInteger();
        private final AtomicInteger sjmConnections = new AtomicInteger();
        private final AtomicInteger sjmMessages = new AtomicInteger();
        private final AtomicInteger controlMessages = new AtomicInteger();
        private final Thread acceptor;
        private volatile boolean stopping;

        private ProxySmtpFixture() throws IOException {
            acceptor = new Thread(this::acceptPeers, "capacity-proxy-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void acceptPeers() {
            try {
                while (!stopping) {
                    final Socket socket = listener.accept();
                    sockets.add(socket);
                    peakPeers.accumulateAndGet(activePeers.incrementAndGet(), Math::max);
                    peers.execute(() -> servePeer(socket));
                }
            } catch (IOException | RuntimeException failure) {
                if (!stopping) { failures.add(failure); }
            }
        }

        private void servePeer(final Socket socket) {
            try (Socket accepted = socket) {
                accepted.setSoTimeout(60000);
                accepted.setTcpNoDelay(true);
                final InputStream input = accepted.getInputStream();
                final OutputStream output = accepted.getOutputStream();
                final boolean control = authenticate(input, output);
                readConnect(input);
                if (!control) { sjmConnections.incrementAndGet(); }
                output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 9, (byte) 221});
                output.flush();
                serveSmtp(input, output, control ? controlMessages : sjmMessages);
            } catch (Exception | AssertionError failure) {
                if (!stopping) { failures.add(failure); }
            } finally {
                sockets.remove(socket);
                activePeers.decrementAndGet();
            }
        }

        private static boolean authenticate(final InputStream input, final OutputStream output) throws IOException {
            final DataInputStream request = new DataInputStream(input);
            assertThat(request.readUnsignedByte()).isEqualTo(5);
            assertThat(request.readNBytes(request.readUnsignedByte())).contains((byte) 2);
            output.write(new byte[] {5, 2});
            output.flush();
            assertThat(request.readUnsignedByte()).isEqualTo(1);
            final String username = new String(request.readNBytes(request.readUnsignedByte()), UTF_8);
            assertThat(username).isIn(PROXY_USER, CONTROL_USER);
            assertThat(new String(request.readNBytes(request.readUnsignedByte()), UTF_8)).isEqualTo(PROXY_PASSWORD);
            output.write(new byte[] {1, 0});
            output.flush();
            return username.equals(CONTROL_USER);
        }

        private static void readConnect(final InputStream input) throws IOException {
            final DataInputStream request = new DataInputStream(input);
            assertThat(request.readNBytes(4)).containsExactly(5, 1, 0, 1);
            assertThat(request.readNBytes(4)).containsExactly(127, 0, 0, 1);
            assertThat(request.readUnsignedShort()).isEqualTo(SMTP_PORT);
        }

        private static void serveSmtp(final InputStream input, final OutputStream output, final AtomicInteger messages) throws IOException {
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input, US_ASCII));
            writeLine(output, "220 capacity-fixture.example.test ESMTP");
            String line;
            while ((line = reader.readLine()) != null) {
                final String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO ") || command.startsWith("HELO ") || command.startsWith("MAIL FROM:")
                        || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) {
                    writeLine(output, "250 OK");
                } else if (command.equals("DATA")) {
                    writeLine(output, "354 Send message");
                    while ((line = reader.readLine()) != null && !line.equals(".")) { }
                    if (line == null) { throw new EOFException("SMTP body was truncated"); }
                    messages.incrementAndGet();
                    writeLine(output, "250 queued");
                } else if (command.equals("QUIT")) {
                    writeLine(output, "221 Bye");
                    return;
                } else {
                    throw new IOException("Unexpected fixture command: " + line);
                }
            }
        }

        private void sendDirectControlMessage() throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 5000);
                socket.setSoTimeout(5000);
                final DataInputStream input = new DataInputStream(socket.getInputStream());
                final OutputStream output = socket.getOutputStream();
                output.write(new byte[] {5, 1, 2});
                output.flush();
                assertThat(input.readNBytes(2)).containsExactly(5, 2);
                final byte[] username = CONTROL_USER.getBytes(UTF_8);
                final byte[] password = PROXY_PASSWORD.getBytes(UTF_8);
                output.write(1);
                output.write(username.length);
                output.write(username);
                output.write(password.length);
                output.write(password);
                output.flush();
                assertThat(input.readNBytes(2)).containsExactly(1, 0);
                output.write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, 9, (byte) 221});
                output.flush();
                assertThat(input.readNBytes(10)).containsExactly(5, 0, 0, 1, 127, 0, 0, 1, 9, (byte) 221);
                final BufferedReader smtp = new BufferedReader(new InputStreamReader(input, US_ASCII));
                assertThat(smtp.readLine()).startsWith("220 ");
                controlCommand(output, smtp, "EHLO fixture-control.example.test", "250 ");
                controlCommand(output, smtp, "MAIL FROM:<control@example.org>", "250 ");
                controlCommand(output, smtp, "RCPT TO:<recipient@example.org>", "250 ");
                controlCommand(output, smtp, "DATA", "354 ");
                controlCommand(output, smtp, "From: control@example.org\r\nTo: recipient@example.org\r\nSubject: fixture control\r\n\r\nControl\r\n.", "250 ");
                controlCommand(output, smtp, "QUIT", "221 ");
            }
        }

        private static void controlCommand(final OutputStream output, final BufferedReader input, final String command, final String expected) throws IOException {
            writeLine(output, command);
            assertThat(input.readLine()).startsWith(expected);
        }

        private static void writeLine(final OutputStream output, final String line) throws IOException {
            output.write((line + "\r\n").getBytes(US_ASCII));
            output.flush();
        }

        private String counts(final AtomicInteger liveCallbacks, final CountDownLatch reused) {
            return "liveScopes=" + liveCallbacks.get() + ", sjmConnections=" + sjmConnections.get()
                    + ", sjmMessages=" + sjmMessages.get() + ", controlMessages=" + controlMessages.get()
                    + ", peakFixturePeers=" + peakPeers.get() + ", retainedReuses=" + (RETAINED_CONNECTIONS - reused.getCount());
        }

        private void assertHealthy() { assertThat(failures).as("independent proxy/SMTP fixture failures").isEmpty(); }

        @Override
        public void close() throws Exception {
            stopping = true;
            listener.close();
            for (final Socket socket : sockets) { socket.close(); }
            peers.shutdownNow();
            acceptor.join(5000);
            assertThat(acceptor.isAlive()).as("fixture listener stops").isFalse();
            assertThat(peers.awaitTermination(5, SECONDS)).as("fixture peer workers stop").isTrue();
        }
    }
}
