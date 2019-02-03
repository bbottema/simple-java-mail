package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.MailerRegularBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
public interface ProxyConfig {
	/**
	 * @return {@code true} if remoteProxyHost isn't empty.
	 */
	boolean requiresProxy();
	
	/**
	 * @return {@code true} if username isn't empty.
	 */
	boolean requiresAuthentication();
	
	/**
	 * @see MailerRegularBuilder#withProxyBridgePort(Integer)
	 */
	@Nonnull
	Integer getProxyBridgePort();
	
	/**
	 * @see MailerRegularBuilder#withProxyHost(String)
	 */
	@Nullable
	String getRemoteProxyHost();
	
	/**
	 * @see MailerRegularBuilder#withProxyPort(Integer)
	 */
	@Nullable
	Integer getRemoteProxyPort();
	
	/**
	 * @see MailerRegularBuilder#withProxyUsername(String)
	 */
	@Nullable
	String getUsername();
	
	/**
	 * @see MailerRegularBuilder#withProxyPassword(String)
	 */
	@Nullable
	String getPassword();
}
