package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonProcessLauncherTest {
	@Test
	void namedDefinitionsKeepInheritedLeavesNestedSettingsAndLocks() {
		final Properties inherited = new Properties();
		inherited.setProperty("simplejavamail.mailers.standby.smtp.host", "backup.example.org");
		final Properties properties = new Properties(inherited);
		properties.setProperty("simplejavamail.smtp.port", "2525");
		properties.setProperty("simplejavamail.mailers.standby.failover.mailer", "last");
		properties.setProperty("simplejavamail.mailers.last.smtp.host", "last.example.org");
		properties.setProperty("simplejavamail.locked.mailers.standby.smtp.port", "587");
		properties.setProperty("simplejavamail.mailers.standby.extraproperties.mail.smtp.auth.mechanisms", "XOAUTH2");
		properties.setProperty("simplejavamail.locked.mailers.standby.defaults.connectionpool.clusters.work.maxsize", "3");
		final Map<String, String> environment = new HashMap<>();
		environment.put("SIMPLEJAVAMAIL_SMTP_PORT", "25");
		environment.put("simplejavamail.mailers.standby.smtp.host", "environment.example.org");

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).containsEntry("SIMPLEJAVAMAIL_SMTP_PORT", "2525")
				.containsEntry("simplejavamail.mailers.standby.smtp.host", "backup.example.org")
				.containsEntry("simplejavamail.mailers.standby.failover.mailer", "last")
				.containsEntry("simplejavamail.mailers.last.smtp.host", "last.example.org")
				.containsEntry("simplejavamail.locked.mailers.standby.smtp.port", "587")
				.containsEntry("simplejavamail.mailers.standby.extraproperties.mail.smtp.auth.mechanisms", "XOAUTH2")
				.containsEntry("simplejavamail.locked.mailers.standby.defaults.connectionpool.clusters.work.maxsize", "3");
	}

	@Test
	void unsupportedJvmNamesAreNotAddedToTheChildEnvironment() {
		final Properties properties = new Properties();
		properties.setProperty("unrelated.setting", "ignored");
		properties.setProperty("simplejavamail.unknown", "ignored");
		properties.setProperty("simplejavamail.mailers.backup.unknown", "ignored");
		properties.setProperty("simplejavamail.mailers.backup.mailers.nested.smtp.host", "ignored");
		properties.setProperty("simplejavamail.mailers.backup.locked.smtp.host", "ignored");
		properties.setProperty("simplejavamail.defaults.connectionpool.clusters.work.unknown", "ignored");
		final Map<String, String> environment = new HashMap<>();

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).isEmpty();
	}

	@Test
	void childProcessReceivesLockedScalarAndWildcardSettings() {
		final Properties properties = new Properties();
		properties.setProperty("simplejavamail.locked.smtp.password", "secret");
		properties.setProperty("simplejavamail.locked.extraproperties.mail.smtp.timeout", "1000");
		final Map<String, String> environment = new HashMap<>();
		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);
		assertThat(environment).containsEntry("SIMPLEJAVAMAIL_LOCKED_SMTP_PASSWORD", "secret")
				.containsEntry("simplejavamail.locked.extraproperties.mail.smtp.timeout", "1000");
	}

	@Test
	void detachedChildReceivesSimpleJavaMailSystemPropertyConfigurationWithoutCommandLineSecrets() {
		final Properties properties = new Properties();
		final String password = "password-" + UUID.randomUUID();
		properties.setProperty("simplejavamail.smtp.password", password);
		properties.setProperty("simplejavamail.extraproperties.mail.smtp.auth.mechanisms", "XOAUTH2");
		properties.setProperty("simplejavamail.defaults.connectionpool.clusters.work.maxsize", "3");
		properties.setProperty("simplejavamail.cli.daemon.max-mailers", "99");
		properties.setProperty("unrelated", "ignored");
		final Map<String, String> environment = new HashMap<>();

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment)
				.containsEntry("SIMPLEJAVAMAIL_SMTP_PASSWORD", password)
				.containsEntry("simplejavamail.extraproperties.mail.smtp.auth.mechanisms", "XOAUTH2")
				.containsEntry("simplejavamail.defaults.connectionpool.clusters.work.maxsize", "3")
				.doesNotContainKeys("simplejavamail.cli.daemon.max-mailers", "unrelated");
	}
}
