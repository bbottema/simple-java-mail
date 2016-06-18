

package org.simplejavamail.internal.socks.socks5client;

import org.simplejavamail.internal.util.MiscUtil;

@SuppressWarnings("SameParameterValue")
public class KeyStoreInfo {

	private String keyStorePath;
	private String password;
	private String type = "JKS";

	public KeyStoreInfo() {
	}

	public KeyStoreInfo(String keyStorePath, String password, String type) {
		this.keyStorePath = MiscUtil.checkNotNull(keyStorePath, "Argument [keyStorePath] may not be null");
		this.password = MiscUtil.checkNotNull(password, "Argument [password] may not be null");
		this.type = MiscUtil.checkNotNull(type, "Argument [type] may not be null");
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

	@Override
	public String toString() {
		return "[KEY STORE] PATH:" + keyStorePath + " PASSWORD:" + password + " TYPE:" + type;
	}

}
