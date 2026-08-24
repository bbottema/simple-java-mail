package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonProcessLauncherTest {
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
