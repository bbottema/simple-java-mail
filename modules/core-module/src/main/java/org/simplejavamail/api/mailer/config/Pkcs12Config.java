package org.simplejavamail.api.mailer.config;

import java.io.InputStream;

/**
 * Config holder for PKCS12 store+key info used for S/MIME encrypting / decrypting.
 */
public class Pkcs12Config {
	private final InputStream pkcs12StoreStream;
	private final char[] storePassword;
	private final String keyAlias;
	private final char[] keyPassword;

	public Pkcs12Config(final InputStream pkcs12StoreStream, final char[] storePassword, final String keyAlias, final char[] keyPassword) {
		this.pkcs12StoreStream = pkcs12StoreStream;
		this.storePassword = storePassword;
		this.keyAlias = keyAlias;
		this.keyPassword = keyPassword;
	}

	public InputStream getPkcs12StoreStream() {
		return pkcs12StoreStream;
	}

	public char[] getStorePassword() {
		return storePassword;
	}

	public String getKeyAlias() {
		return keyAlias;
	}

	public char[] getKeyPassword() {
		return keyPassword;
	}
}