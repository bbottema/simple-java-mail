package org.simplejavamail.api.mailer.config;

import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.util.KeyMaterialReadException;
import org.simplejavamail.internal.util.MiscUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.String.format;

/**
 * Config holder for PKCS12 store+key info used for S/MIME encrypting / decrypting.
 * Since 9.2.0 it is serializable. The serialized form contains the PKCS12 bytes and both passwords, so it must be handled as sensitive data.
 * Building requires store bytes, both passwords and an alias. Explicit empty passwords are permitted; the optional S/MIME operation
 * determines whether the supplied store, passwords and selected key are usable.
 */
@EqualsAndHashCode
public final class Pkcs12Config implements Serializable {

	private static final long serialVersionUID = 1234567L;

	@NotNull private final byte[] pkcs12StoreData;
	@NotNull private final char[] storePassword;
	@NotNull private final String keyAlias;
	@NotNull private final char[] keyPassword;

	private Pkcs12Config(byte@NotNull[] pkcs12StoreData, @NotNull char[] storePassword, @NotNull String keyAlias, @NotNull char[] keyPassword) {
		this.pkcs12StoreData = requireSupplied(pkcs12StoreData, "pkcs12StoreData").clone();
		this.storePassword = requireSupplied(storePassword, "storePassword").clone();
		this.keyAlias = requireSupplied(keyAlias, "keyAlias");
		this.keyPassword = requireSupplied(keyPassword, "keyPassword").clone();
	}

	@NotNull
	private static <T> T requireSupplied(@Nullable final T value, @NotNull final String field) {
		if (value == null) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value;
	}

	@NotNull
	public static Pkcs12ConfigBuilder builder() {
		return new Pkcs12ConfigBuilder();
	}

	/**
	 * Returns a copy of the store bytes.
	 *
	 * @see Pkcs12ConfigBuilder#pkcs12Store(byte[])
	 */
	@NotNull
	public byte[] getPkcs12StoreData() {
		return this.pkcs12StoreData.clone();
	}

	/**
	 * Returns a copy of the store password.
	 *
	 * @see Pkcs12ConfigBuilder#storePassword(char[])
	 */
	@NotNull
	public char[] getStorePassword() {
		return this.storePassword.clone();
	}

	/** @see Pkcs12ConfigBuilder#keyAlias(String) */
	@NotNull
	public String getKeyAlias() {
		return this.keyAlias;
	}

	/**
	 * Returns a copy of the private-key password.
	 *
	 * @see Pkcs12ConfigBuilder#keyPassword(char[])
	 */
	@NotNull
	public char[] getKeyPassword() {
		return this.keyPassword.clone();
	}

	@Override
	public String toString() {
		@SuppressWarnings("StringBufferReplaceableByString")
		final StringBuilder sb = new StringBuilder("Pkcs12Config{")
				.append("  storePassword=***")
				.append(", keyAlias='").append(keyAlias).append('\'')
				.append(", keyPassword=***")
				.append('}');
		return sb.toString();
	}

	public static class Pkcs12ConfigBuilder {
		private byte[] pkcs12StoreData;
		private char[] storePassword;
		private String keyAlias;
		private char[] keyPassword;

		private Pkcs12ConfigBuilder() {
		}

		/**
		 * Returns a copy of the stored bytes, or {@code null} before a store is supplied.
		 *
		 * @see #pkcs12Store(byte[])
		 */
		@Nullable
		public byte[] getPkcs12StoreData() {
			return pkcs12StoreData == null ? null : pkcs12StoreData.clone();
		}

		/**
		 * Returns a copy of the stored password, or {@code null} when none was supplied.
		 *
		 * @see #storePassword(char[])
		 */
		@Nullable
		public char[] getStorePassword() {
			return storePassword == null ? null : storePassword.clone();
		}

		/**
		 * Returns the stored alias, or {@code null} when none was supplied.
		 *
		 * @see #keyAlias(String)
		 */
		@Nullable
		public String getKeyAlias() {
			return keyAlias;
		}

		/**
		 * Returns a copy of the stored private-key password, or {@code null} when none was supplied.
		 *
		 * @see #keyPassword(char[])
		 */
		@Nullable
		public char[] getKeyPassword() {
			return keyPassword == null ? null : keyPassword.clone();
		}

		/**
		 * Reads the PKCS12 store at the supplied filesystem path.
		 *
		 * @throws IllegalArgumentException If the path is null; the previous store choice remains unchanged.
		 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the file fails with an {@link IOException}.
		 * @see #pkcs12Store(Path)
		 */
		public Pkcs12ConfigBuilder pkcs12Store(String pkcs12StorePath) {
			return pkcs12Store(new File(requireSupplied(pkcs12StorePath, "pkcs12StorePath")));
		}

		/**
		 * Reads the PKCS12 store from a file.
		 *
		 * @throws IllegalArgumentException If the file is null; the previous store choice remains unchanged.
		 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the file fails with an {@link IOException}.
		 * @see #pkcs12Store(Path)
		 */
		public Pkcs12ConfigBuilder pkcs12Store(File pkcs12StorePath) {
			return pkcs12Store(requireSupplied(pkcs12StorePath, "pkcs12StorePath").toPath());
		}

