package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.SocketFetcher;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketImpl;
import java.net.SocketOption;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.Properties;

/** Observes the transport hook in the loaded Angus code, without relying on a version or private implementation name. */
final class AngusTransportSocketSupport {
    private AngusTransportSocketSupport() {
    }

    static boolean isSupported() {
        return Capability.SUPPORTED;
    }

    private static final class Capability {
        private static final boolean SUPPORTED = detect();
    }

    private static boolean detect() {
        // These sockets have no SocketImpl. Every path that could reach the operating system is replaced below.
        try (CanaryFactory regular = new CanaryFactory(); CanaryFactory transport = new CanaryFactory()) {
            final Properties isolated = new Properties();
            final String prefix = "mail.sjmtransportcapability";
            isolated.put(prefix + ".socketFactory", regular);
            isolated.put(prefix + ".socketFactory.transport", transport);
            isolated.setProperty(prefix + ".socketFactory.fallback", "false");
            try (Socket ignored = SocketFetcher.getSocket("127.0.0.1", 1, isolated, prefix, false)) {
                return false; // Both canaries always reject connection; a returned socket cannot establish the contract.
            } catch (IOException rejected) {
                return transport.socket.connectAttempted && !regular.socket.connectAttempted;
            }
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static final class CanaryFactory extends SocketFactory implements AutoCloseable {
        private final CanarySocket socket = new CanarySocket();

        private CanaryFactory() throws SocketException {
        }

        @Override public Socket createSocket() { return socket; }
        @Override public Socket createSocket(String host, int port) throws IOException { throw unexpected(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { throw unexpected(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { throw unexpected(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { throw unexpected(); }
        private IOException unexpected() { return new IOException("Unsupported in-memory transport capability operation"); }
        @Override public void close() { socket.close(); }
    }

    private static final class CanarySocket extends Socket {
        private boolean connectAttempted;
        private boolean closed;

        private CanarySocket() throws SocketException { super((SocketImpl) null); }
        @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 0); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            connectAttempted = true;
            throw new IOException("In-memory transport capability canary");
        }
        @Override public boolean isConnected() { return false; }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isBound() { return false; }
        @Override public boolean isInputShutdown() { return false; }
        @Override public boolean isOutputShutdown() { return false; }
        @Override public InetAddress getInetAddress() { return null; }
        @Override public InetAddress getLocalAddress() { return null; }
        @Override public int getPort() { return 0; }
        @Override public int getLocalPort() { return -1; }
        @Override public SocketAddress getRemoteSocketAddress() { return null; }
        @Override public SocketAddress getLocalSocketAddress() { return null; }
        @Override public void bind(SocketAddress bindpoint) throws SocketException { throw unexpected(); }
        @Override public InputStream getInputStream() throws SocketException { throw unexpected(); }
        @Override public OutputStream getOutputStream() throws SocketException { throw unexpected(); }
        @Override public void setTcpNoDelay(boolean on) throws SocketException { throw unexpected(); }
        @Override public boolean getTcpNoDelay() throws SocketException { throw unexpected(); }
        @Override public void setSoLinger(boolean on, int linger) throws SocketException { throw unexpected(); }
        @Override public int getSoLinger() throws SocketException { throw unexpected(); }
        @Override public void sendUrgentData(int data) throws SocketException { throw unexpected(); }
        @Override public void setOOBInline(boolean on) throws SocketException { throw unexpected(); }
        @Override public boolean getOOBInline() throws SocketException { throw unexpected(); }
        @Override public synchronized void setSoTimeout(int timeout) throws SocketException { throw unexpected(); }
        @Override public synchronized int getSoTimeout() throws SocketException { throw unexpected(); }
        @Override public synchronized void setSendBufferSize(int size) throws SocketException { throw unexpected(); }
        @Override public synchronized int getSendBufferSize() throws SocketException { throw unexpected(); }
        @Override public synchronized void setReceiveBufferSize(int size) throws SocketException { throw unexpected(); }
        @Override public synchronized int getReceiveBufferSize() throws SocketException { throw unexpected(); }
        @Override public void setKeepAlive(boolean on) throws SocketException { throw unexpected(); }
        @Override public boolean getKeepAlive() throws SocketException { throw unexpected(); }
        @Override public void setTrafficClass(int tc) throws SocketException { throw unexpected(); }
        @Override public int getTrafficClass() throws SocketException { throw unexpected(); }
        @Override public void setReuseAddress(boolean on) throws SocketException { throw unexpected(); }
        @Override public boolean getReuseAddress() throws SocketException { throw unexpected(); }
        @Override public void shutdownInput() throws SocketException { throw unexpected(); }
        @Override public void shutdownOutput() throws SocketException { throw unexpected(); }
        @Override public <T> Socket setOption(SocketOption<T> name, T value) throws SocketException { throw unexpected(); }
        @Override public <T> T getOption(SocketOption<T> name) throws SocketException { throw unexpected(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Collections.emptySet(); }
        @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { }
        @Override public String toString() { return "InMemoryTransportCapabilitySocket"; }
        @Override public synchronized void close() { closed = true; }
        private SocketException unexpected() { return new SocketException("Unsupported in-memory transport capability operation"); }
    }
}
