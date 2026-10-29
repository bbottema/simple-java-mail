package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Owns fake socket lifetimes explicitly so failures cannot strand network or forwarding workers. */
@Timeout(20)
class SocksTunnelTest {

    @Test
    void closesBothOwnedPeersOnlyOnceAndRetainsTheOperationalFailure() throws Exception {
        final IOException localCloseFailure = new IOException("local close failed");
        final IOException remoteCloseFailure = new IOException("remote close failed");
        final TestSocket local = new TestSocket(new QueueInput(), new RecordingOutput(), localCloseFailure);
        final TestSocket remote = new TestSocket(new QueueInput(), new RecordingOutput(), remoteCloseFailure);
        final AtomicInteger callbacks = new AtomicInteger();
        final SocksTunnel tunnel = new SocksTunnel(local, callbacks::incrementAndGet);
        tunnel.own(remote);
        final IOException original = new IOException("proxy negotiation failed");

        tunnel.close(original);
        tunnel.close(new IOException("later failure"));
        tunnel.close();

        assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
        assertThat(tunnel.failure()).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactlyInAnyOrder(localCloseFailure, remoteCloseFailure);
        assertThat(local.closeCalls).hasValue(1);
        assertThat(remote.closeCalls).hasValue(1);
        tunnel.awaitClientReader();
        assertThat(callbacks).hasValue(1);
    }

    @Test
    void aSocketPublishedAfterClosureIsImmediatelyClosedAndCannotReopenTheTunnel() throws Exception {
        final TestSocket local = socket();
        final TestSocket remote = socket();
        final AtomicInteger callbacks = new AtomicInteger();
        final SocksTunnel tunnel = new SocksTunnel(local, callbacks::incrementAndGet);
        tunnel.close();

        assertThatThrownBy(() -> tunnel.own(remote)).isInstanceOf(SocketException.class);

        assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
        assertThat(remote.closeCalls).hasValue(1);
        assertThat(local.closeCalls).hasValue(1);
        assertThat(callbacks).hasValue(1);
    }

    @Test
    void peerClosureDoesNotDiscardTheFirstLaterOperationalFailure() throws Exception {
        final SocksTunnel tunnel = new SocksTunnel(socket(), () -> { });
        tunnel.own(socket());
        tunnel.close();
        final IOException original = new IOException("remote setup reported its original failure");

        tunnel.close(original);

        assertThat(tunnel.failure()).isSameAs(original);
    }

