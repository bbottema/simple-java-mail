package sockslib.common;

public class ProxyCredentials {

	private String username;

	private String password;

	public ProxyCredentials() {
	}

	public ProxyCredentials(String username, String password) {
		if (username == null) {
			throw new NullPointerException("username required");
		}
		if (password == null) {
			throw new NullPointerException("password required");
		}
		this.username = username;
		this.password = password;
	}

	public String getUsername() {
		return this.username;
	}

	public String getPassword() {
		return this.password;
	}

}
