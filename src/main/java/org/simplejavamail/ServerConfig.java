package org.simplejavamail;

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
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		if (host == null || host.trim().equals("")) {
			throw new MailException(MailException.MISSING_HOST);
		} else if ((password != null && !password.trim().equals("")) && (username == null || username.trim().equals(""))) {
			throw new MailException(MailException.MISSING_USERNAME);
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
