package org.simplejavamail.mailer.internal.socks.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Clean seperation between the server and client packages. This bridge acts as gateway from the temporary intermediary SOCKS5 server to the remote
 * proxy.
 * <p>
 * This Bridge connects the {@code AnonymousSocks5ServerImpl} server and the {@code Socks5} client.
 */
public interface Socks5Bridge {
	/**
	 * Generates a {@link Socket} using {@code Socks5} connected to authenticated proxy.
	 *
	 * @param sessionId           The current email session context.
	 * @param remoteServerAddress The target server that is behind the proxy.
	 * @param remoteServerPort    The target server's port that is behind the proxy.
	 *
	 * @return A socket that channels through an already authenticated SOCKS5 proxy.
	 * @throws IOException
	 */
	@SuppressWarnings("JavaDoc")
	Socket connect(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException;
}
