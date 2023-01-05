package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ServerConfig;

import javax.net.ssl.SSLSocketFactory;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * @see ServerConfig
 */
class ServerConfigImpl implements ServerConfig {
	@NotNull private final String host;
	@NotNull private final Integer port;
	@Nullable private final String username;
	@Nullable private final String password;
	@Nullable private final String customSSLFactoryClass;
	@Nullable private final SSLSocketFactory customSSLFactoryInstance;

	ServerConfigImpl(@NotNull final String host, @NotNull final Integer port, @Nullable final String username, @Nullable final String password,
					 @Nullable final String customSSLFactoryClass, final @Nullable SSLSocketFactory customSSLFactoryInstance) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.customSSLFactoryClass = customSSLFactoryClass;
		this.customSSLFactoryInstance = customSSLFactoryInstance;

		if (valueNullOrEmpty(this.username) && !valueNullOrEmpty(this.password)) {
			throw new IllegalArgumentException("Password provided but not a username");
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
	@NotNull
	@Override
	public String getHost() {
		return host;
	}
	
	/**
	 * @see ServerConfig#getPort()
	 */
	@NotNull
	@Override
	public Integer getPort() {
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

	/**
	 * @see ServerConfig#getCustomSSLFactoryClass()
	 */
	@Override
	@Nullable
	public String getCustomSSLFactoryClass() {
		return customSSLFactoryClass;
	}

	/**
	 * @see ServerConfig#getCustomSSLFactoryInstance()
	 */
	@Override
	@Nullable
	public SSLSocketFactory getCustomSSLFactoryInstance() {
		return customSSLFactoryInstance;
	}
}