package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

	@Test
	void legacyContentPermissionSeparatesDaemonMailerProfiles() {
		final ConfigLoader.Property property = ConfigLoader.Property.SMTP_LEGACY_CONTENT_SUPPORT;
		final CliMailerProfile strict = profile(ConfigLoader.builder().withMap(Map.of(property.key(), false)).load(), new byte[32]);
		final CliMailerProfile legacy = profile(ConfigLoader.builder().withMap(Map.of(property.key(), true)).load(), new byte[32]);
		assertThat(strict).isNotEqualTo(legacy);
	}

	private CliMailerProfile profile(final SimpleJavaMailConfig config, final byte[] daemonKey) {
		return CliMailerProfile.create(config, List.of(), daemonKey, workingDirectory);
	}

	@Test
	void namedRouteSettingsLocksDurationsAndKeyContentsParticipateInIdentity() throws Exception {
		final Path key = workingDirectory.resolve("failover-key.pem");
		Files.writeString(key, "first key");
		final Properties properties = new Properties();
		properties.setProperty("simplejavamail.failover.mailer", "other");
		properties.setProperty("simplejavamail.failover.primary.recheckinterval", "PT30S");
		properties.setProperty("simplejavamail.mailers.other.smtp.host", "other.example.org");
		properties.setProperty("simplejavamail.mailers.other." + ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key().substring("simplejavamail.".length()),
				key.getFileName().toString());
		final SimpleJavaMailConfig firstConfig = ConfigLoader.builder().withProperties(properties).load();
		final CliMailerProfile first = profile(firstConfig, new byte[32]);
		assertThat(first).isEqualTo(profile(ConfigLoader.builder().withProperties(properties).load(), new byte[32]));
		Files.writeString(key, "second key");
		assertThat(first).isNotEqualTo(profile(firstConfig, new byte[32]));
		Files.writeString(key, "first key");
		properties.setProperty("simplejavamail.locked.mailers.other.smtp.host", "other.example.org");
		assertThat(first).isNotEqualTo(profile(ConfigLoader.builder().withProperties(properties).load(), new byte[32]));
	}

	private static SimpleJavaMailConfig config(final String password) {
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "smtp.example.test");
		properties.setProperty(ConfigLoader.Property.SMTP_PASSWORD.key(), password);
		return ConfigLoader.builder().withProperties(properties).load();
	}
}
