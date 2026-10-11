package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.*;

class NamedMailerConfigTest {

	@Test
	void definitionsAreIndependentTypedImmutableAndKeepEachLeafsProvenance() {
		final Properties first = new Properties();
		first.setProperty("simplejavamail.smtp.username", "primary-user");
		first.setProperty("simplejavamail.failover.mailer", "secondary");
		first.setProperty("simplejavamail.mailers.secondary.smtp.host", "relay.example.org");
		first.setProperty("simplejavamail.mailers.secondary.smtp.port", "2525");
		first.setProperty("simplejavamail.mailers.secondary.smtp.password", "secret-password");
		first.setProperty("simplejavamail.mailers.secondary.failover.mailer", "third");
		first.setProperty("simplejavamail.mailers.third.smtp.host", "third.example.org");
		first.setProperty("simplejavamail.locked.mailers.secondary.transportstrategy", "SMTP_TLS");
		first.setProperty("simplejavamail.failover.primary.recheckinterval", "PT45S");
		final Properties last = new Properties();
		last.setProperty("simplejavamail.mailers.secondary.smtp.port", "587");
		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties("central", first).withProperties("deployment", last).load();
		first.setProperty("simplejavamail.mailers.secondary.smtp.host", "changed.example.org");
		final Map<String, SimpleJavaMailConfig> definitions = config.getProperty(NAMED_MAILERS);
		assertThat(definitions).containsOnlyKeys("secondary", "third");
		assertThatThrownBy(definitions::clear).isInstanceOf(UnsupportedOperationException.class);
		assertThat(definitions.get("secondary").<String>getProperty(SMTP_HOST)).isEqualTo("relay.example.org");
		assertThat(definitions.get("secondary").<Integer>getProperty(SMTP_PORT)).isEqualTo(587);
		assertThat(definitions.get("secondary").hasProperty(SMTP_USERNAME)).isFalse();
		assertThat(definitions.get("secondary").getPropertySource(SMTP_PORT)).isEqualTo("deployment");
		assertThat(config.<Duration>getProperty(FAILOVER_PRIMARY_RECHECK_INTERVAL)).isEqualTo(Duration.ofSeconds(45));
		assertThat(config.getDiagnostics().toString()).contains("simplejavamail.mailers.secondary.smtp.port = 587 (source: deployment)")
				.contains("simplejavamail.locked.mailers.secondary.transportstrategy")
				.doesNotContain("secret-password", "changed.example.org");
		final SimpleJavaMailConfig copied = ConfigLoader.builder().withConfig(config).load();
		assertThat(copied.<Map<String, SimpleJavaMailConfig>>getProperty(NAMED_MAILERS).get("secondary").<Integer>getProperty(SMTP_PORT)).isEqualTo(587);
	}

	@Test
	void policyWithoutARelationshipAndBackoffWithoutAnExplicitMaximumAreRejected() {
		final Properties properties = new Properties();
		properties.setProperty("simplejavamail.failover.primary.backoffmultiplier", "2");
		assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load()).mailerBuilder())
				.hasMessageContaining("simplejavamail.failover.mailer");
		properties.setProperty("simplejavamail.failover.mailer", "secondary");
		assertThatThrownBy(() -> SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load()).mailerBuilder())
				.hasMessageContaining("maximumrecheckinterval");
	}
}