    @Test
    void closeCompletionCannotOverwriteAnOperationalFailureReportedDuringCleanup() throws Exception {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch finishClose = new CountDownLatch(1);
        final IOException cleanupFailure = new IOException("remote close failed");
        final TestSocket remote = new TestSocket(new QueueInput(), new RecordingOutput()) {
            @Override public void close() throws IOException {
                closeEntered.countDown();
                try {
                    if (!finishClose.await(5, SECONDS)) { throw new IOException("fixture close watchdog expired"); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture close interrupted", interrupted);
                }
                super.close();
                throw cleanupFailure;
            }
        };
        final TestSocket local = socket();
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
        tunnel.own(remote);
        final IOException original = new IOException("original remote negotiation failure");
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final Future<?> closing = worker.submit((Runnable) tunnel::close);
            assertThat(closeEntered.await(3, SECONDS)).isTrue();

            tunnel.close(original);
            finishClose.countDown();
            closing.get(3, SECONDS);

            assertThat(tunnel.failure()).isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(cleanupFailure);
            assertThat(remote.closeCalls).hasValue(1);
            assertThat(local.closeCalls).hasValue(1);
        } finally {
            finishClose.countDown();
            tunnel.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    @Test
    void republishingTheSameHandleWhileItIsClosingDoesNotAttemptASecondClose() throws Exception {
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch finishClose = new CountDownLatch(1);
        final AtomicInteger closeAttempts = new AtomicInteger();
        final TestSocket remote = new TestSocket(new QueueInput(), new RecordingOutput()) {
            @Override public void close() throws IOException {
                if (closeAttempts.incrementAndGet() != 1) {
                    throw new IOException("duplicate remote close attempt");
                }
                closeEntered.countDown();
                try {
                    if (!finishClose.await(5, SECONDS)) { throw new IOException("fixture close watchdog expired"); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture close interrupted", interrupted);
                }
                super.close();
            }
        };
        final TestSocket local = socket();
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
        tunnel.own(remote);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final Future<?> closing = worker.submit((Runnable) tunnel::close);
            assertThat(closeEntered.await(3, SECONDS)).isTrue();

            assertThatThrownBy(() -> tunnel.own(remote)).isInstanceOf(SocketException.class);

            assertThat(closeAttempts).as("an already owned handle has one closer").hasValue(1);
            finishClose.countDown();
            closing.get(3, SECONDS);
            assertThat(closeAttempts).hasValue(1);
            assertThat(remote.closeCalls).hasValue(1);
            assertThat(local.closeCalls).hasValue(1);
        } finally {
            finishClose.countDown();
            tunnel.close();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    @Test
    void pendingClientEofClosesItsExactRemoteSocket() throws Exception {
        final TestSocket local = socket();
        final TestSocket remote = socket();
        final CountDownLatch closed = new CountDownLatch(1);
        final SocksTunnel tunnel = new SocksTunnel(local, closed::countDown);
        tunnel.own(remote);

        tunnel.startClientReader(new ByteArrayInputStream(new byte[0]));

        assertThat(closed.await(3, SECONDS)).as("pending peer EOF closes the tunnel").isTrue();
        assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
        assertThat(local.closeCalls).hasValue(1);
        assertThat(remote.closeCalls).hasValue(1);
        tunnel.awaitClientReader();
    }

    @Test
    void bufferedPendingBytesPrecedeLaterForwardedBytesExactlyOnce() throws Exception {
        final QueueInput client = new QueueInput();
        final RecordingOutput remoteOutput = new RecordingOutput();
        final TestSocket local = new TestSocket(client, new RecordingOutput());
        final TestSocket remote = new TestSocket(new QueueInput(), remoteOutput);
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
        try {
            tunnel.own(remote);
            tunnel.startClientReader(client);
            client.awaitReadCalls(1);
            client.offer("early-".getBytes(US_ASCII));
            client.awaitReadCalls(2);
            assertThat(remoteOutput.bytes()).isEmpty();

            tunnel.beginForwarding();
            client.offer("later".getBytes(US_ASCII));
            remoteOutput.awaitSize(11);

            assertThat(remoteOutput.bytes()).isEqualTo("early-later".getBytes(US_ASCII));
            assertThat(local.timeout).isZero();
            assertThat(remote.timeout).isZero();
            assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.FORWARDING);
        } finally {
            tunnel.close();
            client.awaitClosed();
            tunnel.awaitClientReader();
        }
    }

    @Test
    void excessPrematureBytesCloseThePendingTunnelInsteadOfGrowingWithoutABound() throws Exception {
        final QueueInput client = new QueueInput();
        final TestSocket local = new TestSocket(client, new RecordingOutput());
        final TestSocket remote = socket();
        final CountDownLatch closed = new CountDownLatch(1);
        final SocksTunnel tunnel = new SocksTunnel(local, closed::countDown);
        try {
            tunnel.own(remote);
            tunnel.startClientReader(client);
            client.offer(new byte[8193]);

            assertThat(closed.await(3, SECONDS)).as("premature input is bounded").isTrue();
            assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
            assertThat(tunnel.failure()).isInstanceOf(IOException.class);
            assertThat(remote.closeCalls).hasValue(1);
        } finally {
            tunnel.close();
            client.awaitClosed();
            tunnel.awaitClientReader();
        }
    }

    @Test
    void closureCanAbortABlockedPendingBufferFlushWithoutWaitingForTheWriterLock() throws Exception {
        final QueueInput client = new QueueInput();
        final BlockingOutput remoteOutput = new BlockingOutput();
        final TestSocket local = new TestSocket(client, new RecordingOutput());
        final TestSocket remote = new TestSocket(new QueueInput(), remoteOutput);
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            tunnel.own(remote);
            tunnel.startClientReader(client);
            client.awaitReadCalls(1);
            client.offer("early".getBytes(US_ASCII));
            client.awaitReadCalls(2);
            final Future<Throwable> forwarding = workers.submit(() -> {
                try {
                    tunnel.beginForwarding();
                    return null;
                } catch (IOException failure) {
                    return failure;
                }
            });
            assertThat(remoteOutput.writing.await(3, SECONDS)).as("flush reached the remote stream").isTrue();

            workers.submit((Runnable) tunnel::close).get(2, SECONDS);

            assertThat(forwarding.get(3, SECONDS)).isInstanceOf(IOException.class);
            assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
            assertThat(remote.closeCalls).hasValue(1);
            assertThat(local.closeCalls).hasValue(1);
        } finally {
            remoteOutput.release();
            tunnel.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, SECONDS)).isTrue();
            client.awaitClosed();
            tunnel.awaitClientReader();
        }
    }

