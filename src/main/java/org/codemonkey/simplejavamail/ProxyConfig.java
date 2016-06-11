package org.codemonkey.simplejavamail;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp.socks.host"</em> and <em>"mail.smtp.socks.port"</em> will be set.
 * <p>
 * As the underlying JavaMail framework only support anonymous SOCKS proxy servers for non-ssl connections, authenticated SOCKS5 proxy is
 * made possible using an intermediary anonymous proxy server which relays the connection through an authenticated remote proxy server.
 * <p>
 * Attempting to use a proxy and SSL SMTP authentication will result in an error, as the underlying JavaMail framework ignores any proxy
 * settings for SSL connections.
 */
public class ProxyConfig {
	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy. Default port is
	 * {@value #DEFAULT_PROXY_BRIDGE_PORT}.
	 */
	private static final int DEFAULT_PROXY_BRIDGE_PORT = 1081;

	private int proxyBridgePort = DEFAULT_PROXY_BRIDGE_PORT;
	private final String remoteProxyHost;
	private final int remoteProxyPort;
	private final String username;
	private final String password;

	public ProxyConfig(String remoteProxyHost, int remoteProxyPort, String username, String password) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
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
