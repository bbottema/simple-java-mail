package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import static java.util.Objects.requireNonNull;

public class ProxyCredentials {

	private String username;

	private String password;

	public ProxyCredentials() {
	}

	public ProxyCredentials(final String username, final String password) {
		this.username = username;
        this.password = requireNonNull(password, "Argument [password] may not be null");
	}

	public String getUsername() {
		return this.username;
	}

	public String getPassword() {
		return this.password;
	}

}
