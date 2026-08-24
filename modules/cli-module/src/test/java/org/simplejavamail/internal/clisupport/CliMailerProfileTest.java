package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CliMailerProfileTest {
	@TempDir
	Path workingDirectory;

	@Test
	void equivalentSnapshotsMatchWhileSecretsAndDaemonKeysSeparateProfiles() {
		final String firstPassword = "password-" + UUID.randomUUID();
		final String secondPassword = "password-" + UUID.randomUUID();
		final byte[] firstDaemonKey = new byte[32];
		final byte[] secondDaemonKey = new byte[32];
		secondDaemonKey[0] = 1;
		final CliMailerProfile first = profile(config(firstPassword), firstDaemonKey);
		final CliMailerProfile equivalent = profile(config(firstPassword), firstDaemonKey);
		final CliMailerProfile changedSecret = profile(config(secondPassword), firstDaemonKey);
		final CliMailerProfile anotherDaemon = profile(config(firstPassword), secondDaemonKey);

		assertThat(first).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
		assertThat(first).isNotEqualTo(changedSecret).isNotEqualTo(anotherDaemon);
		assertThat(first.toString()).doesNotContain(firstPassword).isEqualTo("CliMailerProfile{<redacted>}");
	}

	@Test
	void changingSecurityFileContentsChangesTheProfileWithoutExposingThePathOrBytes() throws Exception {
		final Path keyFile = workingDirectory.resolve("signing-key.pem");
		Files.writeString(keyFile, "first-key-material");
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), keyFile.getFileName().toString());
		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(properties).load();
		final CliMailerProfile first = profile(config, new byte[32]);

		Files.writeString(keyFile, "replacement-key-material");
		final CliMailerProfile changed = profile(config, new byte[32]);

		assertThat(first).isNotEqualTo(changed);
		assertThat(changed.toString()).doesNotContain(keyFile.toString(), "replacement-key-material");
	}

	private CliMailerProfile profile(final SimpleJavaMailConfig config, final byte[] daemonKey) {
		return CliMailerProfile.create(config, List.of(), daemonKey, workingDirectory);
	}

	private static SimpleJavaMailConfig config(final String password) {
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "smtp.example.test");
		properties.setProperty(ConfigLoader.Property.SMTP_PASSWORD.key(), password);
		return ConfigLoader.builder().withProperties(properties).load();
	}
}
