package org.simplejavamail.internal.authenticatedsockssupport;

import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.ProxyCredentials;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.Socks5;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.SocksSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

/**
 * Please refer to {@link Socks5Bridge}.
 */
public class AuthenticatingSocks5Bridge implements Socks5Bridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("socks5bridge");
	
	@Nonnull private final ProxyConfig proxyConfig;
	@Nonnull private final String remoteProxyHost;
	@Nonnull private final Integer remoteProxyPort;

	AuthenticatingSocks5Bridge(final ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
		this.remoteProxyHost = assumeNonNull(proxyConfig.getRemoteProxyHost());
		this.remoteProxyPort = assumeNonNull(proxyConfig.getRemoteProxyPort());
	}

	/**
	 * Refer to {@link Socks5Bridge#connect(String, InetAddress, int)}.
	 */
	@Override
	public Socket connect(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		return proxyConfig.requiresAuthentication() ?
				createSocketAuthenticated(sessionId, remoteServerAddress, remoteServerPort) :
				createSocketPlainAnonymous(sessionId, remoteServerAddress, remoteServerPort);
	}

	private Socket createSocketAuthenticated(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging to remote proxy {}", sessionId, proxyConfig);
		final Socks5 proxyAuth = new Socks5(new InetSocketAddress(remoteProxyHost, remoteProxyPort));
		proxyAuth.setCredentials(new ProxyCredentials(proxyConfig.getUsername(), proxyConfig.getPassword()));
		return new SocksSocket(proxyAuth, proxyAuth.createProxySocket(), new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	private Socket createSocketPlainAnonymous(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging anonymously to remote proxy {}:{}", sessionId, remoteProxyHost, remoteProxyPort);
		final Socks5 socksProxyAnonymous = new Socks5(new InetSocketAddress(remoteProxyHost, remoteProxyPort));
		return new SocksSocket(socksProxyAnonymous, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}
}
