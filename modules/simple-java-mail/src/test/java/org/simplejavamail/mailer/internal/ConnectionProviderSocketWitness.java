package org.simplejavamail.mailer.internal;

import org.eclipse.angus.mail.util.SocketFetcher;
import org.mockito.MockedConstruction;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

/** The actual provider chooses/parses the route; only the JDK socket resource and connect boundary are intercepted. */
public final class ConnectionProviderSocketWitness {
    private ConnectionProviderSocketWitness() { }

    static List<InetSocketAddress> socksEndpoints(final Properties properties, final String prefix) throws Exception {
        mock(Object.class); // Initialize instrumentation before JDK Socket constructors are intercepted.
        final List<InetSocketAddress> endpoints = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) -> {
            if (context.arguments().size() == 1 && context.arguments().get(0) instanceof Proxy) {
                endpoints.add((InetSocketAddress) ((Proxy) context.arguments().get(0)).address());
            }
            doThrow(new IOException("Connection boundary intercepted")).when(socket).connect(any(SocketAddress.class), anyInt());
        })) {
            try { SocketFetcher.getSocket("127.0.0.1", 25, properties, prefix, false); }
            catch (IOException intercepted) { assertThat(intercepted).hasMessageContaining("127.0.0.1"); }
            assertThat(sockets.constructed()).isNotEmpty();
            for (Socket socket : sockets.constructed()) { verify(socket).connect(any(SocketAddress.class), anyInt()); }
        }
        assertThat(endpoints).isNotEmpty();
        return endpoints;
    }

    static void exerciseFactoryLookup(final Properties properties, final String prefix, final boolean ssl) throws Exception {
        mock(Object.class);
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doThrow(new IOException("Connection boundary intercepted")).when(socket).connect(any(SocketAddress.class), anyInt()))) {
            try { SocketFetcher.getSocket("127.0.0.1", 25, properties, prefix, ssl); }
            catch (IOException intercepted) { /* Actual factory lookup has already happened before connect. */ }
            assertThat(sockets.constructed()).isNotEmpty();
            for (Socket socket : sockets.constructed()) { verify(socket).connect(any(SocketAddress.class), anyInt()); }
        }
    }

    /** Public class/getDefault are required by the provider's actual reflective lookup. */
    public static final class RecordingFactory extends SocketFactory {
        static final AtomicInteger CLASS_LOOKUPS = new AtomicInteger();
        static final AtomicInteger SOCKET_CREATIONS = new AtomicInteger();
        public static SocketFactory getDefault() { CLASS_LOOKUPS.incrementAndGet(); return new RecordingFactory(); }
        @Override public Socket createSocket() { SOCKET_CREATIONS.incrementAndGet(); return new Socket(); }
        @Override public Socket createSocket(final String host, final int port) { throw new AssertionError("Unexpected eager connect"); }
        @Override public Socket createSocket(final String host, final int port, final InetAddress local, final int localPort) { throw new AssertionError("Unexpected eager connect"); }
        @Override public Socket createSocket(final InetAddress host, final int port) { throw new AssertionError("Unexpected eager connect"); }
        @Override public Socket createSocket(final InetAddress host, final int port, final InetAddress local, final int localPort) { throw new AssertionError("Unexpected eager connect"); }
    }
}
