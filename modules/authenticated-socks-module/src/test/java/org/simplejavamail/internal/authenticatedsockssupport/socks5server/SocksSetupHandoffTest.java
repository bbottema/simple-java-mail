package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class SocksSetupHandoffTest {
    private static final byte[] HANDSHAKE = {5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, 0, 25};

    @Test
    void aSetupWorkerReturnsWhileItsEstablishedTunnelStillForwards() throws Exception {
        final Input localInput = new Input(HANDSHAKE);
        final Output localOutput = new Output();
        final Peer local = new Peer(localInput, localOutput);
        final Input remoteInput = new Input(new byte[] {42});
        final Peer remote = new Peer(remoteInput, new Output());
        final AtomicReference<Thread> forwarding = new AtomicReference<>();
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { }, capture(forwarding));
        final ExecutorService setup = Executors.newSingleThreadExecutor();
        try {
            final Future<?> handler = setup.submit(new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
                owner.own(remote);
                return remote;
            }, tunnel));

            localOutput.awaitSize(13);
            handler.get(3, SECONDS);
            assertThat(setup.submit(() -> "another setup").get(3, SECONDS)).isEqualTo("another setup");
            assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.FORWARDING);
            assertThat(local.closed).isFalse();
            assertThat(remote.closed).isFalse();
            assertThat(localOutput.bytes()).containsExactly(5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 25, 42);
            remoteInput.offer(new byte[] {43});
            localOutput.awaitSize(14);
            assertThat(localOutput.bytes()[13]).isEqualTo((byte) 43);
            assertThat(localInput.readers).hasValue(1);
        } finally {
            tunnel.close();
            tunnel.awaitClientReader();
            tunnel.awaitReservedForwarding();
            setup.shutdownNow();
            assertThat(setup.awaitTermination(3, SECONDS)).isTrue();
        }
        assertThat(local.closes).hasValue(1);
        assertThat(remote.closes).hasValue(1);
        assertThat(forwarding.get().isAlive()).isFalse();
    }

    @Test
    void aStartedForwardingThreadCannotReadBeforeTheCompleteLocalSuccessFrame() throws Exception {
        final AtomicReference<Thread> forwarding = new AtomicReference<>();
        final CountDownLatch writingSuccess = new CountDownLatch(1);
        final CountDownLatch releaseSuccess = new CountDownLatch(1);
        final AtomicInteger replies = new AtomicInteger();
        final Output localOutput = new Output() {
            @Override public void write(final byte[] bytes, final int offset, final int count) throws IOException {
                if (replies.incrementAndGet() == 2) {
                    assertThat(forwarding.get()).isNotNull();
                    assertThat(forwarding.get().isAlive()).as("usable forwarding thread reserved before success").isTrue();
                    writingSuccess.countDown();
                    await(releaseSuccess);
                }
                super.write(bytes, offset, count);
            }
        };
        final Peer local = new Peer(new Input(HANDSHAKE), localOutput);
        final Input remoteInput = new Input(new byte[] {42});
        final Peer remote = new Peer(remoteInput, new Output());
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { }, capture(forwarding));
        final ExecutorService setup = Executors.newSingleThreadExecutor();
        try {
            final Future<?> handler = setup.submit(new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
                owner.own(remote);
                return remote;
            }, tunnel));
            assertThat(writingSuccess.await(3, SECONDS)).isTrue();
            assertThat(remoteInput.readers).hasValue(0);
            assertThat(localOutput.bytes()).containsExactly(5, 0);

            releaseSuccess.countDown();
            handler.get(3, SECONDS);
            localOutput.awaitSize(13);
            assertThat(localOutput.bytes()).containsExactly(5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 25, 42);
        } finally {
            releaseSuccess.countDown();
            tunnel.close();
            tunnel.awaitClientReader();
            tunnel.awaitReservedForwarding();
            setup.shutdownNow();
            assertThat(setup.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    @Test
    void failedThreadStartClosesOnlyTheNewOwnerWithoutPublishingSuccess() throws Exception {
        final Error original = new OutOfMemoryError("fixture forwarding start failure");
        final Peer local = new Peer(new Input(HANDSHAKE), new Output());
        final Peer remote = new Peer(new Input(new byte[0]), new Output());
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { }, task -> new Thread(task) {
            @Override public synchronized void start() { throw original; }
        });

        assertThatThrownBy(() -> new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
            owner.own(remote);
            return remote;
        }, tunnel).run()).isSameAs(original);

        assertThat(local.output.bytes()).containsExactly(5, 0);
        assertThat(tunnel.failure()).isSameAs(original);
        assertThat(local.closes).hasValue(1);
        assertThat(remote.closes).hasValue(1);
    }

    @Test
    void clientClosureAtHandoffLeavesNoReservedThreadOrRemoteOwner() throws Exception {
        final AtomicReference<SocksTunnel> owner = new AtomicReference<>();
        final AtomicReference<Thread> forwarding = new AtomicReference<>();
        final AtomicInteger replies = new AtomicInteger();
        final Output localOutput = new Output() {
            @Override public void write(final byte[] bytes, final int offset, final int count) throws IOException {
                super.write(bytes, offset, count);
                if (replies.incrementAndGet() == 2) { owner.get().close(); }
            }
        };
        final Peer local = new Peer(new Input(HANDSHAKE), localOutput);
        final Input remoteInput = new Input(new byte[] {42});
        final Peer remote = new Peer(remoteInput, new Output());
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { }, capture(forwarding));
        owner.set(tunnel);

        new Socks5Handler(new SocksSession(local), (id, address, rawOwner) -> {
            rawOwner.own(remote);
            return remote;
        }, tunnel).run();

        assertThat(tunnel.state()).isEqualTo(SocksTunnel.State.CLOSED);
        assertThat(tunnel.forwardingTransferred()).isFalse();
        assertThat(local.closes).hasValue(1);
        assertThat(remote.closes).hasValue(1);
        assertThat(remoteInput.readers).hasValue(0);
        assertThat(forwarding.get().isAlive()).isFalse();
    }

    @Test
    void earlyByteFlushBelongsToForwardingRatherThanTheSetupWorker() throws Exception {
        final byte[] request = new byte[HANDSHAKE.length + 2];
        System.arraycopy(HANDSHAKE, 0, request, 0, HANDSHAKE.length);
        request[HANDSHAKE.length] = 1;
        request[HANDSHAKE.length + 1] = 2;
        final Input localInput = new Input(request);
        final Peer local = new Peer(localInput, new Output());
        final CountDownLatch flushingEarly = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final Output remoteOutput = new Output() {
            @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                if (writes.incrementAndGet() == 1) {
                    flushingEarly.countDown();
                    await(releaseWrite);
                }
                super.write(bytes, offset, length);
            }
            @Override public void close() { releaseWrite.countDown(); }
        };
        final Peer remote = new Peer(new Input(new byte[] {42}), remoteOutput);
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });
        final ExecutorService setup = Executors.newSingleThreadExecutor();
        try {
            final Future<?> handler = setup.submit(new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
                owner.own(remote);
                localInput.awaitBulkReads(2);
                return remote;
            }, tunnel));
            assertThat(flushingEarly.await(3, SECONDS)).isTrue();
            handler.get(3, SECONDS);
            assertThat(setup.submit(() -> "released setup capacity").get(3, SECONDS)).isEqualTo("released setup capacity");
            assertThat(local.output.bytes()).containsExactly(5, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 25);

            localInput.offer(new byte[] {3, 4});
            releaseWrite.countDown();
            remoteOutput.awaitSize(4);
            assertThat(remoteOutput.bytes()).containsExactly(1, 2, 3, 4);
            local.output.awaitSize(13);
            assertThat(localInput.readers).hasValue(1);
        } finally {
            tunnel.close();
            tunnel.awaitClientReader();
            tunnel.awaitReservedForwarding();
            setup.shutdownNow();
            assertThat(setup.awaitTermination(3, SECONDS)).isTrue();
        }
    }

    @Test
    void aFatalForwardingErrorRemainsUncaughtAfterItsOwnerAndCleanupFailuresAreRetired() throws Exception {
        final AssertionError fatal = new AssertionError("fixture fatal reverse forwarding");
        final IOException cleanup = new IOException("fixture remote close failure");
        final AtomicReference<Throwable> uncaught = new AtomicReference<>();
        final CountDownLatch reported = new CountDownLatch(1);
        final Peer local = new Peer(new Input(HANDSHAKE), new Output());
        final AtomicInteger remoteCloses = new AtomicInteger();
        final Socket remote = new Socket() {
            private boolean closed;
            @Override public InetAddress getLocalAddress() { return local.getLocalAddress(); }
            @Override public int getLocalPort() { return 25; }
            @Override public boolean isClosed() { return closed; }
            @Override public void setSoTimeout(final int timeout) { assertThat(timeout).isZero(); }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() { throw fatal; }
                };
            }
            @Override public void close() throws IOException {
                remoteCloses.incrementAndGet();
                closed = true;
                throw cleanup;
            }
        };
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { }, task -> {
            final Thread worker = new Thread(task, "fixture fatal forwarding");
            worker.setUncaughtExceptionHandler((thread, failure) -> {
                uncaught.set(failure);
                reported.countDown();
            });
            return worker;
        });
        try {
            new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
                owner.own(remote);
                return remote;
            }, tunnel).run();

            assertThat(reported.await(3, SECONDS)).as("fatal Error reaches the forwarding thread's uncaught handler").isTrue();
            assertThat(uncaught.get()).isSameAs(fatal);
            assertThat(tunnel.failure()).isSameAs(fatal);
            assertThat(fatal.getSuppressed()).containsExactly(cleanup);
            assertThat(remoteCloses).hasValue(1);
            assertThat(local.closes).hasValue(1);
        } finally {
            tunnel.close();
            tunnel.awaitClientReader();
            tunnel.awaitReservedForwarding();
        }
    }

    private static ThreadFactory capture(final AtomicReference<Thread> reference) {
        return task -> {
            final Thread thread = new Thread(task, "fixture reserved forwarding");
            reference.set(thread);
            return thread;
        };
    }

    private static void await(final CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, SECONDS)) { throw new IOException("fixture handoff watchdog expired"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("fixture handoff interrupted", interrupted);
        }
    }

    private static final class Peer extends Socket {
        private final Input input;
        private final Output output;
        private final AtomicInteger closes = new AtomicInteger();
        private volatile boolean closed;
        private int timeout;
        private Peer(final Input input, final Output output) { this.input = input; this.output = output; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InetAddress getLocalAddress() {
            try { return InetAddress.getByName("127.0.0.1"); } catch (IOException impossible) { throw new AssertionError(impossible); }
        }
        @Override public int getLocalPort() { return 25; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(getLocalAddress(), 50000); }
        @Override public void setSoTimeout(final int timeout) throws SocketException {
            if (closed) { throw new SocketException("fixture peer closed"); }
            this.timeout = timeout;
        }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() {
            closes.incrementAndGet();
            closed = true;
            input.close();
            output.close();
        }
    }

    private static final class Input extends InputStream {
        private final Queue<Byte> bytes = new ArrayDeque<>();
        private final AtomicInteger readers = new AtomicInteger();
        private int bulkReads;
        private boolean closed;
        private Thread reader;
        private Input(final byte[] initial) { offer(initial); }
        private synchronized void offer(final byte[] input) {
            for (byte value : input) { bytes.add(value); }
            notifyAll();
        }
        @Override public synchronized int read() throws IOException {
            while (bytes.isEmpty() && !closed) {
                try { wait(); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture peer read interrupted", interrupted);
                }
            }
            return closed ? -1 : bytes.remove() & 0xff;
        }
        @Override public synchronized int read(final byte[] target, final int offset, final int length) throws IOException {
            if (reader != Thread.currentThread()) { readers.incrementAndGet(); reader = Thread.currentThread(); }
            bulkReads++;
            notifyAll();
            final int first = read();
            if (first < 0) { return -1; }
            target[offset] = (byte) first;
            int count = 1;
            while (count < length && !bytes.isEmpty()) { target[offset + count++] = bytes.remove(); }
            return count;
        }
        @Override public synchronized void close() { closed = true; notifyAll(); }
        private synchronized void awaitBulkReads(final int expected) throws IOException {
            final long deadline = System.nanoTime() + SECONDS.toNanos(3);
            while (bulkReads < expected && System.nanoTime() < deadline) {
                try { wait(10); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture client reader interrupted", interrupted);
                }
            }
            if (bulkReads < expected) { throw new IOException("fixture client reader did not buffer early bytes"); }
        }
    }

    private static class Output extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public void close() { }
        @Override public synchronized void write(final int value) { bytes.write(value); notifyAll(); }
        @Override public synchronized void write(final byte[] source, final int offset, final int length) throws IOException {
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
