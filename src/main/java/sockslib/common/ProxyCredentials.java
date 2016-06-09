

package sockslib.common;

import static sockslib.utils.Util.checkNotNull;

public class ProxyCredentials {

	private String username;

	private String password;

	public ProxyCredentials() {
	}

	public ProxyCredentials(String username, String password) {
		this.username = username;
		this.password = checkNotNull(password, "Argument [password] may not be null");
	}

	public String getUsername() {
		return this.username;
	}

	public String getPassword() {
		return this.password;
	}

}
