

package org.codemonkey.simplejavamail.internal.socks.socks5client;

import org.codemonkey.simplejavamail.internal.Util;

public class ProxyCredentials {

	private String username;

	private String password;

	public ProxyCredentials() {
	}

	public ProxyCredentials(String username, String password) {
		this.username = username;
		this.password = Util.checkNotNull(password, "Argument [password] may not be null");
	}

	public String getUsername() {
		return this.username;
	}

	public String getPassword() {
		return this.password;
	}

}
