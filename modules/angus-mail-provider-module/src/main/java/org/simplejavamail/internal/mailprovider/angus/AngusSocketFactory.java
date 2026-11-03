package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.internal.util.SocketFactoryConfiguration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * Gives Angus an unconnected socket while retaining its timeout, bind, HTTP CONNECT, TLS and authentication implementation.
 * The per-factory binding supplies the transport identity missing from SocketFactory's no-argument API.
 */
final class AngusSocketFactory extends SocketFactory implements SocketFactoryConfiguration {

    private final Properties properties;
    private final String prefix;
    private final ThreadLocal<SocketTracker> connectingTransport = new ThreadLocal<>();
    @Nullable private final AuthenticatedSocksSocketProvider socksProvider;
    @Nullable private final Object transportDelegate;
    @Nullable private final Object transportDelegateClass;

    AngusSocketFactory(final Properties properties, final String prefix) {
        this(properties, prefix, null);
    }

    AngusSocketFactory(final Properties properties, final String prefix, @Nullable final AuthenticatedSocksSocketProvider socksProvider) {
        this.properties = properties;
        this.prefix = prefix;
        this.socksProvider = socksProvider;
        final Object declared = socksProvider == null ? null : properties.get(prefix + ".socketFactory.transport");
        if (declared instanceof AngusSocketFactory) {
            final AngusSocketFactory original = (AngusSocketFactory) declared;
            this.transportDelegate = original.transportDelegate;
            this.transportDelegateClass = original.transportDelegateClass;
        } else {
            this.transportDelegate = declared;
            this.transportDelegateClass = socksProvider == null ? null : properties.getOrDefault(prefix + ".socketFactory.transport.class",
                    properties.getProperty(prefix + ".socketFactory.transport.class"));
        }
    }

    @Override
    public Object configuredInstance() {
        return transportDelegate == null ? this : transportDelegate;
    }

    boolean isDirectSocksFactory() {
        return socksProvider != null;
    }

    boolean ownsPhysicalSocket() {
        final Object regular = properties.get(prefix + ".socketFactory");
        final boolean internalDefault = regular instanceof AngusSocketFactory && !((AngusSocketFactory) regular).isDirectSocksFactory();
        return transportDelegate == null && transportDelegateClass == null
                && (regular == null || internalDefault) && properties.getProperty(prefix + ".socketFactory.class") == null
                && properties.get(prefix + ".ssl.socketFactory") == null && properties.getProperty(prefix + ".ssl.socketFactory.class") == null;
    }

    AngusSocketFactory forProbe(final Properties probeProperties) {
        // Avoid capturing this decorator as its own delegate when a private probe copies the source properties.
        return new AngusSocketFactory(probeProperties, prefix, socksProvider);
    }

    @Nullable
    static AngusSocketFactory find(final Properties properties, final String prefix) {
        final Object transport = properties.get(prefix + ".socketFactory.transport");
        final Object regular = properties.get(prefix + ".socketFactory");
        return transport instanceof AngusSocketFactory ? (AngusSocketFactory) transport
                : transport == null && properties.getProperty(prefix + ".socketFactory.transport.class") == null
                && regular instanceof AngusSocketFactory ? (AngusSocketFactory) regular : null;
    }

    @Nullable
    SocketTracker bind(final SocketTracker transport) {
        final SocketTracker previous = connectingTransport.get();
        connectingTransport.set(transport);
        return previous;
    }

    void restore(@Nullable final SocketTracker previous) {
        if (previous == null) {
            connectingTransport.remove();
        } else {
            connectingTransport.set(previous);
        }
    }

