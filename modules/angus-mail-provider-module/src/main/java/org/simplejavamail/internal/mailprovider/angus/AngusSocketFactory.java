package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import javax.net.SocketFactory;

/**
 * Gives Angus an unconnected socket while retaining its timeout, bind, HTTP CONNECT, TLS and authentication implementation.
 * The per-factory binding supplies the transport identity missing from SocketFactory's no-argument API.
 */
final class AngusSocketFactory extends SocketFactory {

    private final Properties properties;
    private final String prefix;
    private final ThreadLocal<ManagedAngusTransport> connectingTransport = new ThreadLocal<>();

    AngusSocketFactory(final Properties properties, final String prefix) {
        this.properties = properties;
        this.prefix = prefix;
    }

    @Nullable
    ManagedAngusTransport bind(final ManagedAngusTransport transport) {
        final ManagedAngusTransport previous = connectingTransport.get();
        connectingTransport.set(transport);
        return previous;
    }

    void restore(@Nullable final ManagedAngusTransport previous) {
        if (previous == null) {
            connectingTransport.remove();
        } else {
            connectingTransport.set(previous);
        }
    }

    @Override
    public Socket createSocket() throws IOException {
        final Socket socket = createUnconnectedSocket();
        final ManagedAngusTransport transport = connectingTransport.get();
        if (transport != null) {
            transport.trackSocket(socket);
        }
        return socket;
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
}
