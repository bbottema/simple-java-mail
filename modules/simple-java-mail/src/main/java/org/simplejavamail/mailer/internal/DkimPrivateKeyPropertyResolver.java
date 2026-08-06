package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;

final class DkimPrivateKeyPropertyResolver {

	private static final String FILE_PREFIX = "file:";
	private static final String BASE64_PREFIX = "base64:";
	private static final String PROPERTY_NAME = DKIM_PRIVATE_KEY_FILE_OR_DATA.key();

	private DkimPrivateKeyPropertyResolver() {
	}

	@NotNull
	static byte[] resolve(@NotNull final String configuredValue) {
		if (configuredValue.startsWith(FILE_PREFIX)) {
			final String filePath = configuredValue.substring(FILE_PREFIX.length());
			if (filePath.trim().isEmpty()) {
				throw new IllegalArgumentException("No DKIM private key file was provided after file: in property " + PROPERTY_NAME);
			}
			final File privateKeyFile = new File(filePath);
			if (!privateKeyFile.isFile()) {
				throw new IllegalArgumentException("DKIM private key file configured in property " + PROPERTY_NAME
						+ " does not exist or is not a regular file: " + filePath);
			}
			return readFile(privateKeyFile);
		}

		if (configuredValue.startsWith(BASE64_PREFIX)) {
			final String encodedKeyData = configuredValue.substring(BASE64_PREFIX.length());
			if (encodedKeyData.isEmpty()) {
				throw invalidBase64(null);
			}
			try {
				return Base64.getDecoder().decode(encodedKeyData);
			} catch (IllegalArgumentException e) {
				throw invalidBase64(e);
			}
		}

		// Preserve the pre-9.2 contract for existing unprefixed values.
		final File possiblePrivateKeyFile = new File(configuredValue);
		return possiblePrivateKeyFile.exists()
				? readFile(possiblePrivateKeyFile)
				: configuredValue.getBytes(UTF_8);
	}

	@NotNull
	private static byte[] readFile(@NotNull final File privateKeyFile) {
		try {
			return Files.readAllBytes(privateKeyFile.toPath());
		} catch (IOException e) {
			throw new IllegalStateException("Error reading DKIM private key file configured in property " + PROPERTY_NAME
					+ ": " + privateKeyFile, e);
		}
	}

	@NotNull
	private static IllegalArgumentException invalidBase64(final IllegalArgumentException cause) {
		return new IllegalArgumentException("Invalid Base64 DKIM private key data configured in property " + PROPERTY_NAME, cause);
	}
}
