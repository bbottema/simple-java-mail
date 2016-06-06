package sockslib.common;

public class KeyStoreInfo {

	private final String keyStorePath;
	private final String password;
	private final String type;

	public KeyStoreInfo(String keyStorePath, String password, String type) {
		this.keyStorePath = keyStorePath;
		this.password = password;
		this.type = type;
	}

	public KeyStoreInfo(String keyStorePath, String password) {
		this(keyStorePath, password, "JKS");
	}

	public String getKeyStorePath() {
		return keyStorePath;
	}

	public String getPassword() {
		return password;
	}

	public String getType() {
		return type;
	}
}