    @Test
    void cancellingOnePendingOwnerDoesNotCloseAnotherForwardingTunnel() throws Exception {
        final QueueInput healthyClient = new QueueInput();
        final RecordingOutput healthyOutput = new RecordingOutput();
        final TestSocket healthyLocal = new TestSocket(healthyClient, new RecordingOutput());
        final TestSocket healthyRemote = new TestSocket(new QueueInput(), healthyOutput);
        final TestSocket pendingLocal = socket();
        final TestSocket pendingRemote = socket();
        final SocksTunnel healthy = new SocksTunnel(healthyLocal, () -> { });
        final SocksTunnel pending = new SocksTunnel(pendingLocal, () -> { });
        try {
            healthy.own(healthyRemote);
            healthy.startClientReader(healthyClient);
            healthy.beginForwarding();
            pending.own(pendingRemote);

            pending.close(new IOException("pending send cancelled"));
            healthyClient.offer("still healthy".getBytes(US_ASCII));
            healthyOutput.awaitSize(13);

            assertThat(healthyOutput.bytes()).isEqualTo("still healthy".getBytes(US_ASCII));
            assertThat(healthy.state()).isEqualTo(SocksTunnel.State.FORWARDING);
            assertThat(healthyLocal.closeCalls).hasValue(0);
            assertThat(healthyRemote.closeCalls).hasValue(0);
            assertThat(pendingLocal.closeCalls).hasValue(1);
            assertThat(pendingRemote.closeCalls).hasValue(1);
        } finally {
            healthy.close();
            pending.close();
            healthyClient.awaitClosed();
            healthy.awaitClientReader();
        }
    }

    @Test
    void listenerRetirementClosesPendingOwnersButPreservesEstablishedTunnels() throws Exception {
        final TestSocket pendingLocal = socket();
        final TestSocket pendingRemote = socket();
        final TestSocket establishedLocal = socket();
        final TestSocket establishedRemote = socket();
        final SocksTunnel pending = new SocksTunnel(pendingLocal, () -> { });
        final SocksTunnel established = new SocksTunnel(establishedLocal, () -> { });
        try {
            pending.own(pendingRemote);
            established.own(establishedRemote);
            established.beginForwarding();

            pending.closeIfPending();
            established.closeIfPending();

            assertThat(pending.state()).isEqualTo(SocksTunnel.State.CLOSED);
            assertThat(pendingRemote.closeCalls).hasValue(1);
            assertThat(established.state()).isEqualTo(SocksTunnel.State.FORWARDING);
            assertThat(establishedLocal.closeCalls).hasValue(0);
            assertThat(establishedRemote.closeCalls).hasValue(0);
        } finally {
            pending.close();
            established.close();
        }
    }

