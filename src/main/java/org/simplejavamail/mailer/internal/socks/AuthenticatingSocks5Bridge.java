package org.simplejavamail.mailer.internal.socks;

import org.simplejavamail.mailer.internal.socks.common.Socks5Bridge;
import org.simplejavamail.mailer.internal.socks.socks5client.KeyStoreInfo;
import org.simplejavamail.mailer.internal.socks.socks5client.ProxyCredentials;
import org.simplejavamail.mailer.internal.socks.socks5client.SSLConfiguration;
import org.simplejavamail.mailer.internal.socks.socks5client.SSLSocks5;
import org.simplejavamail.mailer.internal.socks.socks5client.Socks5;
import org.simplejavamail.mailer.internal.socks.socks5client.SocksSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Please refer to {@link Socks5Bridge}.
 */
public class AuthenticatingSocks5Bridge implements Socks5Bridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("socks5bridge");

	private final SocksProxyConfig proxyConfig;

	public AuthenticatingSocks5Bridge(final SocksProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
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
		final Socks5 proxyAuth = new Socks5(new InetSocketAddress(proxyConfig.remoteProxyHost, proxyConfig.remoteProxyPort));
		proxyAuth.setCredentials(new ProxyCredentials(proxyConfig.username, proxyConfig.password));
		return new SocksSocket(proxyAuth, proxyAuth.createProxySocket(), new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	private Socket createSocketPlainAnonymous(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging anonymously to remote proxy {}:{}", sessionId, proxyConfig.remoteProxyHost,
				proxyConfig.remoteProxyPort);
		final Socks5 socksProxyAnonymous = new Socks5(new InetSocketAddress(proxyConfig.remoteProxyHost, proxyConfig.remoteProxyPort));
		return new SocksSocket(socksProxyAnonymous, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	@SuppressWarnings("unused")
	private Socket createSocketSSLAuthenticated(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging with SSL to remote proxy {}", sessionId, proxyConfig);
		final SSLSocks5 socksProxySSLAuth = new SSLSocks5(new InetSocketAddress("localhost", proxyConfig.proxyBridgePort),
				new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
		socksProxySSLAuth.setCredentials(new ProxyCredentials(proxyConfig.username, proxyConfig.password));
		return new SocksSocket(socksProxySSLAuth, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	@SuppressWarnings("unused")
	private Socket createSocketSSL(final String sessionId, final InetAddress remoteServerAddress, final int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging with SSL anonymously to remote proxy {}:{}", sessionId, proxyConfig.remoteProxyHost,
				proxyConfig.remoteProxyPort);
		final SSLSocks5 socksProxySSLAnonymous = new SSLSocks5(new InetSocketAddress("localhost", proxyConfig.proxyBridgePort),
				new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
		return new SocksSocket(socksProxySSLAnonymous, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}
}
