package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

/** Owns one accepted peer and its remote handle through setup, transparent forwarding and closure. */
final class SocksTunnel implements Socks5Bridge.SocketOwner, AutoCloseable {
    enum State { PENDING, FORWARDING, CLOSED }

    private static final int MAX_PENDING_BYTES = 8192;
    private final Socket local;
    private final Runnable onClosed;
    // Serializes early-byte handoff with later writes. Socket closure never acquires this forwarding-order lock.
    private final Object clientOutputOrder = new Object();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private State state = State.PENDING;
    private Socket remote;
    private Thread clientReader;
    private Throwable operationalFailure;
    private Throwable cleanupFailure;

    SocksTunnel(final Socket local, final Runnable onClosed) {
        this.local = local;
        this.onClosed = onClosed;
    }

    @Override
    public void own(final Socket socket) throws IOException {
        synchronized (this) {
            if (remote == socket) {
                if (state == State.CLOSED) { throw new SocketException("The owned SOCKS remote socket is already closing"); }
                return;
            }
            if (state != State.CLOSED) {
                if (remote != null) {
                    throw new IllegalStateException("A SOCKS tunnel already owns its remote socket");
                }
                remote = socket;
                return;
            }
        }
        final SocketException closed = new SocketException("The SOCKS client closed before remote setup could finish");
        closeSocket(socket, closed);
        throw closed;
    }

    /** The handler has finished reading the local SOCKS command; this becomes its only remaining input reader. */
    void startClientReader(final InputStream source) {
        final Thread reader;
        synchronized (this) {
            if (state == State.CLOSED) { return; }
            if (clientReader != null) { throw new IllegalStateException("The SOCKS client reader already started"); }
            reader = new Thread(() -> readClient(source), "simple-java-mail SOCKS5 client reader");
            clientReader = reader;
        }
        reader.start();
    }

    private void readClient(final InputStream source) {
        final byte[] buffer = new byte[MAX_PENDING_BYTES];
        try {
            int count;
            while (state() != State.CLOSED && (count = source.read(buffer)) != -1) {
                if (count > 0) { sendClientBytes(buffer, count); }
            }
            close();
        } catch (IOException | RuntimeException | Error problem) {
            close(problem);
        }
    }

    private void sendClientBytes(final byte[] bytes, final int count) throws IOException {
        synchronized (clientOutputOrder) {
            final Socket destination;
            synchronized (this) {
                if (state == State.CLOSED) { return; }
                if (state == State.PENDING) {
                    if (count > MAX_PENDING_BYTES - pending.size()) {
                        throw new IOException("Too much client data arrived before the SOCKS connection was established");
                    }
                    pending.write(bytes, 0, count);
                    return;
                }
                destination = remote;
            }
            final OutputStream output = destination.getOutputStream();
            output.write(bytes, 0, count);
            output.flush();
        }
    }

    /** Called only after writing the complete local CONNECT success response. */
    void beginForwarding() throws IOException {
        synchronized (clientOutputOrder) {
            final Socket destination;
            synchronized (this) {
                requirePending();
                if (remote == null) { throw new IllegalStateException("No remote SOCKS socket was published"); }
                destination = remote;
            }
            local.setSoTimeout(0);
            destination.setSoTimeout(0);
            final OutputStream output = destination.getOutputStream();
            final byte[] early;
            synchronized (this) {
                requirePending();
                state = State.FORWARDING;
                early = pending.toByteArray();
                pending.reset();
            }
            if (early.length > 0) {
                output.write(early);
                output.flush();
            }
        }
    }

    private void requirePending() throws SocketException {
        if (state == State.CLOSED) { throw new SocketException("The SOCKS tunnel is closed"); }
        if (state != State.PENDING) { throw new IllegalStateException("The SOCKS tunnel already forwards data"); }
    }

    /** The handler's worker owns the reverse direction; the existing client reader owns the other direction. */
    void pumpRemoteToClient() throws IOException {
        final Socket source;
        synchronized (this) {
            if (state == State.CLOSED) { return; }
            if (state != State.FORWARDING) { throw new IllegalStateException("The SOCKS tunnel is not forwarding"); }
            source = remote;
        }
        final InputStream input = source.getInputStream();
        final OutputStream output = local.getOutputStream();
        final byte[] buffer = new byte[MAX_PENDING_BYTES];
        int count;
        while (state() != State.CLOSED && (count = input.read(buffer)) != -1) {
            if (count > 0) {
                output.write(buffer, 0, count);
                output.flush();
            }
        }
    }

    void closeIfPending() {
        finishClose(claimClose(null, true));
    }

    @Override public void close() { close(null); }

    void close(final Throwable cause) {
        finishClose(claimClose(cause, false));
    }

    private synchronized Closing claimClose(final Throwable cause, final boolean pendingOnly) {
        // Peer EOF can retire the sockets before the setup worker reports its actual failure.
        if (cause != null && operationalFailure == null) {
            operationalFailure = cause;
            if (cleanupFailure != null && cleanupFailure != cause) { cause.addSuppressed(cleanupFailure); }
        }
        if (state == State.CLOSED || pendingOnly && state != State.PENDING) { return null; }
        state = State.CLOSED;
        return new Closing(remote);
    }

    private void finishClose(final Closing closing) {
        if (closing == null) { return; }
        recordCleanup(closeSocket(closing.remote, null));
        recordCleanup(closeSocket(local, null));
        try {
            onClosed.run();
        } catch (RuntimeException | Error cleanup) {
            recordCleanup(cleanup);
        }
    }

    private synchronized void recordCleanup(final Throwable cause) {
        if (cause == null) { return; }
        if (operationalFailure != null) {
            preserve(operationalFailure, cause);
        } else {
            cleanupFailure = preserve(cleanupFailure, cause);
        }
    }

    private static Throwable closeSocket(final Socket socket, final Throwable primary) {
        if (socket == null) { return primary; }
        try {
            if (!socket.isClosed()) { socket.close(); }
            return primary;
        } catch (IOException | RuntimeException | Error cleanup) {
            return preserve(primary, cleanup);
        }
    }

    private static Throwable preserve(final Throwable primary, final Throwable secondary) {
        if (primary == null) { return secondary; }
        if (primary != secondary) { primary.addSuppressed(secondary); }
        return primary;
    }

    synchronized State state() { return state; }
    synchronized Throwable failure() { return operationalFailure != null ? operationalFailure : cleanupFailure; }

    void awaitClientReader() throws InterruptedException {
        final Thread reader;
        synchronized (this) { reader = clientReader; }
        if (reader != null && reader != Thread.currentThread()) { reader.join(); }
    }

    private static final class Closing {
        private final Socket remote;
        private Closing(final Socket remote) {
            this.remote = remote;
        }
    }
}
