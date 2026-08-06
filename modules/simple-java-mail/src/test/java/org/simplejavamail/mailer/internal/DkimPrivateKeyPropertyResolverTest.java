package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DkimPrivateKeyPropertyResolverTest {

	@Test
	public void resolvesExplicitFileAndBase64Values() throws Exception {
		final byte[] privateKeyData = new byte[] { 0, 1, 2, 3, -1 };
		final File privateKeyFile = File.createTempFile("simple-java-mail-dkim-", ".der");
		try {
			Files.write(privateKeyFile.toPath(), privateKeyData);

			assertThat(DkimPrivateKeyPropertyResolver.resolve("file:" + privateKeyFile.getAbsolutePath()))
					.containsExactly(privateKeyData);
			assertThat(DkimPrivateKeyPropertyResolver.resolve("base64:" + Base64.getEncoder().encodeToString(privateKeyData)))
					.containsExactly(privateKeyData);
		} finally {
			//noinspection ResultOfMethodCallIgnored
			privateKeyFile.delete();
		}
	}

	@Test
	public void preservesLegacyUnprefixedValues() throws Exception {
		final byte[] privateKeyData = new byte[] { 4, 5, 6 };
		final File privateKeyFile = File.createTempFile("simple-java-mail-dkim-legacy-", ".der");
		try {
			Files.write(privateKeyFile.toPath(), privateKeyData);

			assertThat(DkimPrivateKeyPropertyResolver.resolve(privateKeyFile.getAbsolutePath()))
					.containsExactly(privateKeyData);
			assertThat(DkimPrivateKeyPropertyResolver.resolve("legacy-key-data"))
					.containsExactly("legacy-key-data".getBytes(UTF_8));
		} finally {
			//noinspection ResultOfMethodCallIgnored
			privateKeyFile.delete();
		}
	}

	@Test
	public void rejectsInvalidExplicitValuesWithoutEchoingKeyData() {
		assertThatThrownBy(() -> DkimPrivateKeyPropertyResolver.resolve("base64:not-base64!"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalid Base64 DKIM private key data configured in property simplejavamail.dkim.signing.private_key_file_or_data")
				.hasCauseInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> DkimPrivateKeyPropertyResolver.resolve("base64:"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalid Base64 DKIM private key data configured in property simplejavamail.dkim.signing.private_key_file_or_data");

		final String missingFile = "target/missing-dkim-private-key-" + System.nanoTime() + ".der";
		assertThatThrownBy(() -> DkimPrivateKeyPropertyResolver.resolve("file:" + missingFile))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("DKIM private key file configured in property simplejavamail.dkim.signing.private_key_file_or_data does not exist or is not a regular file: " + missingFile);
	}
}
