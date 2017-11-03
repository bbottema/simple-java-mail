package org.simplejavamail.mailer.config;

import org.simplejavamail.mailer.internal.socks.SocksProxyConfig;

import static java.lang.String.format;
import static org.simplejavamail.util.ConfigLoader.Property.*;
import static org.simplejavamail.util.ConfigLoader.valueOrProperty;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * The proxy configuration that indicates whether the connections should be routed through a proxy.
 * <p>
 * In case a proxy is required, the properties <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em> will be set.
 * <p>
 * As the underlying JavaMail framework only support anonymous SOCKS proxy servers for non-ssl connections, authenticated SOCKS5 proxy is made
 * possible using an intermediary anonymous proxy server which relays the connection through an authenticated remote proxy server. Anonymous proxies
 * are still handled by JavaMail's own time-tested proxy client implementation.
 * <p>
 * Attempting to use a proxy and SSL SMTP authentication will result in an error, as the underlying JavaMail framework ignores any proxy settings for
 * SSL connections.
 */
public class ProxyConfig extends SocksProxyConfig {

	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy. Default port is {@value
	 * #DEFAULT_PROXY_BRIDGE_PORT}.
	 */
	@SuppressWarnings("JavaDoc")
	public static final int DEFAULT_PROXY_BRIDGE_PORT = 1081;

	/**
	 * 'Skip proxy' constructor short-cut.
	 *
	 * @see #ProxyConfig(String, Integer, String, String)
	 */
	public ProxyConfig() {
		this(null, null, null, null);
	}

	/**
	 * 'Anonymous proxy' constructor short-cut.
	 *
	 * @param remoteProxyHost The host of the remote proxy.
	 * @param remoteProxyPort The port of the remote proxy.
	 * @see #ProxyConfig(String, Integer, String, String)
	 */
	public ProxyConfig(final String remoteProxyHost, final Integer remoteProxyPort) {
		this(remoteProxyHost, remoteProxyPort, null, null);
	}

	/**
	 * Creates an proxy configuration, which can be anonymous or authenticated. Host and port are required and either both or none of username and
	 * password should be provided. All arguments can be empty if the related properties are configured in a config file.
	 *
	 * @param remoteProxyHost The host of the remote proxy.
	 * @param remoteProxyPort The port of the remote proxy.
	 * @param username        Username is mandatory when authentication is required.
	 * @param password        Password is mandatory when authentication is required.
	 */
	@SuppressWarnings({ "WeakerAccess", "SameParameterValue" })
	public ProxyConfig(final String remoteProxyHost, final Integer remoteProxyPort, final String username, final String password) {
		super(
				valueOrProperty(remoteProxyHost, PROXY_HOST),
				valueOrProperty(remoteProxyPort, PROXY_PORT),
				valueOrProperty(username, PROXY_USERNAME),
				valueOrProperty(password, PROXY_PASSWORD),
				valueOrProperty(null, PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT)
		);

		if (!valueNullOrEmpty(this.remoteProxyHost)) {
			checkArgumentNotEmpty(this.remoteProxyPort, "remoteProxyPort not given and not configured in config file");

			if (!valueNullOrEmpty(this.username) && valueNullOrEmpty(this.password)) {
				throw new IllegalArgumentException("Proxy username provided but no password given as argument or in config file");
			}
			if (valueNullOrEmpty(this.username) && !valueNullOrEmpty(this.password)) {
				throw new IllegalArgumentException("Proxy password provided but no username given as argument or in config file");
			}
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
		String str = "";
		if (remoteProxyHost == null) {
			return "no-proxy";
		} else {
			str += format("%s:%s", remoteProxyHost, remoteProxyPort);
		}
		if (username != null) {
			str += format(", username: %s", username);
		}
		if (proxyBridgePort != DEFAULT_PROXY_BRIDGE_PORT) {
			str += format(", proxy bridge @ localhost:%s", proxyBridgePort);
		}
		return str;
	}

	public int getProxyBridgePort() {
		return proxyBridgePort;
	}

	/**
	 * @param proxyBridgePort Port override for the temporary intermediary SOCKS5 relay server bridge (default is {@value
	 *                        #DEFAULT_PROXY_BRIDGE_PORT}).
	 */
	public void setProxyBridgePort(@SuppressWarnings("SameParameterValue") final int proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
	}

	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}

	public Integer getRemoteProxyPort() {
		return remoteProxyPort;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}
