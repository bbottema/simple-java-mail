package org.simplejavamail.mailer.internal.socks;

import static java.lang.String.format;

/**
 * Proxy config needed for authenticating SOCKS proxy. Used in conjunction with the SOCK bridging server.
 */
public class SocksProxyConfig {
	
	final String remoteProxyHost;
	final Integer remoteProxyPort;
	final String username;
	final String password;
	final int proxyBridgePort;
	
	public SocksProxyConfig(final String remoteProxyHost, final Integer remoteProxyPort, final String username, final String password, final int proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	boolean requiresAuthentication() {
		return username != null;
	}
	
	@Override
	public String toString() {
		String str = format("%s:%s", remoteProxyHost, remoteProxyPort);
		if (requiresAuthentication()) {
			str += format(", username: %s", username);
			str += format(", proxy bridge @ localhost:%s", proxyBridgePort);
		}
		return str;
	}
}