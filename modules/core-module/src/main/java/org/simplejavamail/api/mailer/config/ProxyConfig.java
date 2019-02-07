package org.simplejavamail.api.mailer.config;

import javax.annotation.Nullable;

import static java.lang.String.format;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em> will be set.
 * <p>
 * As the underlying JavaMail framework only support anonymous SOCKS proxy servers for non-ssl connections, authenticated SOCKS5 proxy is made
 * possible using an intermediary anonymous proxy server which relays the connection through an authenticated remote proxy server. Anonymous proxies
 * are still handled by JavaMail's own time-tested proxy client implementation.
 * <p>
 * NOTE: Attempting to use a proxy and SSL SMTP authentication will result in an error, as the underlying JavaMail framework ignores any proxy
 * settings for SSL connections.
 */
public class ProxyConfig {
	
	@Nullable private final String remoteProxyHost;
	@Nullable private final Integer remoteProxyPort;
	@Nullable private final String username;
	@Nullable private final String password;
	@Nullable private final Integer proxyBridgePort;
	
	/**
	 * @deprecated For internal use only.
	 */
	@Deprecated
	public ProxyConfig(@Nullable final String remoteProxyHost, @Nullable final Integer remoteProxyPort, @Nullable final String username, @Nullable final String password, @Nullable final Integer proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	/**
	 * @return {@code true} if remoteProxyHost isn't empty.
	 */
	public boolean requiresProxy() {
		return remoteProxyHost != null;
	}
	
	/**
	 * @return {@code true} if username isn't empty.
	 */
	public boolean requiresAuthentication() {
		return username != null;
	}
	
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
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyBridgePort(Integer)
	 */
	@Nullable
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyHost(String)
	 */
	@Nullable
	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyPort(Integer)
	 */
	@Nullable
	public Integer getRemoteProxyPort() {
		return remoteProxyPort;
	}
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyUsername(String)
	 */
	@Nullable
	public String getUsername() {
		return username;
	}
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyPassword(String)
	 */
	@Nullable
	public String getPassword() {
		return password;
	}
}