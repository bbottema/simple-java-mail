package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.authenticatedsockssupport.AuthenticatedSocksHelper;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.Permission;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SocksSocketProviderTest {
    private static final byte[] SUCCESS = {5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 25, '2', '2', '0'};

    @Test
    void rejectsATlsSocketBeforeAnyPlaintextNegotiationOrConnection() throws Exception {
        final AuthenticatedSocksSocketProvider provider = provider(new MutableConfig("127.0.0.1", 1080));
        try (Socket tls = SSLSocketFactory.getDefault().createSocket()) {
            assertThrows(SocketException.class, () -> provider.wrap(tls));
            assertFalse(tls.isConnected());
            assertFalse(tls.isClosed());
        }
    }

    @Test
    void capturesFactsOnceAndWrapsFreshUnconnectedPhysicalSocketsWithoutResolvingOrOpeningThem() throws Exception {
        final MutableConfig config = new MutableConfig("127.0.0.1", 1080);
        final AuthenticatedSocksSocketProvider provider = new AuthenticatedSocksHelper().createSocketProvider(config);
        config.host = "changed.invalid";
        config.port = 1081;
        config.username = "changed";
        config.password = "changed";
        final ScriptedSocket firstRaw = new ScriptedSocket(SUCCESS);
        final ScriptedSocket secondRaw = new ScriptedSocket(SUCCESS);
        try (Socket first = provider.wrap(firstRaw); Socket second = provider.wrap(secondRaw)) {
            assertNotSame(first, second);
            assertEquals(0, firstRaw.connects);
            assertEquals(0, firstRaw.streamAcquisitions);
            assertEquals(0, secondRaw.streamAcquisitions);
            assertFalse(first.isConnected());
            assertNull(first.getInetAddress());
            assertNull(first.getRemoteSocketAddress());
            assertEquals("SocksSocket[unconnected]", first.toString());
            first.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 2000);
            assertEquals("127.0.0.1", firstRaw.endpoint.getHostString());
            assertEquals(1080, firstRaw.endpoint.getPort());
            assertArrayEquals(authentication("proxy-user", "proxy-secret"),
                    Arrays.copyOfRange(firstRaw.sent.toByteArray(), 4, 4 + authentication("proxy-user", "proxy-secret").length));
            assertFalse(second.isConnected());
            assertEquals(0, secondRaw.connects);
            assertEquals(4, config.factReads);
        }
        assertEquals(1, firstRaw.closes);
        assertEquals(1, secondRaw.closes);
    }

    @Test
    @SuppressWarnings("removal")
    void capabilityCreationWrappingAndUnconnectedMetadataNeverAskTheResolver() throws Exception {
        final SecurityManager previous = System.getSecurityManager();
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(final Permission permission) { }
                @Override public void checkConnect(final String host, final int port) {
                    throw new AssertionError("Passive inspection attempted resolution or connection: " + host + ":" + port);
                }
            });
            final AuthenticatedSocksSocketProvider provider = provider(new MutableConfig("unresolvable.proxy.invalid", 1080));
            final ScriptedSocket physical = new ScriptedSocket(SUCCESS);
            try (Socket socket = provider.wrap(physical)) {
                assertNull(socket.getInetAddress());
                assertNull(socket.getRemoteSocketAddress());
                assertEquals(0, socket.getPort());
                assertEquals("SocksSocket[unconnected]", socket.toString());
                assertEquals(0, physical.connects);
                assertEquals(0, physical.streamAcquisitions);
            }
        } finally {
            System.setSecurityManager(previous);
        }
    }

    @ParameterizedTest
    @MethodSource("destinations")
    void realAuthenticatedExchangePreservesDestinationKindAndDoesNotConsumeTheFollowingSmtpBytes(final InetSocketAddress target)
            throws Exception {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Future<Void> exchange = worker.submit(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(4000);
                    final DataInputStream input = new DataInputStream(peer.getInputStream());
                    final OutputStream output = peer.getOutputStream();
                    assertArrayEquals(new byte[] {5, 2, 0, 2}, input.readNBytes(4));
                    output.write(new byte[] {5, 2});
                    output.flush();
                    assertArrayEquals(authentication("proxy-user", "proxy-secret"),
                            input.readNBytes(authentication("proxy-user", "proxy-secret").length));
                    output.write(new byte[] {1, 0});
                    output.flush();
                    assertArrayEquals(request(target), input.readNBytes(request(target).length));
                    output.write(Arrays.copyOfRange(SUCCESS, 4, SUCCESS.length));
                    output.flush();
                    assertEquals(42, input.read());
                }
                return null;
            });
            final CountingSocket physical = new CountingSocket();
            final Socket socket = provider(listener).wrap(physical);
            try {
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                final int localPort = socket.getLocalPort();
                socket.setSoTimeout(1200);
                socket.connect(target, 3000);
                assertTrue(socket.isConnected());
                assertTrue(socket.isBound());
                assertSame(target, socket.getRemoteSocketAddress());
                assertEquals(target.getAddress(), socket.getInetAddress());
                assertEquals(target.getPort(), socket.getPort());
                assertEquals(localPort, physical.getLocalPort());
                assertEquals(physical.getLocalSocketAddress(), socket.getLocalSocketAddress());
                assertEquals(1200, socket.getSoTimeout());
                assertArrayEquals(new byte[] {'2', '2', '0'}, socket.getInputStream().readNBytes(3));
                socket.getOutputStream().write(42);
                socket.getOutputStream().flush();
                exchange.get(5, TimeUnit.SECONDS);
            } finally {
                socket.close();
                socket.close();
            }
            assertEquals(1, physical.closes);
            assertTrue(socket.isConnected());
            assertTrue(socket.isBound());
            assertTrue(socket.isClosed());
            assertSame(target, socket.getRemoteSocketAddress());
            assertEquals(target.getAddress(), socket.getInetAddress());
            assertThrows(SocketException.class, socket::getInputStream);
            assertThrows(SocketException.class, socket::getSoTimeout);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void usesThePhysicalSocketForOrdinaryOptionsBindingAndShutdown() throws Exception {
        final ScriptedSocket physical = new ScriptedSocket(SUCCESS);
        try (Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical)) {
            socket.setSoTimeout(1300);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReuseAddress(true);
            socket.setSoLinger(true, 4);
            socket.setOOBInline(true);
            socket.setTrafficClass(16);
            socket.setReceiveBufferSize(8192);
            socket.setSendBufferSize(16384);
            assertEquals(physical.getTcpNoDelay(), socket.getTcpNoDelay());
            assertEquals(physical.getKeepAlive(), socket.getKeepAlive());
            assertEquals(physical.getReuseAddress(), socket.getReuseAddress());
            assertEquals(physical.getSoLinger(), socket.getSoLinger());
            assertEquals(physical.getOOBInline(), socket.getOOBInline());
            assertEquals(physical.getTrafficClass(), socket.getTrafficClass());
            assertEquals(physical.getReceiveBufferSize(), socket.getReceiveBufferSize());
            assertEquals(physical.getSendBufferSize(), socket.getSendBufferSize());
            socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 2000);
            assertEquals(1300, physical.getSoTimeout());
            socket.shutdownInput();
            socket.shutdownOutput();
            assertTrue(socket.isInputShutdown());
            assertTrue(socket.isOutputShutdown());
            assertTrue(physical.inputShutdown);
            assertTrue(physical.outputShutdown);
        }
    }

    @Test
    @SuppressWarnings("removal")
    void checksTheOriginalDestinationPermissionBeforeTouchingTheProxySocket() throws Exception {
        final SecurityManager previous = System.getSecurityManager();
        final SecurityException denied = new SecurityException("logical destination denied");
        final ScriptedSocket physical = new ScriptedSocket(SUCCESS);
        try (Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical)) {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(final Permission permission) { }
                @Override public void checkConnect(final String host, final int port) {
                    if ("smtp.denied.invalid".equals(host) && port == 587) { throw denied; }
                }
            });
            assertSame(denied, assertThrows(SecurityException.class,
                    () -> socket.connect(InetSocketAddress.createUnresolved("smtp.denied.invalid", 587), 1000)));
            assertFalse(socket.isConnected());
            assertFalse(socket.isClosed());
            assertEquals(0, physical.connects);
            assertEquals(0, physical.streamAcquisitions);
        } finally {
            System.setSecurityManager(previous);
        }
    }

    @Test
    void closesOnceAndPreservesTheSetupFailureAndSuppressedCleanupFailure() throws Exception {
        final ScriptedSocket physical = new ScriptedSocket(new byte[] {5});
        final IOException cleanup = new IOException("physical disposal failed");
        physical.closeFailure = cleanup;
        final Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical);
        final IOException failure = assertThrows(IOException.class,
                () -> socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 1000));
        assertEquals("End of stream", failure.getMessage());
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        assertEquals(1, physical.closes);
        assertTrue(socket.isClosed());
        assertFalse(socket.isConnected());
        socket.close();
        assertEquals(1, physical.closes);
    }

    @ParameterizedTest
    @MethodSource("rejectedReplies")
    void failedNegotiationDisposesThePhysicalSocketWithoutPublishingAConnectedDestination(final byte[] reply) throws Exception {
        final ScriptedSocket physical = new ScriptedSocket(reply);
        final Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical);
        final Throwable failure = assertThrows(Exception.class,
                () -> socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 1000));
        assertFalse(failure.getMessage().contains("proxy-secret"));
        assertTrue(socket.isClosed());
        assertFalse(socket.isConnected());
        assertNull(socket.getInetAddress());
        assertNull(socket.getRemoteSocketAddress());
        socket.close();
        assertEquals(1, physical.closes);
    }

    @Test
    void tcpSetupAndSocksRepliesConsumeTheSameRemainingBudget() throws Exception {
        final ScriptedSocket physical = new ScriptedSocket(SUCCESS) {
            @Override public void connect(final SocketAddress address, final int timeout) {
                super.connect(address, timeout);
                try { Thread.sleep(160); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }
        };
        try (Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical)) {
            socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 1000);
            assertTrue(physical.connectTimeout > 0 && physical.connectTimeout <= 1000);
            assertFalse(physical.setupReadTimeouts.isEmpty());
            for (final int readTimeout : physical.setupReadTimeouts) {
                assertTrue(readTimeout > 0 && readTimeout <= 900, "TCP time must reduce every SOCKS read timeout: " + readTimeout);
            }
            assertEquals(0, physical.getSoTimeout());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeReachesTheOwnedPhysicalSocketWhileASetupReadOrWriteIsBlocked(final boolean blockedWrite) throws Exception {
        final BlockingSocket physical = new BlockingSocket(blockedWrite);
        final Socket socket = provider(new MutableConfig("127.0.0.1", 1080)).wrap(physical);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final Future<Throwable> setup = worker.submit(() -> {
                try {
                    socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 0);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(physical.blocked.await(3, TimeUnit.SECONDS));
            socket.close();
            assertSame(physical.closedFailure, setup.get(3, TimeUnit.SECONDS));
            socket.close();
            assertEquals(1, physical.closes);
            assertTrue(socket.isClosed());
            assertFalse(socket.isConnected());
        } finally {
            socket.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aFiniteSetupBudgetIsNotRenewedForEachFragmentOrAuthenticationPhase() throws Exception {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Future<Void> exchange = worker.submit(() -> {
                try (Socket peer = listener.accept()) {
                    final DataInputStream input = new DataInputStream(peer.getInputStream());
                    assertEquals(4, input.readNBytes(4).length);
                    for (final byte value : new byte[] {5, 2}) {
                        Thread.sleep(90);
                        peer.getOutputStream().write(value);
                        peer.getOutputStream().flush();
                    }
                    input.readNBytes(authentication("proxy-user", "proxy-secret").length);
                    Thread.sleep(90);
                    peer.getOutputStream().write(1);
                    peer.getOutputStream().flush();
                    Thread.sleep(90);
                    peer.getOutputStream().write(0);
                    peer.getOutputStream().flush();
                } catch (SocketException expectedAfterClientTimeout) {
                    // The client has already disposed the one physical socket when its cumulative budget expires.
                }
                return null;
            });
            final CountingSocket physical = new CountingSocket();
            try (Socket socket = provider(listener).wrap(physical)) {
                final long started = System.nanoTime();
                assertThrows(SocketTimeoutException.class,
                        () -> socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 300));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
                assertTrue(socket.isClosed());
                assertEquals(1, physical.closes);
            }
            exchange.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unlimitedSetupTemporarilyOverridesTheOrdinaryReadTimeoutAndRestoresItBeforeReturning() throws Exception {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Future<Void> exchange = worker.submit(() -> {
                try (Socket peer = listener.accept()) {
                    final DataInputStream input = new DataInputStream(peer.getInputStream());
                    input.readNBytes(4);
                    Thread.sleep(100);
                    peer.getOutputStream().write(new byte[] {5, 2});
                    peer.getOutputStream().flush();
                    input.readNBytes(authentication("proxy-user", "proxy-secret").length);
                    Thread.sleep(100);
                    peer.getOutputStream().write(new byte[] {1, 0});
                    peer.getOutputStream().flush();
                    input.readNBytes(request(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587)).length);
                    Thread.sleep(100);
                    peer.getOutputStream().write(Arrays.copyOfRange(SUCCESS, 4, 14));
                    peer.getOutputStream().flush();
                    assertEquals(-1, input.read());
                }
                return null;
            });
            try (Socket socket = provider(listener).wrap(new Socket())) {
                socket.setSoTimeout(25);
                socket.connect(InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587), 0);
                assertTrue(socket.isConnected());
                assertEquals(25, socket.getSoTimeout());
            }
            exchange.get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Stream<InetSocketAddress> destinations() throws Exception {
        final byte[] mapped = new byte[16];
        mapped[10] = (byte) 255;
        mapped[11] = (byte) 255;
        mapped[12] = 127;
        mapped[15] = 1;
        return Stream.of(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 25),
                new InetSocketAddress(Inet6Address.getByAddress(null, new byte[16], -1), 465),
                new InetSocketAddress(Inet6Address.getByAddress(null, mapped, -1), 587),
                InetSocketAddress.createUnresolved("smtp.remote-only.invalid", 587),
                InetSocketAddress.createUnresolved("m\u00e4il.remote-only.invalid", 25));
    }

    private static Stream<byte[]> rejectedReplies() {
        return Stream.of(new byte[] {5, (byte) 255}, new byte[] {6, 2},
                new byte[] {5, 2, 1, 1}, new byte[] {5, 2, 2, 0}, new byte[] {5, 0, 5, 0, 0, 1});
    }

    private static byte[] authentication(final String username, final String password) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(1);
        bytes.write(username.getBytes(UTF_8).length);
        bytes.write(username.getBytes(UTF_8));
        bytes.write(password.getBytes(UTF_8).length);
        bytes.write(password.getBytes(UTF_8));
        return bytes.toByteArray();
    }

    private static byte[] request(final InetSocketAddress target) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {5, 1, 0});
        final byte[] address = target.isUnresolved() ? target.getHostString().getBytes(UTF_8) : target.getAddress().getAddress();
        bytes.write(target.isUnresolved() ? 3 : address.length == 4 ? 1 : 4);
        if (target.isUnresolved()) { bytes.write(address.length); }
        bytes.write(address);
        bytes.write(target.getPort() >>> 8);
        bytes.write(target.getPort());
        return bytes.toByteArray();
    }

    private static AuthenticatedSocksSocketProvider provider(final ServerSocket listener) {
        return provider(new MutableConfig(listener.getInetAddress().getHostAddress(), listener.getLocalPort()));
    }

    private static AuthenticatedSocksSocketProvider provider(final ProxyConfig config) {
        return new AuthenticatedSocksHelper().createSocketProvider(config);
    }

    private static final class MutableConfig implements ProxyConfig {
        private String host;
        private int port;
        private String username = "proxy-user";
        private String password = "proxy-secret";
        private int factReads;
        private MutableConfig(final String host, final int port) { this.host = host; this.port = port; }
        @Override public boolean requiresProxy() { throw new AssertionError("Factory must capture the effective facts only"); }
        @Override public boolean requiresAuthentication() { throw new AssertionError("Factory must capture the effective facts only"); }
        public Integer getProxyBridgePort() { throw new AssertionError("Direct sockets have no bridge port"); }
        @Override public String getRemoteProxyHost() { factReads++; return host; }
        @Override public Integer getRemoteProxyPort() { factReads++; return port; }
        @Override public String getUsername() { factReads++; return username; }
        @Override public String getPassword() { factReads++; return password; }
    }

    private static class CountingSocket extends Socket {
        int closes;
        @Override public void close() throws IOException { closes++; super.close(); }
    }

    private static class ScriptedSocket extends Socket {
        private final ByteArrayInputStream replies;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        private InetSocketAddress endpoint;
        private int connects;
        private int connectTimeout;
        private final java.util.List<Integer> setupReadTimeouts = new java.util.ArrayList<>();
        private int streamAcquisitions;
        int closes;
        private boolean closed;
        private boolean connected;
        private boolean inputShutdown;
        private boolean outputShutdown;
        private IOException closeFailure;
        private ScriptedSocket(final byte[] replies) { this.replies = new ByteArrayInputStream(replies); }
        @Override public void connect(final SocketAddress target, final int timeout) {
            connects++;
            connectTimeout = timeout;
            endpoint = (InetSocketAddress) target;
            connected = true;
        }
        @Override public void setSoTimeout(final int timeout) throws SocketException {
            if (connected && timeout > 0) { setupReadTimeouts.add(timeout); }
            super.setSoTimeout(timeout);
        }
        @Override public InputStream getInputStream() { streamAcquisitions++; return replies; }
        @Override public OutputStream getOutputStream() { streamAcquisitions++; return sent; }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isClosed() { return closed; }
        @Override public void shutdownInput() { inputShutdown = true; }
        @Override public void shutdownOutput() { outputShutdown = true; }
        @Override public boolean isInputShutdown() { return inputShutdown; }
        @Override public boolean isOutputShutdown() { return outputShutdown; }
        @Override public void close() throws IOException {
            closes++;
            super.close();
            closed = true;
            if (closeFailure != null) { throw closeFailure; }
        }
    }

    private static final class BlockingSocket extends ScriptedSocket {
        private final boolean blockWrite;
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final SocketException closedFailure = new SocketException("owned physical socket closed");
        private BlockingSocket(final boolean blockWrite) { super(new byte[0]); this.blockWrite = blockWrite; }
        private void awaitClose() throws IOException {
            blocked.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Fixture was not closed"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            throw closedFailure;
        }
        @Override public InputStream getInputStream() {
            return new InputStream() { @Override public int read() throws IOException { awaitClose(); return -1; } };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() { @Override public void write(final int value) throws IOException { if (blockWrite) { awaitClose(); } } };
        }
        @Override public void close() throws IOException { try { super.close(); } finally { release.countDown(); } }
    }
}
