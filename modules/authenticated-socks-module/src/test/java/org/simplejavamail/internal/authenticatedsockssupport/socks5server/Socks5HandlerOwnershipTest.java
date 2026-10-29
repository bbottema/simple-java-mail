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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class Socks5HandlerOwnershipTest {

    @Test
    void failureWritingTheLocalConnectSuccessClosesTheAlreadyOwnedRemoteSocket() throws Exception {
        final HandshakeInput localInput = new HandshakeInput();
        final AtomicInteger replies = new AtomicInteger();
        final IOException localFailure = new IOException("local peer disappeared while receiving CONNECT success");
        final OutputStream localOutput = new OutputStream() {
            @Override public void write(final int octet) throws IOException {
                throw new AssertionError("The local reply is written as a complete frame");
            }

            @Override public void write(final byte[] bytes, final int offset, final int count) throws IOException {
                if (replies.incrementAndGet() == 2) {
                    throw localFailure;
                }
            }
        };
        final AtomicInteger remoteReads = new AtomicInteger();
        final InputStream remoteInput = new ByteArrayInputStream(new byte[0]) {
            @Override public int read(final byte[] bytes, final int offset, final int count) {
                remoteReads.incrementAndGet();
                return super.read(bytes, offset, count);
            }
        };
        final OwnedSocket local = new OwnedSocket(localInput, localOutput);
        final OwnedSocket remote = new OwnedSocket(remoteInput, new ByteArrayOutputStream());
        final SocksTunnel tunnel = new SocksTunnel(local, () -> { });

        new Socks5Handler(new SocksSession(local), (id, address, owner) -> {
            owner.own(remote);
            return remote;
        }, tunnel).run();

        assertThat(replies).hasValue(2);
        assertThat(local.closes).hasValue(1);
        assertThat(remote.closes).hasValue(1);
        assertThat(remoteReads).as("no reverse pump before local success is delivered").hasValue(0);
        assertThat(tunnel.failure()).isSameAs(localFailure);
    }

    private static final class HandshakeInput extends InputStream {
        private final byte[] command = new byte[] {5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, 0, 25};
        private int offset;
        private boolean closed;

        @Override public synchronized int read() throws IOException {
            if (offset < command.length) { return command[offset++] & 0xff; }
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture peer reader interrupted", interrupted);
                }
            }
            return -1;
        }

        @Override public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    private static final class OwnedSocket extends Socket {
        private final InputStream input;
        private final OutputStream output;
        private final AtomicInteger closes = new AtomicInteger();
        private volatile boolean closed;
        private int timeout;

        private OwnedSocket(final InputStream input, final OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 50025); }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return 50026; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(final int timeout) { this.timeout = timeout; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            closed = true;
            input.close();
            output.close();
        }
    }
}
