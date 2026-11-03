package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.net.ssl.SSLSocket;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/** A logical destination socket over the supplied, directly connected SOCKS physical socket. */
public final class SocksSocket extends Socket {
    private final Socket physical;
    private final InetSocketAddress proxyAddress;
    private final ProxyCredentials credentials;
    private final Object state = new Object();
    private volatile InetSocketAddress destination;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean closed;

    /** Captures immutable facts only; neither creating this capability nor wrapping resolves or connects a socket. */
    public static AuthenticatedSocksSocketProvider providerFor(final ProxyConfig config) {
        requireNonNull(config, "proxyConfig");
        final String host = requireNonNull(config.getRemoteProxyHost(), "remoteProxyHost");
        final int port = requireNonNull(config.getRemoteProxyPort(), "remoteProxyPort");
        final String username = config.getUsername();
        final String password = config.getPassword();
        final InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(host, port);
        SocksAuthenticationHelper.validateCredentials(username, password);
        return raw -> new SocksSocket(raw, proxyAddress, new ProxyCredentials(username, password));
    }

    private SocksSocket(final Socket physical, final InetSocketAddress proxyAddress, final ProxyCredentials credentials)
            throws SocketException {
        this.physical = requireNonNull(physical, "unconnectedSocket");
        if (physical.isConnected()) { throw new SocketException("Authenticated SOCKS requires an unconnected physical socket"); }
        if (physical instanceof SSLSocket) { throw new SocketException("Authenticated SOCKS requires a plain physical socket before TLS layering"); }
        this.proxyAddress = requireNonNull(proxyAddress, "proxyAddress");
        this.credentials = credentials;
    }

    // Internal construction retained alongside the bridge fallback.
    public SocksSocket(final Socks5 proxy, final Socket physical) throws SocketException {
        this(physical, requireNonNull(proxy, "proxy").getProxyAddress(), proxy.getCredentials());
    }

    public SocksSocket(final Socks5 proxy, final Socket physical, final InetSocketAddress target) throws IOException {
        this(proxy, physical);
        connect(target);
    }

    public SocksSocket(final Socks5 proxy) throws IOException { this(proxy, proxy.createProxySocket()); }
    public SocksSocket(final Socks5 proxy, final InetSocketAddress target) throws IOException { this(proxy); connect(target); }

    @Override public void connect(final SocketAddress endpoint) throws IOException { connect(endpoint, 0); }

    @Override public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
        if (endpoint == null) { throw new IllegalArgumentException("connect: The address cannot be null"); }
        if (timeout < 0) { throw new IllegalArgumentException("connect: timeout cannot be negative"); }
        requireOpen();
        synchronized (state) {
            if (connected || connecting) { throw new SocketException("Socket is already connected or connecting"); }
        }
        if (!(endpoint instanceof InetSocketAddress)) { throw new IllegalArgumentException("Unsupported address type"); }
        final InetSocketAddress target = (InetSocketAddress) endpoint;
        checkDestinationPermission(target);
        synchronized (state) {
            if (closed) { throw new SocketException("Socket is closed"); }
            if (connected || connecting) { throw new SocketException("Socket is already connected or connecting"); }
            connecting = true;
        }
        try {
            final ConnectBudget budget = new ConnectBudget(timeout);
            final int ordinaryReadTimeout = physical.getSoTimeout();
            final InetSocketAddress proxy = proxyAddress.isUnresolved()
                    ? new InetSocketAddress(proxyAddress.getHostString(), proxyAddress.getPort()) : proxyAddress;
            physical.connect(proxy, budget.remainingMillis());
            final Socket negotiation = new NegotiationSocket(budget);
            if (SocksAuthenticationHelper.shouldAuthenticate(negotiation)) {
                SocksAuthenticationHelper.performUserPasswordAuthentication(negotiation, credentials);
            }
            SocksCommandSender.send(negotiation, target);
            budget.remainingMillis();
            physical.setSoTimeout(ordinaryReadTimeout);
            requireOpen();
            synchronized (state) {
                if (closed) { throw new SocketException("Socket was closed during SOCKS setup"); }
                destination = target;
                connected = true;
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        } finally {
            connecting = false;
        }
    }