    @Override
    public Socket createSocket() throws IOException {
        final Socket socket = socksProvider == null ? createUnconnectedSocket() : createDirectPhysicalSocket();
        if (socket == null) {
            throw new IOException("The configured physical socket factory returned no socket");
        }
        try {
            if (socksProvider != null && (socket.isClosed() || socket.isConnected() || socket instanceof SSLSocket)) {
                throw new IOException("An authenticated SOCKS transport factory must return an open, unconnected, non-SSL socket");
            }
            final SocketTracker transport = connectingTransport.get();
            if (transport != null) {
                transport.track(socket);
            }
            // HTTP CONNECT has the same precedence over SOCKS as the native Angus path.
            return socksProvider != null && properties.getProperty(prefix + ".proxy.host") == null ? socksProvider.wrap(socket) : socket;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                socket.close();
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    private Socket createDirectPhysicalSocket() throws IOException {
        if (transportDelegate != null) {
            if (!(transportDelegate instanceof SocketFactory)) {
                throw new IOException(prefix + ".socketFactory.transport must be a SocketFactory instance");
            }
            return ((SocketFactory) transportDelegate).createSocket();
        }
        if (transportDelegateClass != null) {
            if (!(transportDelegateClass instanceof String) || ((String) transportDelegateClass).isEmpty()) {
                throw new IOException(prefix + ".socketFactory.transport.class must be a non-empty class name");
            }
            return AngusSocketFactories.resolveFactory((String) transportDelegateClass, prefix + ".socketFactory.transport.class").createSocket();
        }
        final SocketFactory regular = selectedLegacyFactory();
        if (regular != null && !(regular instanceof SSLSocketFactory)) {
            // A former owned Session can retain our ordinary factory after shutdown. Its SOCKS properties now describe the remote
            // authenticated endpoint; invoking its legacy creator would introduce a second, anonymous SOCKS layer.
            if (regular instanceof AngusSocketFactory && !((AngusSocketFactory) regular).isDirectSocksFactory()) {
                return createDefaultPhysicalSocket();
            }
            final Socket selected = regular.createSocket();
            // Legacy factories may successfully decline physical creation; an explicit independent transport may not.
            return selected == null ? createDefaultPhysicalSocket() : selected;
        }
        return createDefaultPhysicalSocket();
    }

    private Socket createDefaultPhysicalSocket() throws IOException {
        return PropUtil.getBooleanProperty(properties, prefix + ".usesocketchannels", false)
                ? SocketChannel.open().socket() : new Socket(Proxy.NO_PROXY);
    }

    @Nullable
    private SocketFactory selectedLegacyFactory() throws IOException {
        try {
            // Pinned SMTPSSLTransport keeps implicit TLS even when smtps.ssl.enable=false.
            if (prefix.equals("mail.smtps") || PropUtil.getBooleanProperty(properties, prefix + ".ssl.enable", false)) {
                final SocketFactory ssl = legacyFactory(prefix + ".ssl.socketFactory");
                if (ssl != null) {
                    return ssl;
                }
            }
            return legacyFactory(prefix + ".socketFactory");
        } catch (IOException | RuntimeException failure) {
            // This is only factory resolution. Never retry after invoking createSocket, proxy setup, TLS or SMTP.
            if (PropUtil.getBooleanProperty(properties, prefix + ".socketFactory.fallback", true)) {
                return null;
            }
            throw failure;
        }
    }

    @Nullable
    private SocketFactory legacyFactory(final String key) throws IOException {
        final Object instance = properties.get(key);
        if (instance instanceof SocketFactory) {
            return (SocketFactory) instance;
        }
        final String className = properties.getProperty(key + ".class");
        return className == null || className.isEmpty() ? null : AngusSocketFactories.resolveLegacyFactory(className, key + ".class");
    }

    private Socket createUnconnectedSocket() throws IOException {
        // A supplied SocketFactory bypasses Angus's SOCKS construction, so preserve its proxy choice here.
        final String socksHost = properties.getProperty(prefix + ".socks.host");
        if (socksHost != null && properties.getProperty(prefix + ".proxy.host") == null) {
            final int separator = socksHost.indexOf(':');
            final String host = separator < 0 ? socksHost : socksHost.substring(0, separator);
            final int port = resolveSocksPort(socksHost, separator);
            return new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port)));
        }
        return PropUtil.getBooleanProperty(properties, prefix + ".usesocketchannels", false)
                ? SocketChannel.open().socket() : new Socket();
    }

    private int resolveSocksPort(final String socksHost, final int separator) {
        int defaultPort = 1080;
        if (separator >= 0) {
            try {
                defaultPort = Integer.parseInt(socksHost.substring(separator + 1));
            } catch (NumberFormatException ignored) {
                // Keep Angus's existing default for a malformed inline port.
            }
        }
        return PropUtil.getIntProperty(properties, prefix + ".socks.port", defaultPort);
    }

    @Override
    public Socket createSocket(final String host, final int port) throws IOException {
        return connectSocket(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port) throws IOException {
        return connectSocket(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(final String host, final int port, final InetAddress localHost, final int localPort) throws IOException {
        return connectSocket(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port, final InetAddress localHost, final int localPort) throws IOException {
        return connectSocket(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    private Socket connectSocket(final InetSocketAddress remoteAddress, @Nullable final InetSocketAddress localAddress) throws IOException {
        final Socket socket = createSocket();
        try {
            if (localAddress != null) {
                socket.bind(localAddress);
            }
            socket.connect(remoteAddress);
            return socket;
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Both managed sends and dedicated probes must retain a socket before Angus finishes connection setup. */
    @FunctionalInterface
    interface SocketTracker {
        void track(Socket socket) throws IOException;
    }
}
