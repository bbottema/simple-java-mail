package org.simplejavamail.internal.socks;

import org.simplejavamail.ProxyConfig;
import org.simplejavamail.internal.socks.common.Socks5Bridge;
import org.simplejavamail.internal.socks.socks5client.*;
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

	private final ProxyConfig proxyConfig;

	public AuthenticatingSocks5Bridge(ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
	}

	/**
	 * Refer to {@link Socks5Bridge#connect(String, InetAddress, int)}.
	 */
	@Override
	public Socket connect(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		return proxyConfig.requiresAuthentication() ?
				createSocketAuthenticated(sessionId, remoteServerAddress, remoteServerPort) :
				createSocketPlainAnonymous(sessionId, remoteServerAddress, remoteServerPort);
	}

	private Socket createSocketAuthenticated(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging to remote proxy {}", sessionId, proxyConfig);
		Socks5 proxyAuth = new Socks5(new InetSocketAddress(proxyConfig.getRemoteProxyHost(), proxyConfig.getRemoteProxyPort()));
		proxyAuth.setCredentials(new ProxyCredentials(proxyConfig.getUsername(), proxyConfig.getPassword()));
		return new SocksSocket(proxyAuth, proxyAuth.createProxySocket(), new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	private Socket createSocketPlainAnonymous(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging anonymously to remote proxy {}:{}", sessionId, proxyConfig.getRemoteProxyHost(),
				proxyConfig.getRemoteProxyPort());
		Socks5 socksProxyAnonymous = new Socks5(new InetSocketAddress(proxyConfig.getRemoteProxyHost(), proxyConfig.getRemoteProxyPort()));
		return new SocksSocket(socksProxyAnonymous, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	@SuppressWarnings("unused")
	private Socket createSocketSSLAuthenticated(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging with SSL to remote proxy {}", sessionId, proxyConfig);
		SSLSocks5 socksProxySSLAuth = new SSLSocks5(new InetSocketAddress("localhost", proxyConfig.getProxyBridgePort()),
				new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
		socksProxySSLAuth.setCredentials(new ProxyCredentials(proxyConfig.getUsername(), proxyConfig.getPassword()));
		return new SocksSocket(socksProxySSLAuth, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}

	@SuppressWarnings("unused")
	private Socket createSocketSSL(String sessionId, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		LOGGER.info("SESSION[{}] bridging with SSL anonymously to remote proxy {}:{}", sessionId, proxyConfig.getRemoteProxyHost(),
				proxyConfig.getRemoteProxyPort());
		SSLSocks5 socksProxySSLAnonymous = new SSLSocks5(new InetSocketAddress("localhost", proxyConfig.getProxyBridgePort()),
				new SSLConfiguration(null, new KeyStoreInfo("client-trust-keystore.jks", "123456", "JKS")));
		return new SocksSocket(socksProxySSLAnonymous, new InetSocketAddress(remoteServerAddress, remoteServerPort));
	}
}
