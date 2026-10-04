package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;

class LockedConfigLoaderTest {

	@Test
	void locksWinAcrossNamespacesWhileSourcesKeepTheirOwnPrecedence() {
		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withMap("central", Map.of(ConfigurationLocks.lockedName(SMTP_HOST.key()), "relay.example.org",
						ConfigurationLocks.lockedName(SMTP_PORT.key()), "0587"))
				.withMap("application", Map.of(SMTP_HOST.key(), "elsewhere.example.org", SMTP_PORT.key(), "invalid-shadowed-value",
						ConfigurationLocks.lockedName(SMTP_PORT.key()), "2525"))
				.load();
		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("relay.example.org");
		assertThat(config.getIntegerProperty(SMTP_PORT)).isEqualTo(2525);
		assertThat(config.getPropertySource(SMTP_HOST)).isEqualTo("central");
		assertThat(config.getDiagnostics().toString()).contains("simplejavamail.locked.smtp.port = 2525 (source: application)");
		config.getLocks().verify(SMTP_PORT, 2525);
		assertThatThrownBy(() -> config.getLocks().verify(SMTP_PORT, 587)).hasMessageContaining("locked.smtp.port");
	}

	@Test
	void snapshotsRetainLocksAndDetachSources() {
		final Map<String, Object> values = new HashMap<>();
		values.put(ConfigurationLocks.lockedName(SMTP_HOST.key()), "relay.example.org");
		final SimpleJavaMailConfig first = ConfigLoader.builder().withMap(values).load();
		values.put(ConfigurationLocks.lockedName(SMTP_HOST.key()), "changed.example.org");
		final SimpleJavaMailConfig copy = ConfigLoader.builder().withConfig(first)
				.withMap(Map.of(SMTP_HOST.key(), "ignored.example.org", ConfigurationLocks.lockedName(SMTP_HOST.key()), " ")).load();
		assertThat(copy.getStringProperty(SMTP_HOST)).isEqualTo("relay.example.org");
		assertThat(copy.getLocks().contains(SMTP_HOST)).isTrue();
		assertThatThrownBy(() -> copy.getLocks().getValues().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThat(ConfigLoader.builder().withMap(values).load().getStringProperty(SMTP_HOST)).isEqualTo("changed.example.org");
	}

	@Test
	void wildcardChildrenRetainTheirOwnLocksAndSources() {
		final String cluster = UUID.randomUUID().toString();
		final String maximum = "simplejavamail.defaults.connectionpool.clusters." + cluster + ".maxsize";
		final String timeout = "simplejavamail.extraproperties.mail.smtp.timeout";
		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withMap("central", Map.of(ConfigurationLocks.lockedName(maximum), "5", ConfigurationLocks.lockedName(timeout), "1000"))
				.withMap("application", Map.of(maximum, "20", timeout, "2000", "simplejavamail.extraproperties.mail.smtp.auth", "true"))
				.load();
		assertThat(config.<Map<String, String>>getProperty(EXTRA_PROPERTIES)).containsEntry("mail.smtp.timeout", "1000");
		assertThat(config.getLocks().getValues()).containsEntry(maximum, 5).containsEntry(timeout, "1000");
		assertThat(ConfigLoader.builder().withConfig(config).load().asMap()).isEqualTo(config.asMap());
		assertThat(config.hasProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).isTrue();
	}

	@Test
	void lockedCredentialsStayOutOfDiagnosticsAndConflictErrors() {
		final SimpleJavaMailConfig config = ConfigLoader.builder().withMap("central\nsource", Map.of(
				ConfigurationLocks.lockedName(SMTP_PASSWORD.key()), "configured-secret",
				"simplejavamail.locked.extraproperties.mail.smtp.oauth.token", "token-secret")).load();
		assertThat(config.getDiagnostics().toString()).doesNotContain("configured-secret", "token-secret").contains("<redacted>", "central\\nsource");
		assertThatThrownBy(() -> config.getLocks().verify(SMTP_PASSWORD, "other-secret"))
				.hasMessageNotContaining("configured-secret").hasMessageNotContaining("other-secret").hasMessageContaining("central\\nsource")
				.hasMessageContaining("This key uses simplejavamail.locked.*")
				.hasMessageContaining("fixed for every Mailer created by this factory")
				.hasMessageContaining("Ordinary properties, builder calls and Email settings cannot override it")
				.hasMessageContaining("change this lock in its configuration source");
	}

	@Test
	void invalidLockedValuesIdentifyTheActualDeclarationAndEscapeItsSourceName() {
		assertThatThrownBy(() -> ConfigLoader.builder().withMap("central\r\nsettings",
				Map.of("simplejavamail.locked.smtp.port", "not-a-port")).load())
				.hasMessageContaining("Invalid value for simplejavamail.locked.smtp.port")
				.hasMessageContaining("from source central\\r\\nsettings").hasMessageContaining("expected an integer")
				.hasMessageContaining("Correct this value in its configuration source").hasMessageNotContaining("not-a-port");
		assertThatThrownBy(() -> ConfigLoader.builder().withMap("central\r\nsettings",
				Map.of("simplejavamail.locked.extraproperties.mail.smtp.timeout", 1000)).load())
				.hasMessageContaining("Invalid value for simplejavamail.locked.extraproperties.mail.smtp.timeout")
				.hasMessageContaining("from source central\\r\\nsettings").hasMessageContaining("expected text");
		assertThatThrownBy(() -> ConfigLoader.builder().withMap("central\r\nsettings",
				Map.of("simplejavamail.locked.defaults.connectionpool.clusters.primary.maxsize", "not-a-count")).load())
				.hasMessageContaining("simplejavamail.locked.defaults.connectionpool.clusters.primary.maxsize")
				.hasMessageContaining("from source central\\r\\nsettings").hasMessageContaining("should be an integer")
				.hasMessageNotContaining("not-a-count");
	}

	@Test
	void environmentAndUnknownPropertyHandlingApplyToLockedCounterparts() {
		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withEnvironmentVariables(Map.of("SIMPLEJAVAMAIL_LOCKED_SMTP_PORT", "587")).load();
		assertThat(config.getIntegerProperty(SMTP_PORT)).isEqualTo(587);
		assertThat(config.getLocks().contains(SMTP_PORT)).isTrue();
		assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of("simplejavamail.locked.mispelled", "secret")).load())
				.hasMessageContaining("Unknown Simple Java Mail property").hasMessageNotContaining("secret")
				.hasMessageContaining("A locked key uses simplejavamail.locked.<existing-property-tail>");
	}
}