		/**
		 * Reads the PKCS12 store bytes immediately and closes the stream opened for the path.
		 * The previous store choice remains unchanged if opening, reading or closing fails.
		 *
		 * @param pkcs12StorePath Path to the PKCS12 store.
		 * @return This builder.
		 * @throws IllegalArgumentException If the path is null; the previous store choice remains unchanged.
		 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing fails with an {@link IOException}.
		 *         The original I/O cause retains any suppressed closing failure.
		 */
		public Pkcs12ConfigBuilder pkcs12Store(@NotNull final Path pkcs12StorePath) {
			final byte[] storeData;
			try (InputStream pkcs12StoreStream = Files.newInputStream(requireSupplied(pkcs12StorePath, "pkcs12StorePath"))) {
				storeData = MiscUtil.readInputStreamToBytes(pkcs12StoreStream);
			} catch (IOException e) {
				throw new KeyMaterialReadException(format("error reading PKCS12 store from path [%s]", pkcs12StorePath), e);
			}
			return pkcs12Store(storeData);
		}

		/**
		 * Reads the store bytes immediately, leaving the supplied stream open for its owner to close.
		 *
		 * @throws IllegalArgumentException If the stream is null; the previous store choice remains unchanged.
		 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if reading fails with an {@link IOException}, retained as the cause.
		 * @see #pkcs12Store(byte[])
		 */
		public Pkcs12ConfigBuilder pkcs12Store(InputStream pkcs12StoreStream) {
			try {
				this.pkcs12StoreData = MiscUtil.readInputStreamToBytes(requireSupplied(pkcs12StoreStream, "pkcs12StoreStream"));
			} catch (IOException e) {
				throw new KeyMaterialReadException("error reading PKCS12 store from input stream", e);
			}
			return this;
		}

		/**
		 * Supplies the PKCS12 store bytes. The builder retains its own copy; supplying another store replaces the bytes without changing passwords or alias.
		 *
		 * @throws IllegalArgumentException If the bytes are null; the previous store choice remains unchanged.
		 */
		public Pkcs12ConfigBuilder pkcs12Store(byte[] pkcs12StoreData) {
			this.pkcs12StoreData = requireSupplied(pkcs12StoreData, "pkcs12StoreData").clone();
			return this;
		}

		/**
		 * Supplies the password used to open the store. The builder retains its own copy; calling this again replaces the password.
		 * An empty array explicitly selects an empty password.
		 *
		 * @throws IllegalArgumentException If the password is null; the previous password choice remains unchanged.
		 */
		public Pkcs12ConfigBuilder storePassword(char[] storePassword) {
			this.storePassword = requireSupplied(storePassword, "storePassword").clone();
			return this;
		}

		/**
		 * Supplies the store password as text. Empty text explicitly selects an empty password.
		 *
		 * @throws IllegalArgumentException If the password is null; the previous password choice remains unchanged.
		 * @see #storePassword(char[])
		 */
		public Pkcs12ConfigBuilder storePassword(String storePassword) {
			this.storePassword = requireSupplied(storePassword, "storePassword").toCharArray();
			return this;
		}

		/**
		 * Chooses the private-key entry in the store. Calling this again replaces the alias without changing the store or passwords.
		 *
		 * @throws IllegalArgumentException If the alias is null; the previous alias choice remains unchanged.
		 */
		public Pkcs12ConfigBuilder keyAlias(String keyAlias) {
			this.keyAlias = requireSupplied(keyAlias, "keyAlias");
			return this;
		}

		/**
		 * Supplies the password for the selected private key. The builder retains its own copy; calling this again replaces the password.
		 * An empty array explicitly selects an empty password.
		 *
		 * @throws IllegalArgumentException If the password is null; the previous password choice remains unchanged.
		 */
		public Pkcs12ConfigBuilder keyPassword(char[] keyPassword) {
			this.keyPassword = requireSupplied(keyPassword, "keyPassword").clone();
			return this;
		}

		/**
		 * Supplies the private-key password as text. Empty text explicitly selects an empty password.
		 *
		 * @throws IllegalArgumentException If the password is null; the previous password choice remains unchanged.
		 * @see #keyPassword(char[])
		 */
		public Pkcs12ConfigBuilder keyPassword(String keyPassword) {
			this.keyPassword = requireSupplied(keyPassword, "keyPassword").toCharArray();
			return this;
		}

		/**
		 * Builds a detached configuration after requiring store bytes, store password, key alias and key password.
		 * Empty passwords are explicit choices. Store parsing, password correctness and key selection are checked when the optional
		 * S/MIME operation uses the configuration. Use the builder getters to inspect unfinished choices without constructing a value.
		 *
		 * @return The completed configuration.
		 * @throws IllegalArgumentException If a required choice was not supplied.
		 */
		public Pkcs12Config build() {
			return new Pkcs12Config(pkcs12StoreData, storePassword, keyAlias, keyPassword);
		}
	}
}
