package org.codemonkey.simplejavamail;

import static java.lang.String.format;
import static org.codemonkey.simplejavamail.internal.Util.checkNotNull;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp.socks.host"</em> and <em>"mail.smtp.socks.port"</em> will be set.
 * <p>
 * As the underlying JavaMail framework only support anonymous SOCKS proxy servers for non-ssl connections, authenticated SOCKS5 proxy is
 * made possible using an intermediary anonymous proxy server which relays the connection through an authenticated remote proxy server.
 * Anonymous proxies are still handled by JavaMail's own time-tested proxy client implementation.
 * <p>
 * Attempting to use a proxy and SSL SMTP authentication will result in an error, as the underlying JavaMail framework ignores any proxy
 * settings for SSL connections.
 */
public class ProxyConfig {

	static final ProxyConfig SKIP_PROXY_CONFIG = new ProxyConfig();

	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy. Default port is
	 * {@value #DEFAULT_PROXY_BRIDGE_PORT}.
	 */
	@SuppressWarnings("JavaDoc")
	private static final int DEFAULT_PROXY_BRIDGE_PORT = 1081;

	private int proxyBridgePort = DEFAULT_PROXY_BRIDGE_PORT;
	private final String remoteProxyHost;
	private final int remoteProxyPort;
	private final String username;
	private final String password;

	/**
	 * 'Skip proxy' constructor.
	 */
	private ProxyConfig() {
		remoteProxyHost = null;
		remoteProxyPort = -1;
		username = null;
		password = null;
	}

	/**
	 * Creates an anonymous proxy configuration.
	 *
	 * @param remoteProxyHost The host of the remote proxy.
	 * @param remoteProxyPort The port of the remote proxy.
	 */
	public ProxyConfig(String remoteProxyHost, int remoteProxyPort) {
		this(remoteProxyHost, remoteProxyPort, null, null);
	}

	/**
	 * Creates an proxy configuration, which can be anonymous or authenticated.
	 *
	 * @param remoteProxyHost The host of the remote proxy.
	 * @param remoteProxyPort The port of the remote proxy.
	 * @param username        Username is mandatory when authentication is required.
	 * @param password        Password is mandatory when authentication is required.
	 */
	@SuppressWarnings({ "WeakerAccess", "SameParameterValue" })
	public ProxyConfig(String remoteProxyHost, int remoteProxyPort, String username, String password) {
		this.remoteProxyHost = checkNotNull(remoteProxyHost, "remoteProxyHost missing");
		this.remoteProxyPort = checkNotNull(remoteProxyPort, "remoteProxyPort missing");
		this.username = username;
		this.password = password;
		if (username != null && password == null) {
			throw new MailException(MailException.MISSING_PROXY_PASSWORD);
		}
		if (username == null && password != null) {
			throw new MailException(MailException.MISSING_PROXY_USERNAME);
		}
	}

	/**
	 * If a host was provided then proxy is required.
	 */
	public boolean requiresProxy() {
		return remoteProxyHost != null;
	}

	/**
	 * If a username was provided, we will need to authenticate with the proxy.
	 */
	public boolean requiresAuthentication() {
		return username != null;
	}

	@Override
	public String toString() {
		String str = format("%s:%s", remoteProxyHost, remoteProxyPort);
		return requiresAuthentication() ? str + format(" (username: %s)", username) : str;
	}

	public int getProxyBridgePort() {
		return proxyBridgePort;
	}

	/**
	 * @param proxyBridgePort Port override for the temporary intermediary SOCKS5 relay server bridge (default is {@value
	 *                        #DEFAULT_PROXY_BRIDGE_PORT}).
	 */
	public void setProxyBridgePort(int proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
	}

	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}

	public int getRemoteProxyPort() {
		return remoteProxyPort;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}
