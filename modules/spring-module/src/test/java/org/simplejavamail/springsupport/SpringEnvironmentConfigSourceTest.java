package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigDiagnostics;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;

class SpringEnvironmentConfigSourceTest {

	@Test
	void reportsTheHighestPriorityUnderlyingSpringPropertySource() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("low priority", SMTP_HOST.key(), "low.example.test"));
		environment.getPropertySources().addFirst(source("application-production.properties", SMTP_HOST.key(), "smtp.example.test"));

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), SMTP_HOST.key());

		assertThat(diagnostic.getDisplayValue()).isEqualTo("smtp.example.test");
		assertThat(diagnostic.getSourceName()).isEqualTo("application-production.properties");
	}

	@Test
	void reportsTheSourceOfACompatibilityAliasWhileDisplayingTheCanonicalName() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source(
				"legacy application properties",
				"simplejavamail.smime.signing.key-password",
				"secret"));

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), SMIME_SIGNING_KEY_PASSWORD.key());

		assertThat(diagnostic.getPropertyName()).isEqualTo(SMIME_SIGNING_KEY_PASSWORD.key());
		assertThat(diagnostic.getSourceName()).isEqualTo("legacy application properties");
		assertThat(diagnostic.isRedacted()).isTrue();
	}

	@Test
	void preservesCanonicalKeyPreferenceOverCompatibilityAliases() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source(
				"low priority canonical source",
				SMIME_SIGNING_KEY_PASSWORD.key(),
				"old secret"));
		environment.getPropertySources().addFirst(source(
				"high priority compatibility source",
				"simplejavamail.smime.signing.key-password",
				"new secret"));

		final SimpleJavaMailConfig config = loadConfig(environment);
		final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), SMIME_SIGNING_KEY_PASSWORD.key());

		assertThat(config.getStringProperty(SMIME_SIGNING_KEY_PASSWORD)).isEqualTo("old secret");
		assertThat(diagnostic.getSourceName()).isEqualTo("low priority canonical source");
	}

	@Test
	void reportsWhereTheSimpleJavaMailPropertyWasDeclaredRatherThanThePlaceholderValue() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("placeholder values", "smtp.target", "smtp.example.test"));
		environment.getPropertySources().addFirst(source("mail settings", SMTP_HOST.key(), "${smtp.target}"));

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), SMTP_HOST.key());

		assertThat(diagnostic.getDisplayValue()).isEqualTo("smtp.example.test");
		assertThat(diagnostic.getSourceName()).isEqualTo("mail settings");
	}

	@Test
	void reportsIndividualSourcesForWildcardProperties() {
		final StandardEnvironment environment = new StandardEnvironment();
		final Map<String, Object> lowPriority = new LinkedHashMap<>();
		lowPriority.put(extra("mail.smtp.timeout"), "1000");
		lowPriority.put(extra("mail.smtp.connectiontimeout"), "500");
		environment.getPropertySources().addFirst(new MapPropertySource("low priority", lowPriority));
		environment.getPropertySources().addFirst(source("profile override", extra("mail.smtp.timeout"), "2000"));

		final ConfigDiagnostics diagnostics = load(environment);

		assertThat(diagnostic(diagnostics, extra("mail.smtp.timeout")).getDisplayValue()).isEqualTo("2000");
		assertThat(diagnostic(diagnostics, extra("mail.smtp.timeout")).getSourceName()).isEqualTo("profile override");
		assertThat(diagnostic(diagnostics, extra("mail.smtp.connectiontimeout")).getSourceName()).isEqualTo("low priority");
	}

	@Test
	void reportsIndividualSourcesForConnectionPoolClusterFields() {
		final StandardEnvironment environment = new StandardEnvironment();
		final String clusterPrefix = "simplejavamail.defaults.connectionpool.clusters.orders.";
		final Map<String, Object> clusterDefaults = new LinkedHashMap<>();
		clusterDefaults.put(clusterPrefix + "clusterkey.uuid", "00000000-0000-0000-0000-000000000301");
		clusterDefaults.put(clusterPrefix + "maxsize", "3");
		environment.getPropertySources().addFirst(new MapPropertySource("cluster defaults", clusterDefaults));
		environment.getPropertySources().addFirst(source("profile override", clusterPrefix + "maxsize", "05"));

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), clusterPrefix + "maxsize");

		assertThat(diagnostic.getDisplayValue()).isEqualTo("5");
		assertThat(diagnostic.getSourceName()).isEqualTo("profile override");
	}

	@Test
	void ignoresBootsSyntheticConfigurationPropertiesAggregator() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("applicationConfig", SMTP_HOST.key(), "smtp.example.test"));
		ConfigurationPropertySources.attach(environment);

		assertThat(environment.getPropertySources().get("configurationProperties")).isNotNull();
		assertThat(diagnostic(load(environment), SMTP_HOST.key()).getSourceName()).isEqualTo("applicationConfig");
	}

	@Test
	void locatesTheUnderlyingSourceWhenBootResolvesARelaxedPropertyName() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("relaxed application config", "simple-java-mail.smtp.host", "smtp.example.test"));
		ConfigurationPropertySources.attach(environment);

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), SMTP_HOST.key());

		assertThat(diagnostic.getDisplayValue()).isEqualTo("smtp.example.test");
		assertThat(diagnostic.getSourceName()).isEqualTo("relaxed application config");
	}

	private static ConfigDiagnostics load(final StandardEnvironment environment) {
		return loadConfig(environment).getDiagnostics();
	}

	private static SimpleJavaMailConfig loadConfig(final StandardEnvironment environment) {
		return ConfigLoader.builder()
				.withSource(new SpringEnvironmentConfigSource(environment))
				.load();
	}

	private static ConfigPropertyDiagnostic diagnostic(final ConfigDiagnostics diagnostics, final String propertyName) {
		return diagnostics.getGroups().stream()
				.flatMap(group -> diagnostics.getProperties(group).stream())
				.filter(property -> property.getPropertyName().equals(propertyName))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No diagnostic found for " + propertyName));
	}

	private static MapPropertySource source(final String sourceName, final String propertyName, final Object value) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(propertyName, value);
		return new MapPropertySource(sourceName, properties);
	}

	private static String extra(final String propertyName) {
		return "simplejavamail.extraproperties." + propertyName;
	}
}
