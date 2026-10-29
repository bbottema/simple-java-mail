package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class LocalSocksRequestValidationTest {
    private static final byte[] CONNECT = {5, 1, 0, 1, 127, 0, 0, 1, 0, 25};

    @ParameterizedTest(name = "{0}")
    @MethodSource("unacceptableGreetings")
    void refusesOnlyUnofferedAnonymousAuthenticationBeforeRemoteEffects(final String scenario, final byte[] greeting) throws IOException {
        final Attempt attempt = new Attempt(join(greeting, CONNECT), null);

        attempt.run();

        assertThat(attempt.remoteConnections).hasValue(0);
        assertThat(attempt.local.response.toByteArray()).containsExactly(5, 255);
        assertThat(attempt.local.closes).hasValue(1);
    }

    private static Stream<Arguments> unacceptableGreetings() {
        return Stream.of(
                Arguments.of("no offered method", new byte[] {5, 0}),
                Arguments.of("credentials only", new byte[] {5, 1, 2}),
                Arguments.of("unsupported methods", new byte[] {5, 3, 1, (byte) 128, (byte) 255}));
    }

    @ParameterizedTest(name = "anonymous offer {0}")
    @MethodSource("anonymousGreetings")
    void acceptsAnonymousWhereverItIsOffered(final int order, final byte[] greeting) throws IOException {
        final Attempt attempt = new Attempt(join(greeting, CONNECT), null);

        attempt.run();

        assertThat(attempt.remoteConnections).hasValue(1);
        assertThat(Arrays.copyOf(attempt.local.response.toByteArray(), 2)).containsExactly(5, 0);
        assertThat(attempt.local.closes).hasValue(1);
    }

    private static Stream<Arguments> anonymousGreetings() {
        return Stream.of(Arguments.of(1, new byte[] {5, 1, 0}), Arguments.of(2, new byte[] {5, 2, 2, 0}),
                Arguments.of(3, new byte[] {5, 4, (byte) 128, 0, 2, 0}));
    }

    @ParameterizedTest(name = "greeting truncated after {0} bytes")
    @MethodSource("truncatedGreetings")
    void neverSelectsAMethodFromAnIncompleteGreeting(final int length, final byte[] greeting) throws IOException {
        final Attempt attempt = new Attempt(greeting, null);

        attempt.run();

        assertThat(attempt.remoteConnections).hasValue(0);
        assertThat(attempt.local.response.size()).isZero();
        assertThat(attempt.local.closes).hasValue(1);
    }

    private static Stream<Arguments> truncatedGreetings() {
        final byte[] complete = {5, 2, 0, 2};
        return IntStream.range(0, complete.length).mapToObj(length -> Arguments.of(length, Arrays.copyOf(complete, length)));
    }

    @Test
    void anUnsupportedGreetingVersionStillClosesWithoutRemoteEffects() throws IOException {
        final Attempt attempt = new Attempt(join(new byte[] {4, 1, 0}, CONNECT), null);
        attempt.run();
        assertThat(attempt.remoteConnections).hasValue(0);
        assertThat(attempt.local.response.size()).isZero();
        assertThat(attempt.local.closes).hasValue(1);
    }

    @ParameterizedTest(name = "CONNECT version={0}, reserved={1}")
    @MethodSource("invalidHeaders")
    void malformedConnectHeadersCannotReachTheRemoteProxy(final int version, final int reserved) throws IOException {
        final byte[] command = CONNECT.clone();
        command[0] = (byte) version;
        command[2] = (byte) reserved;
        final Attempt attempt = new Attempt(join(new byte[] {5, 1, 0}, command), null);

        attempt.run();

        assertThat(attempt.remoteConnections).hasValue(0);
        assertThat(attempt.local.response.toByteArray()).containsExactly(5, 0);
        assertThat(attempt.local.closes).hasValue(1);
    }

    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(Arguments.of(4, 0), Arguments.of(0, 0), Arguments.of(255, 0),
                Arguments.of(5, 1), Arguments.of(5, 128), Arguments.of(5, 255));
    }

    @Test
    void rejectingAReplyWriteFailureRetainsTheCauseAndClosesThePeer() throws IOException {
        final IOException failure = new IOException("fixture rejected-method response write failed");
        final Attempt attempt = new Attempt(join(new byte[] {5, 1, 2}, CONNECT), failure);
        attempt.run();
        assertThat(attempt.remoteConnections).hasValue(0);
        assertThat(attempt.tunnel.failure()).isSameAs(failure);
        assertThat(attempt.local.closes).hasValue(1);
    }

    @Test
    void bindAndUdpCommandsRemainOutsideTheAdapterContract() throws IOException {
        for (int command : new int[] {2, 3}) {
            final byte[] request = CONNECT.clone();
            request[1] = (byte) command;
            final Attempt attempt = new Attempt(join(new byte[] {5, 1, 0}, request), null);
            attempt.run();
            assertThat(attempt.remoteConnections).hasValue(0);
            assertThat(attempt.local.closes).hasValue(1);
        }
    }

    @Test
    void rejectingAnotherPeerLeavesAHealthyForwardingTunnelUsable() throws Exception {
        final ExecutorService peerWorker = Executors.newSingleThreadExecutor();
        final AtomicInteger remoteConnections = new AtomicInteger();
        try (ServerSocket echo = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            echo.setSoTimeout(5000);
            final CompletableFuture<Void> echoDone = CompletableFuture.runAsync(() -> {
                try (Socket socket = echo.accept()) {
                    socket.setSoTimeout(10000);
                    int value;
                    while ((value = socket.getInputStream().read()) >= 0) {
                        socket.getOutputStream().write(value);
                        socket.getOutputStream().flush();
                    }
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, peerWorker);
            final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl((id, target, owner) -> {
                remoteConnections.incrementAndGet();
                final Socket remote = new Socket();
                owner.own(remote);
                remote.connect(new InetSocketAddress("127.0.0.1", echo.getLocalPort()), 2000);
                return remote;
            }, 0);
            server.start();
            try (Socket healthy = client(server)) {
                healthy.getOutputStream().write(new byte[] {5, 1, 0});
                healthy.getOutputStream().flush();
                assertThat(healthy.getInputStream().readNBytes(2)).containsExactly(5, 0);
                healthy.getOutputStream().write(CONNECT);
                healthy.getOutputStream().flush();
                assertThat(healthy.getInputStream().readNBytes(10)).startsWith((byte) 5, (byte) 0);
                healthy.getOutputStream().write(41);
                healthy.getOutputStream().flush();
                assertThat(healthy.getInputStream().read()).isEqualTo(41);

                try (Socket rejected = client(server)) {
                    rejected.getOutputStream().write(new byte[] {5, 1, 2});
                    rejected.getOutputStream().flush();
                    assertThat(rejected.getInputStream().readNBytes(2)).containsExactly(5, 255);
                    assertClosed(rejected);
                }
                healthy.getOutputStream().write(42);
                healthy.getOutputStream().flush();
                assertThat(healthy.getInputStream().read()).isEqualTo(42);
                assertThat(remoteConnections).hasValue(1);
                assertThat(server.isRunning()).isTrue();
            } finally {
                server.stop();
            }
            echoDone.get(5, SECONDS);
        } finally {
            peerWorker.shutdownNow();
        }
    }

    private static Socket client(final AnonymousSocks5ServerImpl server) throws IOException {
        final Socket socket = new Socket("127.0.0.1", server.getLocalPort());
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void assertClosed(final Socket socket) throws IOException {
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketException closedPeer) {
            // A TCP reset also establishes that the rejected peer was retired.
        }
    }

    private static byte[] join(final byte[] first, final byte[] second) {
        final byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static final class Attempt {
        private final ProbeSocket local;
        private final AtomicInteger remoteConnections = new AtomicInteger();
        private final SocksTunnel tunnel;

        private Attempt(final byte[] request, final IOException replyFailure) {
            local = new ProbeSocket(request, replyFailure);
            tunnel = new SocksTunnel(local, () -> { });
        }

        private void run() throws IOException {
            new Socks5Handler(new SocksSession(local), (id, target, owner) -> {
                remoteConnections.incrementAndGet();
                throw new IOException("fixture remote refusal after valid local framing");
            }, tunnel).run();
        }
    }

    private static final class ProbeSocket extends Socket {
        private final InputStream request;
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        private final OutputStream output;
        private final AtomicInteger closes = new AtomicInteger();
        private volatile boolean closed;

        private ProbeSocket(final byte[] request, final IOException replyFailure) {
            this.request = new ByteArrayInputStream(request);
            output = new OutputStream() {
                @Override public void write(final int value) throws IOException {
                    if (replyFailure != null) { throw replyFailure; }
                    response.write(value);
                }
                @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                    if (replyFailure != null) { throw replyFailure; }
                    response.write(bytes, offset, length);
                }
            };
        }

        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress("127.0.0.1", 50123); }
        @Override public InputStream getInputStream() { return request; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(final int timeout) { }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            closed = true;
            request.close();
            output.close();
        }
    }
}
