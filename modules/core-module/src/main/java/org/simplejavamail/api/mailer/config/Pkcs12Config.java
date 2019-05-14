package org.simplejavamail.api.mailer.config;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * Config holder for PKCS12 store+key info used for S/MIME encrypting / decrypting.
 */
public final class Pkcs12Config {
	@Nonnull
	private final InputStream pkcs12StoreStream;
	@Nonnull
	private final char[] storePassword;
	@Nonnull
	private final String keyAlias;
	@Nonnull
	private final char[] keyPassword;

	private Pkcs12Config(@Nonnull InputStream pkcs12StoreStream, @Nonnull char[] storePassword, @Nonnull String keyAlias, @Nonnull char[] keyPassword) {
		this.pkcs12StoreStream = pkcs12StoreStream;
		this.storePassword = storePassword;
		this.keyAlias = keyAlias;
		this.keyPassword = keyPassword;
	}

	public static Pkcs12ConfigBuilder builder() {
		return new Pkcs12ConfigBuilder();
	}

	@Nonnull
	public InputStream getPkcs12StoreStream() {
		return this.pkcs12StoreStream;
	}

	@Nonnull
	public char[] getStorePassword() {
		return this.storePassword;
	}

	@Nonnull
	public String getKeyAlias() {
		return this.keyAlias;
	}

	@Nonnull
	public char[] getKeyPassword() {
		return this.keyPassword;
	}

	public static class Pkcs12ConfigBuilder {
		private InputStream pkcs12StoreStream;
		private char[] storePassword;
		private String keyAlias;
		private char[] keyPassword;

		private Pkcs12ConfigBuilder() {
		}

		public Pkcs12ConfigBuilder pkcs12Store(String pkcs12StorePath) throws FileNotFoundException {
			return pkcs12Store(new File(pkcs12StorePath));
		}

		public Pkcs12ConfigBuilder pkcs12Store(File pkcs12StorePath) throws FileNotFoundException {
			return pkcs12Store(new FileInputStream(pkcs12StorePath));
		}

		public Pkcs12ConfigBuilder pkcs12Store(InputStream pkcs12StoreStream) {
			this.pkcs12StoreStream = pkcs12StoreStream;
			return this;
		}

		public Pkcs12ConfigBuilder storePassword(char[] storePassword) {
			this.storePassword = storePassword;
			return this;
		}

		public Pkcs12ConfigBuilder storePassword(String storePassword) {
			this.storePassword = storePassword.toCharArray();
			return this;
		}

		public Pkcs12ConfigBuilder keyAlias(String keyAlias) {
			this.keyAlias = keyAlias;
			return this;
		}

		public Pkcs12ConfigBuilder keyPassword(char[] keyPassword) {
			this.keyPassword = keyPassword;
			return this;
		}

		public Pkcs12ConfigBuilder keyPassword(String keyPassword) {
			this.keyPassword = keyPassword.toCharArray();
			return this;
		}

		public Pkcs12Config build() {
			return new Pkcs12Config(pkcs12StoreStream, storePassword, keyAlias, keyPassword);
		}
	}
}