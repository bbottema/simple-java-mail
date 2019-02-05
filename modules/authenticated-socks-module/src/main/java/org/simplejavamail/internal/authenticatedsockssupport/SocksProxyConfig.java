package org.simplejavamail.internal.authenticatedsockssupport;

import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

/**
 * Proxy config needed for authenticating SOCKS proxy. Used in conjunction with the SOCK bridging server.
 */
// FIXME can't this come from Core Module?
public class SocksProxyConfig implements ProxyConfig {
	
	private final String remoteProxyHost;
	private final Integer remoteProxyPort;
	private final String username;
	private final String password;
	private final int proxyBridgePort;
	
	public SocksProxyConfig(final String remoteProxyHost, final Integer remoteProxyPort, final String username, final String password, final int proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	@Override
	public boolean requiresProxy() {
		return true;
	}
	
	public boolean requiresAuthentication() {
		return username != null;
	}
	
	@Nonnull
	@Override
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}
	
	@Nullable
	@Override
	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}
	
	@Nullable
	@Override
	public Integer getRemoteProxyPort() {
		return remoteProxyPort;
	}
	
	@Nullable
	@Override
	public String getUsername() {
		return username;
	}
	
	@Nullable
	@Override
	public String getPassword() {
		return password;
	}
	
	@SuppressWarnings("Duplicates")
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