package org.simplejavamail.mailer.internal.socks;

public class SocksProxyConfig {

	protected final String remoteProxyHost;
	protected final Integer remoteProxyPort;
	protected final String username;
	protected final String password;
	protected int proxyBridgePort;

	public SocksProxyConfig(String remoteProxyHost, Integer remoteProxyPort, String username, String password, int proxyBridgePort) {
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
