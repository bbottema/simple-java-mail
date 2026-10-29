package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(45)
class SocksSetupAdmissionTest {

    @Test
    void fullPendingAdmissionRefusesOnlyTheNewPeerAndIdleWorkersRetire() throws Exception {
        final Raw healthy = new Raw();
        final List<Raw> pending = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final Socks5Bridge bridge = (id, address, owner) -> {
            final int number = calls.incrementAndGet();
            final Raw raw = number == 1 ? healthy : new Raw();
            owner.own(raw);
            if (number > 1 && number <= 101) {
                pending.add(raw);
                awaitClose(raw);
                throw new SocketException("fixture pending remote closed");
            }
            return raw;
        };
        final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl(bridge, 0);
        final List<Socket> waiting = new ArrayList<>();
        server.start();
        final ThreadPoolExecutor setup = field(server, "threadPool", ThreadPoolExecutor.class);
        setup.setKeepAliveTime(50, MILLISECONDS);
        try (Socket established = beginConnect(server, true)) {
            assertThat(established.getInputStream().read()).isEqualTo(42);
            for (int index = 0; index < 100; index++) {
                waiting.add(beginConnect(server, false));
                final int expected = index + 1;
                await(() -> pending.size() == expected);
            }
            assertThat(setup.getActiveCount()).isEqualTo(100);
            assertThat(setup.getQueue()).isEmpty();

            try (Socket refused = new Socket("127.0.0.1", server.getLocalPort())) {
                refused.setSoTimeout(2000);
                refused.getOutputStream().write(new byte[] {5, 1, 0});
                refused.getOutputStream().flush();
                assertRefused(refused);
            }
            assertThat(server.isRunning()).isTrue();
            assertThat(healthy.closed.getCount()).isEqualTo(1);
            assertThat(pending).allSatisfy(raw -> assertThat(raw.closed.getCount()).isEqualTo(1));
            established.getOutputStream().write(71);
            established.getOutputStream().flush();
            healthy.output.awaitSize(1);
            assertThat(healthy.output.bytes()).containsExactly(71);

            for (Socket socket : waiting) { socket.close(); }
            await(() -> pending.stream().allMatch(raw -> raw.closed.getCount() == 0));
            await(() -> setup.getPoolSize() == 0);
            assertThat(healthy.isClosed()).as("established forwarding survives setup worker retirement").isFalse();
            try (Socket recovered = beginConnect(server, true)) {
                assertThat(recovered.getInputStream().read()).isEqualTo(42);
                assertThat(server.isRunning()).isTrue();
                assertThat(calls.get()).isEqualTo(102);
                server.stop();
                established.getOutputStream().write(72);
                established.getOutputStream().flush();
                healthy.output.awaitSize(2);
                assertThat(healthy.output.bytes()).containsExactly(71, 72);
            }
        } finally {
            for (Socket socket : waiting) { socket.close(); }
            server.stop();
            healthy.closeIfOpen();
            pending.forEach(Raw::closeIfOpen);
            setup.shutdownNow();
            assertThat(setup.awaitTermination(3, SECONDS)).isTrue();
        }
        assertThat(healthy.closes).hasValue(1);
        assertThat(pending).allSatisfy(raw -> assertThat(raw.closes).hasValue(1));
    }

