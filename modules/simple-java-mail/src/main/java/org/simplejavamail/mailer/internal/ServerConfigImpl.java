package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ServerConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * @see ServerConfig
 */
public class ServerConfigImpl implements ServerConfig {
	private final String host;
	private final Integer port;
	@Nullable
	private final String username;
	@Nullable
	private final String password;
	
	/**
	 * @deprecated For internal use only.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public ServerConfigImpl(@Nonnull final String host, @Nonnull final Integer port, @Nullable final String username, @Nullable final String password) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		
		if (valueNullOrEmpty(this.username) && !valueNullOrEmpty(this.password)) {
			throw new IllegalArgumentException("Password provided but no username given as argument or in config file");
		}
	}
	
	@Override
	public String toString() {
		String str = format("%s:%s", host, port);
		if (username != null) {
			str += format(", username: %s", username);
		}
		if (password != null) {
			str += " (authenticated)";
		}
		return str;
	}
	
	/**
	 * @see ServerConfig#getHost()
	 */
	@Override public String getHost() {
		return host;
	}
	
	/**
	 * @see ServerConfig#getPort()
	 */
	@Override public Integer getPort() {
		return port;
	}
	
	/**
	 * @see ServerConfig#getUsername()
	 */
	@Override
	@Nullable
	public String getUsername() {
		return username;
	}
	
	/**
	 * @see ServerConfig#getPassword()
	 */
	@Override
	@Nullable
	public String getPassword() {
		return password;
	}
}