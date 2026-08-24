package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.Nullable;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em> will be set.
 * <p>
 * The underlying Jakarta Mail implementation handles anonymous SOCKS proxy connections directly. For authenticated SOCKS5 proxies, Simple Java Mail
 * starts an intermediary anonymous proxy server which relays the connection through the authenticated remote proxy server. Both routes work with
 * SMTP, STARTTLS and SMTPS transports.
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
	 * Returns the configured bridge port. A value of {@code 0} means the operating system selects the actual loopback port when the bridge starts.
	 * The effective Jakarta Mail Session contains the selected port while an authenticated-proxy operation is running.
	 *
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyBridgePort(Integer)
	 */
	@Nullable
	Integer getProxyBridgePort();

	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyHost(String)
	 */
	@Nullable
	String getRemoteProxyHost();

	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyPort(Integer)
	 */
	@Nullable
	Integer getRemoteProxyPort();

	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyUsername(String)
	 */
	@Nullable
	String getUsername();

	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxyPassword(String)
	 */
	@Nullable
	String getPassword();
}
