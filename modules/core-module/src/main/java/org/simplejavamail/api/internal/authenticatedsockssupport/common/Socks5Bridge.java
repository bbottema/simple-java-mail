package org.simplejavamail.api.internal.authenticatedsockssupport.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Clean seperation between the server and client packages. This bridge acts as gateway from the temporary intermediary SOCKS5 server to the remote
 * proxy.
 * <p>
 * This Bridge connects the {@code AnonymousSocks5ServerImpl} server and the {@code Socks5} client.
 */
public interface Socks5Bridge {
	/** Provider-compatible numeric address for the internal loopback-only adapter hop. */
	String LOOPBACK_HOST = "127.0.0.1";

	/**
	 * Generates a {@link Socket} using {@code Socks5} connected to authenticated proxy.
	 *
	 * @param sessionId           The current email session context.
	 * @param remoteServerAddress The numeric or unresolved domain destination behind the proxy.
	 * @param owner               Owns the raw remote socket before connection or negotiation can block.
	 *
	 * @return A socket that channels through an already authenticated SOCKS5 proxy.
	 * @throws IOException Thrown by the underlying Socket in case of connection issues.
	 */
	Socket connect(String sessionId, InetSocketAddress remoteServerAddress, SocketOwner owner)
			throws IOException;

	/** A closed owner rejects and closes a newly published handle; an already owned handle retains its single closer. */
	@FunctionalInterface
	interface SocketOwner {
		void own(Socket socket) throws IOException;
	}
}
