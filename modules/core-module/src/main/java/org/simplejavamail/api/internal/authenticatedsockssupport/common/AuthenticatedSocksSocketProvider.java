package org.simplejavamail.api.internal.authenticatedsockssupport.common;

import java.io.IOException;
import java.net.Socket;

/**
 * Captured authenticated-proxy choices that wrap an already owned, unconnected plain physical socket without connecting it.
 * TLS sockets are layered after the returned socket completes its plaintext SOCKS exchange.
 */
@FunctionalInterface
public interface AuthenticatedSocksSocketProvider {
    /**
     * Transfers physical disposal to the returned facade only when wrapping succeeds.
     * If wrapping fails, the caller retains the socket and must close it while preserving the original failure.
     *
     * @param unconnectedSocket a caller-owned plain transport socket; it may already be locally bound
     * @return a fresh facade that owns disposal of this exact physical socket
     * @throws IOException if the physical socket cannot be wrapped
     */
    Socket wrap(Socket unconnectedSocket) throws IOException;
}
