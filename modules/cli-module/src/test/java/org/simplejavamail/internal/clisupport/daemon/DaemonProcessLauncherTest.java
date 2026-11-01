package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonProcessLauncherTest {
	@Test
	void blankJvmValuesRetainEnvironmentChoicesAndLocks() {
		final Properties properties = new Properties();
		properties.setProperty("simplejavamail.transport.mode.logging.only", "");
		properties.setProperty("simplejavamail.locked.smtp.host", " \t ");
		properties.setProperty("simplejavamail.mailers.backup.smtp.host", " ");
		properties.setProperty("simplejavamail.extraproperties.mail.smtp.timeout", "");
		final Map<String, String> environment = new HashMap<>(Map.of(
				"SIMPLEJAVAMAIL_TRANSPORT_MODE_LOGGING_ONLY", "true",
				"SIMPLEJAVAMAIL_LOCKED_SMTP_HOST", "locked.example.org",
				"simplejavamail.mailers.backup.smtp.host", "backup.example.org",
				"simplejavamail.extraproperties.mail.smtp.timeout", "1000"));
		final Map<String, String> expected = Map.copyOf(environment);

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).isEqualTo(expected);
	}

	@Test
	void typedDirectChoicesOutrankInheritedStringsAndRetainTheirSchemaMeaning() {
		final Properties inherited = new Properties();
		inherited.setProperty("simplejavamail.transport.mode.logging.only", "false");
		inherited.setProperty("simplejavamail.smtp.port", "25");
		final Properties properties = new Properties(inherited);
		properties.put("simplejavamail.transport.mode.logging.only", Boolean.TRUE);
		properties.put("simplejavamail.smtp.port", 2525);
		properties.put("simplejavamail.transportstrategy", TransportStrategy.SMTP_TLS);
		properties.put("simplejavamail.smtp.retry.delay", Duration.ofSeconds(3));
		properties.put("simplejavamail.defaults.content.transfer.encoding", ContentTransferEncoding.BASE_64);
		properties.put("simplejavamail.defaults.mailsend.retry.maximummessagesize", 12345L);
		properties.put("simplejavamail.smtp.retry.jitter", 0.25);
		properties.put("simplejavamail.mailers.backup.smtp.port", 2526);
		properties.put("simplejavamail.mailers.backup.defaults.connectionpool.clusters.work.clusterkey.uuid", UUID.randomUUID());
		properties.put("simplejavamail.locked.mailers.backup.defaults.connectionpool.clusters.work.maxsize", 3);
		final Map<String, String> environment = new HashMap<>();

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).containsEntry("SIMPLEJAVAMAIL_TRANSPORT_MODE_LOGGING_ONLY", "true")
				.containsEntry("SIMPLEJAVAMAIL_SMTP_PORT", "2525")
				.containsEntry("SIMPLEJAVAMAIL_TRANSPORTSTRATEGY", "SMTP_TLS")
				.containsEntry("SIMPLEJAVAMAIL_SMTP_RETRY_DELAY", "PT3S")
				.containsEntry("SIMPLEJAVAMAIL_DEFAULTS_CONTENT_TRANSFER_ENCODING", "BASE_64")
				.containsEntry("SIMPLEJAVAMAIL_DEFAULTS_MAILSEND_RETRY_MAXIMUMMESSAGESIZE", "12345")
				.containsEntry("SIMPLEJAVAMAIL_SMTP_RETRY_JITTER", "0.25")
				.containsEntry("simplejavamail.mailers.backup.smtp.port", "2526")
				.containsEntry("simplejavamail.locked.mailers.backup.defaults.connectionpool.clusters.work.maxsize", "3");
	}

	@Test
	void invalidTypedChoiceIsRejectedInsteadOfBecomingValidText() {
		final Properties properties = new Properties();
		properties.put("simplejavamail.smtp.host", Boolean.TRUE);

		assertThatThrownBy(() -> DaemonProcessLauncher.copyConfigurationSystemProperties(new HashMap<>(), properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.smtp.host");
	}

	@Test
	void lowerPriorityLockStillOverridesAnInvalidOrdinaryTypedChoice() {
		final Properties properties = new Properties();
		properties.put("simplejavamail.smtp.host", Boolean.TRUE);
		final Map<String, String> environment = new HashMap<>(Map.of("SIMPLEJAVAMAIL_LOCKED_SMTP_HOST", "locked.example.org"));

		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).containsEntry("SIMPLEJAVAMAIL_LOCKED_SMTP_HOST", "locked.example.org")
				.doesNotContainKey("SIMPLEJAVAMAIL_SMTP_HOST");
	}

	@Test
	void opaqueValuesDoNotRunApplicationRenderingOrReviveInheritedStrings() {
		final Properties inherited = new Properties();
		inherited.setProperty("simplejavamail.smtp.port", "25");
		final Properties properties = new Properties(inherited);
		final AtomicBoolean rendered = new AtomicBoolean();
		properties.put("simplejavamail.smtp.port", new Object() {
			@Override public String toString() {
				rendered.set(true);
				return "sensitive-value";
			}
		});

		assertThatThrownBy(() -> DaemonProcessLauncher.copyConfigurationSystemProperties(new HashMap<>(), properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.smtp.port")
				.hasMessageNotContaining("sensitive-value");
		assertThat(rendered).isFalse();
	}

	@Test
	void typedExtraPropertiesRetainTheLoadersTextOnlyRule() {
		final Properties properties = new Properties();
		properties.put("simplejavamail.extraproperties.mail.smtp.timeout", 1000);

		assertThatThrownBy(() -> DaemonProcessLauncher.copyConfigurationSystemProperties(new HashMap<>(), properties))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("simplejavamail.extraproperties.mail.smtp.timeout");
	}

	@Test
	void clusterValuesCannotInvokeApplicationRenderingAndStillRespectLowerLocks() {
		final String name = "simplejavamail.defaults.connectionpool.clusters." + UUID.randomUUID() + ".maxsize";
		final Properties properties = new Properties();
		final AtomicBoolean rendered = new AtomicBoolean();
		properties.put(name, new Object() {
			@Override public String toString() {
				rendered.set(true);
				return "3";
			}
		});
		assertThatThrownBy(() -> DaemonProcessLauncher.copyConfigurationSystemProperties(new HashMap<>(), properties))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name);

		final String lockedName = name.replace("simplejavamail.", "simplejavamail.locked.");
		final Map<String, String> environment = new HashMap<>(Map.of(lockedName, "4"));
		DaemonProcessLauncher.copyConfigurationSystemProperties(environment, properties);

		assertThat(environment).containsOnlyKeys(lockedName).containsEntry(lockedName, "4");
		assertThat(rendered).isFalse();
	}

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
