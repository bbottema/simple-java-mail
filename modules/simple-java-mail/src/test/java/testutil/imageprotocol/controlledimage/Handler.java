package testutil.imageprotocol.controlledimage;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/** Deterministic image I/O without a network server or a process-wide URLStreamHandlerFactory. */
public final class Handler extends URLStreamHandler {
    public static final ThreadLocal<ConnectionFactory> CONNECTIONS = new ThreadLocal<>();

    @Override
    protected URLConnection openConnection(final URL url) throws IOException {
        final ConnectionFactory connections = CONNECTIONS.get();
        if (connections == null) {
            throw new AssertionError("No image connection installed for " + url);
        }
        return connections.open(url);
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;
    }
}