    @Test
    void pendingRetirementAndForwardingHandoffHaveOnlyOneWinningState() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 40; iteration++) {
                final TestSocket local = socket();
                final TestSocket remote = socket();
                final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
                tunnel.own(remote);
                final CountDownLatch start = new CountDownLatch(1);
                final Future<?> retire = workers.submit(() -> {
                    await(start);
                    tunnel.closeIfPending();
                });
                final Future<Throwable> forward = workers.submit(() -> {
                    await(start);
                    try {
                        tunnel.beginForwarding();
                        return null;
                    } catch (IOException closed) {
                        return closed;
                    }
                });
                try {
                    start.countDown();
                    retire.get(3, SECONDS);
                    final Throwable handoffFailure = forward.get(3, SECONDS);

                    assertThat(tunnel.state()).isIn(SocksTunnel.State.FORWARDING, SocksTunnel.State.CLOSED);
                    if (tunnel.state() == SocksTunnel.State.FORWARDING) {
                        assertThat(handoffFailure).isNull();
                        assertThat(local.closeCalls).hasValue(0);
                        assertThat(remote.closeCalls).hasValue(0);
                    } else {
                        assertThat(handoffFailure).isInstanceOf(SocketException.class);
                        assertThat(local.closeCalls).hasValue(1);
                        assertThat(remote.closeCalls).hasValue(1);
                    }
                } finally {
                    tunnel.close();
                }
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    private static TestSocket socket() {
        return new TestSocket(new QueueInput(), new RecordingOutput());
    }

    private static void await(final CountDownLatch signal) {
        try {
            assertThat(signal.await(3, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static class TestSocket extends Socket {
        private final InputStream input;
        private final OutputStream output;
        private final IOException closeFailure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile boolean closed;
        private int timeout = 10000;

        private TestSocket(final InputStream input, final OutputStream output) {
            this(input, output, null);
        }

        private TestSocket(final InputStream input, final OutputStream output, final IOException closeFailure) {
            this.input = input;
            this.output = output;
            this.closeFailure = closeFailure;
        }

        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50025); }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return 50026; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(final int timeout) throws SocketException {
            if (closed) { throw new SocketException("socket closed"); }
            this.timeout = timeout;
        }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() throws IOException {
            closeCalls.incrementAndGet();
            closed = true;
            try {
                input.close();
            } finally {
                output.close();
            }
            if (closeFailure != null) { throw closeFailure; }
        }
    }

    private static final class QueueInput extends InputStream {
        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private int offset;
        private int readCalls;
        private boolean closed;

        synchronized void offer(final byte[] bytes) {
            chunks.add(bytes.clone());
            notifyAll();
        }

        synchronized void awaitReadCalls(final int expected) throws Exception {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (readCalls < expected) {
                final long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("client reader reached the next read").isPositive();
                wait(Math.max(1, remaining / 1_000_000));
            }
        }

        synchronized void awaitClosed() throws Exception {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (!closed) {
                final long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("fixture input closed").isPositive();
                wait(Math.max(1, remaining / 1_000_000));
            }
        }

        @Override public int read() throws IOException {
            final byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
        }

        @Override public synchronized int read(final byte[] bytes, final int start, final int length) throws IOException {
            readCalls++;
            notifyAll();
            while (chunks.isEmpty() && !closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture reader interrupted", interrupted);
                }
            }
            if (closed) { return -1; }
            final byte[] next = chunks.peek();
            final int count = Math.min(length, next.length - offset);
            System.arraycopy(next, offset, bytes, start, count);
            offset += count;
            if (offset == next.length) {
                chunks.remove();
                offset = 0;
            }
            return count;
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static class RecordingOutput extends OutputStream {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private boolean closed;

        @Override public synchronized void write(final int value) throws IOException {
            if (closed) { throw new SocketException("fixture output closed"); }
            content.write(value);
            notifyAll();
        }

        @Override public synchronized void write(final byte[] bytes, final int start, final int length) throws IOException {
            if (closed) { throw new SocketException("fixture output closed"); }
            content.write(bytes, start, length);
            notifyAll();
        }

        synchronized byte[] bytes() {
            return content.toByteArray();
        }

        synchronized void awaitSize(final int size) throws Exception {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (content.size() < size) {
                final long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("forwarded bytes arrived").isPositive();
                wait(Math.max(1, remaining / 1_000_000));
            }
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class BlockingOutput extends RecordingOutput {
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override public void write(final byte[] bytes, final int start, final int length) throws IOException {
            writing.countDown();
            try {
                if (!released.await(5, SECONDS)) { throw new IOException("fixture write watchdog expired"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture writer interrupted", interrupted);
            }
            throw new SocketException("fixture output closed during write");
        }

        void release() {
            released.countDown();
        }

        @Override public void close() {
            release();
            super.close();
        }
    }
}