    @SuppressWarnings("removal")
    private static void checkDestinationPermission(final InetSocketAddress target) {
        final SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkConnect(target.isUnresolved() ? target.getHostString() : target.getAddress().getHostAddress(), target.getPort());
        }
    }

    private void requireOpen() throws SocketException {
        if (isClosed()) { throw new SocketException("Socket is closed"); }
    }

    private void requireConnected() throws SocketException {
        requireOpen();
        if (!connected) { throw new SocketException("Socket is not connected"); }
    }

    private void closeAfterFailure(final Throwable failure) {
        try {
            close();
        } catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) { failure.addSuppressed(cleanup); }
        }
    }

    @Override public void close() throws IOException {
        synchronized (state) {
            if (closed) { return; }
            closed = true;
        }
        // No state monitor is held across physical disposal or blocking protocol work.
        if (!physical.isClosed()) { physical.close(); }
    }

    @Override public InputStream getInputStream() throws IOException { requireConnected(); return physical.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { requireConnected(); return physical.getOutputStream(); }
    @Override public void bind(final SocketAddress point) throws IOException { requireOpen(); physical.bind(point); }
    @Override public InetAddress getInetAddress() { return connected ? destination.getAddress() : null; }
    @Override public int getPort() { return connected ? destination.getPort() : 0; }
    @Override public SocketAddress getRemoteSocketAddress() { return connected ? destination : null; }
    @Override public InetAddress getLocalAddress() { return physical.getLocalAddress(); }
    @Override public int getLocalPort() { return physical.getLocalPort(); }
    @Override public SocketAddress getLocalSocketAddress() { return physical.getLocalSocketAddress(); }
    @Override public SocketChannel getChannel() { return physical.getChannel(); }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isBound() { return physical.isBound(); }
    @Override public boolean isClosed() { return closed || physical.isClosed(); }
    @Override public boolean isInputShutdown() { return physical.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return physical.isOutputShutdown(); }
    @Override public void shutdownInput() throws IOException { requireConnected(); physical.shutdownInput(); }
    @Override public void shutdownOutput() throws IOException { requireConnected(); physical.shutdownOutput(); }
    @Override public void setTcpNoDelay(final boolean value) throws SocketException { requireOpen(); physical.setTcpNoDelay(value); }
    @Override public boolean getTcpNoDelay() throws SocketException { requireOpen(); return physical.getTcpNoDelay(); }
    @Override public void setSoLinger(final boolean enabled, final int value) throws SocketException { requireOpen(); physical.setSoLinger(enabled, value); }
    @Override public int getSoLinger() throws SocketException { requireOpen(); return physical.getSoLinger(); }
    @Override public void sendUrgentData(final int value) throws IOException { requireConnected(); physical.sendUrgentData(value); }
    @Override public void setOOBInline(final boolean value) throws SocketException { requireOpen(); physical.setOOBInline(value); }
    @Override public boolean getOOBInline() throws SocketException { requireOpen(); return physical.getOOBInline(); }
    @Override public void setSoTimeout(final int value) throws SocketException { requireOpen(); physical.setSoTimeout(value); }
    @Override public int getSoTimeout() throws SocketException { requireOpen(); return physical.getSoTimeout(); }
    @Override public void setSendBufferSize(final int value) throws SocketException { requireOpen(); physical.setSendBufferSize(value); }
    @Override public int getSendBufferSize() throws SocketException { requireOpen(); return physical.getSendBufferSize(); }
    @Override public void setReceiveBufferSize(final int value) throws SocketException { requireOpen(); physical.setReceiveBufferSize(value); }
    @Override public int getReceiveBufferSize() throws SocketException { requireOpen(); return physical.getReceiveBufferSize(); }
    @Override public void setKeepAlive(final boolean value) throws SocketException { requireOpen(); physical.setKeepAlive(value); }
    @Override public boolean getKeepAlive() throws SocketException { requireOpen(); return physical.getKeepAlive(); }
    @Override public void setTrafficClass(final int value) throws SocketException { requireOpen(); physical.setTrafficClass(value); }
    @Override public int getTrafficClass() throws SocketException { requireOpen(); return physical.getTrafficClass(); }
    @Override public void setReuseAddress(final boolean value) throws SocketException { requireOpen(); physical.setReuseAddress(value); }
    @Override public boolean getReuseAddress() throws SocketException { requireOpen(); return physical.getReuseAddress(); }
    @Override public void setPerformancePreferences(final int connection, final int latency, final int bandwidth) {
        physical.setPerformancePreferences(connection, latency, bandwidth);
    }
    @Override public <T> Socket setOption(final SocketOption<T> option, final T value) throws IOException {
        requireOpen(); physical.setOption(option, value); return this;
    }
    @Override public <T> T getOption(final SocketOption<T> option) throws IOException { requireOpen(); return physical.getOption(option); }
    @Override public Set<SocketOption<?>> supportedOptions() { return physical.supportedOptions(); }
    @Override public String toString() { return connected ? "SocksSocket[remote=" + destination + "]" : "SocksSocket[unconnected]"; }

    private final class NegotiationSocket extends Socket {
        private final ConnectBudget budget;
        private NegotiationSocket(final ConnectBudget budget) { this.budget = budget; }
        @Override public InputStream getInputStream() throws IOException {
            return new FilterInputStream(physical.getInputStream()) {
                @Override public int read() throws IOException {
                    physical.setSoTimeout(budget.remainingMillis());
                    return in.read();
                }
                @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                    if (length == 0) { return 0; }
                    physical.setSoTimeout(budget.remainingMillis());
                    return in.read(bytes, offset, length);
                }
            };
        }
        @Override public OutputStream getOutputStream() throws IOException {
            return new FilterOutputStream(physical.getOutputStream()) {
                @Override public void write(final int value) throws IOException {
                    budget.remainingMillis(); out.write(value); budget.remainingMillis();
                }
                @Override public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                    budget.remainingMillis(); out.write(bytes, offset, length); budget.remainingMillis();
                }
                @Override public void flush() throws IOException { budget.remainingMillis(); out.flush(); budget.remainingMillis(); }
            };
        }
        @Override public void close() throws IOException { SocksSocket.this.close(); }
    }

    private static final class ConnectBudget {
        private final int timeout;
        private final long started = System.nanoTime();
        private ConnectBudget(final int timeout) { this.timeout = timeout; }
        private int remainingMillis() throws SocketTimeoutException {
            if (timeout == 0) { return 0; }
            final long remaining = TimeUnit.MILLISECONDS.toNanos(timeout) - (System.nanoTime() - started);
            if (remaining <= 0) { throw new SocketTimeoutException("SOCKS connection setup timed out"); }
            return (int) Math.min(Integer.MAX_VALUE, (remaining + 999999L) / 1000000L);
        }
    }
}
