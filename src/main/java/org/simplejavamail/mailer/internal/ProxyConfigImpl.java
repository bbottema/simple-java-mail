package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

/**
 * @see ProxyConfig
 */
public class ProxyConfigImpl implements ProxyConfig {
	
	private final String remoteProxyHost;
	private final Integer remoteProxyPort;
	private final String username;
	private final String password;
	@Nonnull
	private final Integer proxyBridgePort;
	
	/**
	 * @deprecated For internal use only.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public ProxyConfigImpl(@Nullable final String remoteProxyHost, @Nullable final Integer remoteProxyPort, @Nullable final String username, @Nullable final String password, @Nonnull final Integer proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	/**
	 * @see ProxyConfig#requiresProxy()
	 */
	@Override
	public boolean requiresProxy() {
		return remoteProxyHost != null;
	}
	
	/**
	 * @see ProxyConfig#requiresAuthentication()
	 */
	@Override
	public boolean requiresAuthentication() {
		return username != null;
	}
	
	@SuppressWarnings("Duplicates")
	@Override
	public String toString() {
		if (!requiresProxy()) {
			return "no-proxy";
		}
		String str = format("%s:%s", remoteProxyHost, remoteProxyPort);
		if (requiresAuthentication()) {
			str += format(", username: %s", username);
			str += format(", proxy bridge @ localhost:%s", proxyBridgePort);
		}
		return str;
	}
	
	/**
	 * @see ProxyConfig#getProxyBridgePort()
	 */
	@Override
	@Nonnull
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}
	
	/**
	 * @see ProxyConfig#getRemoteProxyHost()
	 */
	@Override
	@Nullable
	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}
	
	/**
	 * @see ProxyConfig#getRemoteProxyPort()
	 */
	@Override
	@Nullable
	public Integer getRemoteProxyPort() {
		return remoteProxyPort;
	}
	
	/**
	 * @see ProxyConfig#getUsername()
	 */
	@Override
	@Nullable
	public String getUsername() {
		return username;
	}
	
	/**
	 * @see ProxyConfig#getPassword()
	 */
	@Override
	@Nullable
	public String getPassword() {
		return password;
	}
}