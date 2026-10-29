package org.simplejavamail.internal.authenticatedsockssupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.ProxyCredentials;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.Socks5;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * Please refer to {@link Socks5Bridge}.
 */
public class AuthenticatingSocks5Bridge implements Socks5Bridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("socks5bridge");
	
	@NotNull private final ProxyConfig proxyConfig;
	@NotNull private final String remoteProxyHost;
	@NotNull private final Integer remoteProxyPort;

	AuthenticatingSocks5Bridge(final ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
		this.remoteProxyHost = verifyNonnullOrEmpty(proxyConfig.getRemoteProxyHost());
		this.remoteProxyPort = verifyNonnullOrEmpty(proxyConfig.getRemoteProxyPort());
	}

	/**
	 * Refer to {@link Socks5Bridge#connect(String, InetAddress, int, SocketOwner)}.
	 */
	@Override
	public Socket connect(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort, final SocketOwner owner)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging to remote proxy {}", sessionId, proxyConfig);
		final Socket raw = new Socket();
		try {
			owner.own(raw);
		} catch (IOException | RuntimeException | Error failure) {
			try {
				if (!raw.isClosed()) { raw.close(); }
			} catch (IOException | RuntimeException | Error cleanup) {
				if (cleanup != failure) { failure.addSuppressed(cleanup); }
			}
			throw failure;
		}
		// The SMTP client's SOCKS connect budget remains authoritative. Its peer closure aborts this exact owned handle.
		raw.connect(new InetSocketAddress(remoteProxyHost, remoteProxyPort));
		final Socks5 proxy = new Socks5(new InetSocketAddress(raw.getInetAddress(), remoteProxyPort));
		proxy.setProxySocket(raw);
		if (proxyConfig.requiresAuthentication()) {
			proxy.setCredentials(new ProxyCredentials(proxyConfig.getUsername(), proxyConfig.getPassword()));
		}
		proxy.buildConnection();
		proxy.requestConnect(remoteServerAddress, remoteServerPort);
		return raw;
	}
}
