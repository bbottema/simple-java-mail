package org.simplejavamail.mailer.internal.socks;

public class SocksProxyConfig {

	protected final String remoteProxyHost;
	protected final Integer remoteProxyPort;
	protected final String username;
	protected final String password;
	protected int proxyBridgePort;

	protected SocksProxyConfig(final String remoteProxyHost, final Integer remoteProxyPort, final String username, final String password, final int proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}

	boolean requiresAuthentication() {
		return username != null;
	}
}
