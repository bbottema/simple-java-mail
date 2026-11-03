package testutil.socks;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Observable application factories; none changes a JVM-wide socket factory or authenticator. */
public final class AuthenticatedSocksFactories {
    private AuthenticatedSocksFactories() { }

    public static final class Raw extends SocketFactory {
        public final List<Socket> created = new CopyOnWriteArrayList<>();
        @Override public Socket createSocket() {
            final Socket socket = new Socket(Proxy.NO_PROXY);
            created.add(socket);
            return socket;
        }
        @Override public Socket createSocket(final String host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
        @Override public Socket createSocket(final InetAddress host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
    }

    public static final class Tls extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        public final AtomicInteger unconnectedCreations = new AtomicInteger();
        public final List<Socket> layeredOn = new CopyOnWriteArrayList<>();
        public final List<String> peers = new CopyOnWriteArrayList<>();
        public Tls(final SSLSocketFactory delegate) { this.delegate = delegate; }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException {
            unconnectedCreations.incrementAndGet();
            return delegate.createSocket();
        }
        @Override public Socket createSocket(final Socket socket, final String host, final int port, final boolean autoClose) throws IOException {
            layeredOn.add(socket);
            peers.add(host + ":" + port);
            return delegate.createSocket(socket, host, port, autoClose);
        }
        @Override public Socket createSocket(final String host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
        @Override public Socket createSocket(final InetAddress host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
    }

    public abstract static class RawClass extends SocketFactory {
        public static Raw application;
        public static final AtomicInteger lookups = new AtomicInteger();
        public static Raw getDefault() {
            lookups.incrementAndGet();
            if (application == null) { throw new AssertionError("Missing application raw factory"); }
            return application;
        }
    }

    public abstract static class TlsClass extends SSLSocketFactory {
        public static SSLSocketFactory application;
        public static final AtomicInteger lookups = new AtomicInteger();
        public static SocketFactory getDefault() {
            lookups.incrementAndGet();
            if (application == null) { throw new AssertionError("Missing application TLS factory"); }
            return application;
        }
    }

    /** Its declared legacy type deliberately does not reveal that getDefault returns an SSL factory at runtime. */
    public abstract static class GenericTlsClass extends SocketFactory {
        public static SSLSocketFactory application;
        public static final AtomicInteger lookups = new AtomicInteger();
        public static SocketFactory getDefault() {
            lookups.incrementAndGet();
            if (application == null) { throw new AssertionError("Missing dynamically selected application TLS factory"); }
            return application;
        }
    }

    /** A successful legacy lookup may decline factory selection; native Angus then considers the next slot/default. */
    public abstract static class NullClass extends SocketFactory {
        public static final AtomicInteger lookups = new AtomicInteger();
        public static SocketFactory getDefault() {
            lookups.incrementAndGet();
            return null;
        }
    }

    /** A legacy raw creator can successfully decline socket creation without invoking exception fallback. */
    public static final class NullSocket extends SocketFactory {
        public final AtomicInteger calls = new AtomicInteger();
        @Override public Socket createSocket() { calls.incrementAndGet(); return null; }
        @Override public Socket createSocket(final String host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
        @Override public Socket createSocket(final InetAddress host, final int port) { throw connectedCreation(); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) {
            throw connectedCreation();
        }
    }

    public abstract static class NullSocketClass extends SocketFactory {
        public static NullSocket application;
        public static final AtomicInteger lookups = new AtomicInteger();
        public static SocketFactory getDefault() {
            lookups.incrementAndGet();
            if (application == null) { throw new AssertionError("Missing application null-socket factory"); }
            return application;
        }
    }

    /** A caller factory has arbitrary code and promises no closeable handle before its creation call returns. */
    public static final class Opaque extends SocketFactory {
        public final AtomicInteger calls = new AtomicInteger();
        @Override public Socket createSocket() throws IOException { calls.incrementAndGet(); throw new IOException("Opaque application creation invoked"); }
        @Override public Socket createSocket(final String host, final int port) throws IOException { return createSocket(); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) throws IOException {
            return createSocket();
        }
        @Override public Socket createSocket(final InetAddress host, final int port) throws IOException { return createSocket(); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) throws IOException {
            return createSocket();
        }
    }

    public abstract static class OpaqueClass extends SocketFactory {
        public static Opaque application;
        public static final AtomicInteger lookups = new AtomicInteger();
        public static SocketFactory getDefault() {
            lookups.incrementAndGet();
            if (application == null) { throw new AssertionError("Missing opaque application factory"); }
            return application;
        }
    }

    public static final class TrustedHosts extends MailSSLSocketFactory {
        public final List<String> checkedHosts = new CopyOnWriteArrayList<>();
        public TrustedHosts(final boolean trustLocalhost) throws GeneralSecurityException {
            setTrustedHosts(trustLocalhost ? "localhost" : "other.example.test");
        }
        @Override public synchronized boolean isServerTrusted(final String server, final SSLSocket socket) {
            checkedHosts.add(server);
            return super.isServerTrusted(server, socket);
        }
    }

    public static void selectClassFactories(final Raw raw, final SSLSocketFactory tls) {
        RawClass.application = raw;
        TlsClass.application = tls;
        RawClass.lookups.set(0);
        TlsClass.lookups.set(0);
    }

    private static AssertionError connectedCreation() {
        return new AssertionError("Authenticated SOCKS must retain the application's unconnected physical socket before connecting");
    }
}
