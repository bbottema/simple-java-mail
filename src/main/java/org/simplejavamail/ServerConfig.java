package org.simplejavamail;

import static org.simplejavamail.internal.util.ConfigLoader.Property.*;
import static org.simplejavamail.internal.util.ConfigLoader.valueOrProperty;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

public class ServerConfig {
	private final String host;
	private final Integer port;
	private final String username;
	private final String password;

	/**
	 * @param host     The address URL of the SMTP server to be used.
	 * @param port     The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>.
	 */
	public ServerConfig(String host, Integer port, String username, String password) {
		this.host = valueOrProperty(host, SMTP_HOST);
		this.port = valueOrProperty(port, SMTP_PORT);
		this.username = valueOrProperty(username, SMTP_USERNAME);
		this.password = valueOrProperty(password, SMTP_PASSWORD);

		checkArgumentNotEmpty(this.host, "smtp host not given and not configured in config file");
		checkArgumentNotEmpty(this.port, "smtp host port not given and not configured in config file");

		if (!valueNullOrEmpty(username) && valueNullOrEmpty(password)) {
			throw new IllegalArgumentException("Username provided but no password given as argument or in config file");
		}
		if (valueNullOrEmpty(username) && !valueNullOrEmpty(password)) {
			throw new IllegalArgumentException("Password provided but no username given as argument or in config file");
		}
	}

	public String getHost() {
		return host;
	}

	public Integer getPort() {
		return port;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}
