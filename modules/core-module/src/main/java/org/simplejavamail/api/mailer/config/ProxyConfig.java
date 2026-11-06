package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.Nullable;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em> will be set.
 * <p>
 * The underlying Jakarta Mail implementation handles anonymous SOCKS proxy connections directly. For authenticated SOCKS5 proxies, Simple Java Mail
 * uses a direct socket that authenticates with the remote proxy before Angus starts SMTP. Both routes work with SMTP, STARTTLS and SMTPS.
 */
public interface ProxyConfig {
	/**
	 * @return {@code true} if remoteProxyHost isn't empty.
	 */
	boolean requiresProxy();

	/**
	 * @return {@code true} if username isn't empty.
	 */
	boolean requiresAuthentication();

	@Override
	String toString();


	/**
	 * @see org.simplejavamail.api.mailer.config.SocksProxyConfig.Builder#withHost(String)
	 */
	@Nullable
	String getRemoteProxyHost();

	/**
	 * @see org.simplejavamail.api.mailer.config.SocksProxyConfig.Builder#withPort(Integer)
	 */
	@Nullable
	Integer getRemoteProxyPort();

	/**
	 * @see org.simplejavamail.api.mailer.config.SocksProxyConfig.Builder#withUsername(String)
	 */
	@Nullable
	String getUsername();

	/**
	 * @see org.simplejavamail.api.mailer.config.SocksProxyConfig.Builder#withPassword(String)
	 */
	@Nullable
	String getPassword();
}