    @Test
    void anUnexpectedListenerCrashClosesItsGenerationAndRestartRemainsIsolated() throws Exception {
        final Raw healthy = new Raw();
        final Raw pending = new Raw();
        final Raw restarted = new Raw();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch pendingOwned = new CountDownLatch(1);
        final AnonymousSocks5ServerImpl server = new AnonymousSocks5ServerImpl((id, address, owner) -> {
            final int number = calls.incrementAndGet();
            final Raw raw = number == 1 ? healthy : number == 2 ? pending : restarted;
            owner.own(raw);
            if (number == 2) {
                pendingOwned.countDown();
                awaitClose(raw);
                throw new SocketException("fixture setup retired by listener failure");
            }
            return raw;
        }, 0);
        final AtomicReference<Throwable> uncaught = new AtomicReference<>();
        final IllegalStateException original = new IllegalStateException("fixture setup thread creation failed");
        server.start();
        final ThreadPoolExecutor setup = field(server, "threadPool", ThreadPoolExecutor.class);
        field(server, "listenerThread", Thread.class).setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
        setup.setKeepAliveTime(50, MILLISECONDS);
        try (Socket established = beginConnect(server, true); Socket waiting = beginConnect(server, false)) {
            assertThat(established.getInputStream().read()).isEqualTo(42);
            assertThat(pendingOwned.await(3, SECONDS)).isTrue();
            await(() -> setup.getPoolSize() == 1 && setup.getActiveCount() == 1);
            setup.setThreadFactory(task -> { throw original; });
            try (Socket failed = new Socket("127.0.0.1", server.getLocalPort())) {
                failed.setSoTimeout(2000);
                assertRefused(failed);
            }
            await(() -> !server.isRunning());
            await(() -> uncaught.get() != null);
            assertThat(uncaught.get()).isSameAs(original);
            assertThat(healthy.closed.await(3, SECONDS)).isTrue();
            assertThat(pending.closed.await(3, SECONDS)).isTrue();
            assertThat(healthy.closes).hasValue(1);
            assertThat(pending.closes).hasValue(1);

            server.start();
            try (Socket newGeneration = beginConnect(server, true)) {
                assertThat(newGeneration.getInputStream().read()).isEqualTo(42);
                newGeneration.getOutputStream().write(73);
                newGeneration.getOutputStream().flush();
                restarted.output.awaitSize(1);
                assertThat(restarted.output.bytes()).containsExactly(73);
                assertThat(server.isRunning()).isTrue();
                assertThat(restarted.isClosed()).isFalse();
            }
        } finally {
            server.stop();
            healthy.closeIfOpen();
            pending.closeIfOpen();
            restarted.closeIfOpen();
            setup.shutdownNow();
            assertThat(setup.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    private static Socket beginConnect(final AnonymousSocks5ServerImpl server, final boolean success) throws Exception {
        final Socket client = new Socket("127.0.0.1", server.getLocalPort());
        try {
            client.setSoTimeout(3000);
            client.getOutputStream().write(new byte[] {5, 1, 0});
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(2)).containsExactly(5, 0);
            client.getOutputStream().write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, 0, 25});
            client.getOutputStream().flush();
            if (success) { assertThat(client.getInputStream().readNBytes(10)).containsExactly(5, 0, 0, 1, 127, 0, 0, 1, 0, 25); }
            return client;
        } catch (Throwable failure) {
            client.close();
            throw failure;
        }
    }

    private static void assertRefused(final Socket client) throws IOException {
        try {
            assertThat(client.getInputStream().read()).isEqualTo(-1);
        } catch (SocketTimeoutException timeout) {
            throw timeout;
        } catch (SocketException reset) {
            // A refused socket may reset if its unread handshake bytes have already arrived.
        }
    }

    private static void await(final BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitClose(final Raw raw) throws IOException {
        try {
            if (!raw.closed.await(30, SECONDS)) { throw new IOException("fixture pending setup watchdog expired"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("fixture pending setup interrupted", interrupted);
        }
    }

    private static <T> T field(final Object owner, final String name, final Class<T> type) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }

    private static final class Raw extends Socket {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();
        private final Sink output = new Sink();
        private final InputStream input = new InputStream() {
            private boolean banner;
            @Override public int read() throws IOException {
                if (!banner) { banner = true; return 42; }
                awaitClose(Raw.this);
                return -1;
            }
            @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                final int value = read();
                if (value < 0) { return -1; }
                bytes[offset] = (byte) value;
                return 1;
            }
        };
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed.getCount() == 0; }
        @Override public InetAddress getLocalAddress() {
            try { return InetAddress.getByName("127.0.0.1"); } catch (IOException impossible) { throw new AssertionError(impossible); }
        }
        @Override public int getLocalPort() { return 25; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(final int timeout) throws SocketException {
            if (isClosed()) { throw new SocketException("fixture raw socket closed"); }
            assertThat(timeout).isZero();
        }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
        private void closeIfOpen() { if (!isClosed()) { close(); } }
    }

    private static final class Sink extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public synchronized void write(final int value) { bytes.write(value); notifyAll(); }
        @Override public synchronized void write(final byte[] source, final int offset, final int length) {
            bytes.write(source, offset, length);
            notifyAll();
        }
        private synchronized byte[] bytes() { return bytes.toByteArray(); }
        private synchronized void awaitSize(final int expected) throws Exception {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (bytes.size() < expected && System.nanoTime() < deadline) { wait(10); }
            assertThat(bytes.size()).isGreaterThanOrEqualTo(expected);
        }
    }
}
