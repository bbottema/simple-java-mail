package org.simplejavamail.springsupport;

import org.simplejavamail.config.ConfigKeys;

import java.time.Duration;
import static testutil.configcontracts.ConfigurationContractCoverage.record;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.config.ConfigDiagnostics;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;

class SpringEnvironmentConfigSourceTest {
	@Test
	void resolvesConnectionConflictPolicyAfterSpringPrecedenceAndPlaceholderResolution() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("application", "simplejavamail.connection.conflictpolicy", "REJECT_CONFLICTS"));
		environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
				"simple-java-mail.connection.conflictpolicy", "${mail.conflict.policy}",
				"mail.conflict.policy", "ALLOW_EXPLICIT_OVERRIDES")));
		ConfigurationPropertySources.attach(environment);
		final SimpleJavaMailConfig config = loadConfig(environment);
		assertThat(config.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
		final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), ConfigKeys.CONNECTION_CONFLICT_POLICY.key());
		assertThat(diagnostic.getDisplayValue()).isEqualTo("ALLOW_EXPLICIT_OVERRIDES");
		assertThat(diagnostic.getSourceName()).isEqualTo("deployment");
		assertThat(diagnostic.getGroup()).isEqualTo(ConfigDiagnosticGroup.SMTP_CONNECTION);
		assertThat(diagnostic.isRedacted()).isFalse();
		record("adapter", "spring.environment");
		record("rule", "spring.source-precedence");
		record("case", "spring.environment-policy");
	}

	@Test
	void resolvesLockedConnectionConflictPolicyWithoutChangingTheOrdinarySchema() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("application", "simplejavamail.connection.conflictpolicy", "ALLOW_EXPLICIT_OVERRIDES"));
		environment.getPropertySources().addFirst(new MapPropertySource("central deployment", Map.of(
				"simplejavamail.locked.connection.conflictpolicy", "${mail.conflict.policy}",
				"mail.conflict.policy", "REJECT_CONFLICTS")));
		ConfigurationPropertySources.attach(environment);
		final SimpleJavaMailConfig config = loadConfig(environment);
		assertThat(config.get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isSameAs(ConnectionConflictPolicy.REJECT_CONFLICTS);
		assertThat(locksOf(config).contains(ConfigLoader.Property.CONNECTION_CONFLICT_POLICY)).isTrue();
		assertThat(diagnostic(config.getDiagnostics(), "simplejavamail.locked.connection.conflictpolicy").getSourceName())
				.isEqualTo("central deployment");
	}

	@Test
	void invalidConnectionConflictPolicyNamesIdentifyTheSpringPropertySource() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("deployment", "simplejavamail.connection.conflictpolicy", "LAST_WINS"));
		assertThatThrownBy(() -> loadConfig(environment)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("simplejavamail.connection.conflictpolicy", "deployment", "connection conflict policy");
	}

	@Test
	void resolvesIndividualSocketTimeoutsThroughRelaxedNamesPlaceholdersAndLocks() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("socket defaults", Map.of(
				"simple-java-mail.defaults.connecttimeoutmillis", "10000",
				"simplejavamail.defaults.readtimeoutmillis", "30000",
				"simplejavamail.defaults.writetimeoutmillis", "${write.timeout}", "write.timeout", "45000",
				"simplejavamail.locked.defaults.readtimeoutmillis", "20000")));
		ConfigurationPropertySources.attach(environment);

		final ConfigDiagnostics diagnostics = load(environment);
		final Map<String, String> expected = Map.of("connect", "10000", "read", "20000", "write", "45000");
		for (final Map.Entry<String, String> timeout : expected.entrySet()) {
			final String prefix = timeout.getKey().equals("read") ? "simplejavamail.locked.defaults." : "simplejavamail.defaults.";
			final ConfigPropertyDiagnostic diagnostic = diagnostic(diagnostics, prefix + timeout.getKey() + "timeoutmillis");
			assertThat(diagnostic.getDisplayValue()).isEqualTo(timeout.getValue());
			assertThat(diagnostic.getSourceName()).isEqualTo("socket defaults");
			assertThat(diagnostic.getGroup()).isEqualTo(ConfigDiagnosticGroup.SMTP_CONNECTION);
			assertThat(diagnostic.isRedacted()).isFalse();
		}
	}

	@Test
	void lockedNamespaceSupportsAliasesPlaceholdersAndUnderlyingSourceNames() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("central deployment", Map.of(
				"simplejavamail.locked.smtp.host", "${relay.host}", "relay.host", "relay.example.org",
				"simplejavamail.locked.javaxmail.debug-out", "STDERR",
				"simplejavamail.locked.extraproperties.mail.smtp.auth", "true")));
		environment.getPropertySources().addFirst(source("application override", SMTP_HOST.key(), "ignored.example.org"));
		ConfigurationPropertySources.attach(environment);
		final SimpleJavaMailConfig config = loadConfig(environment);
		assertThat(config.get(ConfigKeys.SMTP_HOST)).isEqualTo("relay.example.org");
		assertThat(locksOf(config).contains(SMTP_HOST)).isTrue();
		assertThat(config.getDiagnostics().toString()).contains("simplejavamail.locked.smtp.host = relay.example.org (source: central deployment)",
				"simplejavamail.locked.javaxmail.debug.out = STDERR", "simplejavamail.locked.extraproperties.mail.smtp.auth = true");
		record("rule", "spring.lock-authority");
		record("case", "spring.locked-setting");
	}

	@Test
	void resolvesCreationAgeExpirationThroughSpringWithoutLosingClusterSources() {
		final StandardEnvironment environment = new StandardEnvironment();
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000301");
		final String clusterPrefix = "simplejavamail.defaults.connectionpool.clusters.orders.";
		final String clusterAgeProperty = clusterPrefix + "expireaftercreation.millis";
		environment.getPropertySources().addFirst(new MapPropertySource("pool defaults", Map.of(
				DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS.key(), "0450000",
				clusterPrefix + "clusterkey.uuid", clusterKey.toString(),
				clusterAgeProperty, "600000")));
		environment.getPropertySources().addFirst(source("deployment values", "pool.creationAge", "0900000"));
		environment.getPropertySources().addFirst(source("profile override", clusterAgeProperty, "${pool.creationAge}"));
		ConfigurationPropertySources.attach(environment);

		final SimpleJavaMailConfig config = loadConfig(environment);
		final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);

		assertThat(config.get(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION)).isEqualTo(Duration.ofMillis(450000));
		assertThat(clusters.get(clusterKey).getExpireAfterCreation()).isEqualTo(Duration.ofMillis(900000));
		assertThat(diagnostic(config.getDiagnostics(), DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS.key()).getSourceName())
				.isEqualTo("pool defaults");
		final ConfigPropertyDiagnostic clusterDiagnostic = diagnostic(config.getDiagnostics(), clusterAgeProperty);
		assertThat(clusterDiagnostic.getDisplayValue()).isEqualTo("900000");
		assertThat(clusterDiagnostic.getSourceName()).isEqualTo("profile override");
		assertThat(clusterDiagnostic.getGroup()).isEqualTo(ConfigDiagnosticGroup.EXECUTION_AND_POOLING);
		assertThat(clusterDiagnostic.isRedacted()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1})
	void preservesCreationAgeValuesAndSourcesForValidationByTheBatchModule(final int value) {
		final StandardEnvironment environment = new StandardEnvironment();
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000301");
		final String globalProperty = DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS.key();
		final String clusterProperty = "simplejavamail.defaults.connectionpool.clusters." + clusterKey + ".expireaftercreation.millis";
		environment.getPropertySources().addFirst(new MapPropertySource("pool defaults", Map.of(
				globalProperty, "450000", clusterProperty, "600000")));
		environment.getPropertySources().addFirst(new MapPropertySource("deployment values", Map.of(
				globalProperty, Integer.toString(value), clusterProperty, Integer.toString(value))));
		ConfigurationPropertySources.attach(environment);

		final SimpleJavaMailConfig config = loadConfig(environment);
		final Map<UUID, ConnectionPoolClusterConfig> clusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
		assertThat(config.get(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION)).isEqualTo(Duration.ofMillis(value));
		assertThat(clusters.get(clusterKey).getExpireAfterCreation()).isEqualTo(Duration.ofMillis(value));
		for (String property : new String[] {globalProperty, clusterProperty}) {
			final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), property);
			assertThat(diagnostic.getDisplayValue()).isEqualTo(Integer.toString(value));
			assertThat(diagnostic.getSourceName()).isEqualTo("deployment values");
			assertThat(diagnostic.getGroup()).isEqualTo(ConfigDiagnosticGroup.EXECUTION_AND_POOLING);
			assertThat(diagnostic.isRedacted()).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"false", "2147483648"})
	void rejectsMalformedOrOverflowingCreationAgeValuesWithTheirSpringSources(final String value) {
		final String globalProperty = DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS.key();
		final String clusterProperty = "simplejavamail.defaults.connectionpool.clusters.orders.expireaftercreation.millis";
		for (String property : new String[] {globalProperty, clusterProperty}) {
			final StandardEnvironment environment = new StandardEnvironment();
			environment.getPropertySources().addFirst(source("invalid deployment", property, value));
			assertThatThrownBy(() -> loadConfig(environment))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(property)
					.hasMessageContaining("invalid deployment")
					.hasMessageContaining("integer");
		}
	}

	@Test
	void resolvesLegacyContentPermissionWithSpringPrecedenceAndVisibleTypedDiagnostics() {
		final StandardEnvironment environment = new StandardEnvironment();
		final ConfigLoader.Property property = ConfigLoader.Property.SMTP_LEGACY_CONTENT_SUPPORT;
		environment.getPropertySources().addFirst(source("defaults", property.key(), "true"));
		environment.getPropertySources().addFirst(source("production", property.key(), "false"));
		final SimpleJavaMailConfig config = loadConfig(environment);
		assertThat(config.get(ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT)).isFalse();
		final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), property.key());
		assertThat(diagnostic.getDisplayValue()).isEqualTo("false");
		assertThat(diagnostic.getSourceName()).isEqualTo("production");
		assertThat(diagnostic.isRedacted()).isFalse();
		assertThat(diagnostic.getGroup()).isEqualTo(ConfigDiagnosticGroup.SMTP_CONNECTION);
	}

	@Test
	void resolvesQueueSettingsWithSpringPrecedenceAndTypedDiagnostics() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("queue defaults", Map.of(
				"simplejavamail.defaults.async.queue.capacity", "8",
				"simplejavamail.defaults.async.queue.overflowpolicy", "WAIT_FOR_CAPACITY",
				"simplejavamail.defaults.async.queue.waittimeoutmillis", "250")));
		environment.getPropertySources().addFirst(source("production queue", "simplejavamail.defaults.async.queue.capacity", "03"));
		final SimpleJavaMailConfig config = loadConfig(environment);
		final AsyncQueueOverflowPolicy policy = config.get(ConfigKeys.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY);
		assertThat(policy).isEqualTo(AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY);
		assertThat(diagnostic(config.getDiagnostics(), ConfigLoader.Property.DEFAULT_ASYNC_QUEUE_CAPACITY.key()).getDisplayValue()).isEqualTo("3");
		assertThat(diagnostic(config.getDiagnostics(), ConfigLoader.Property.DEFAULT_ASYNC_QUEUE_CAPACITY.key()).getSourceName()).isEqualTo("production queue");
	}


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

		final SimpleJavaMailConfig config = loadConfig(environment);
		final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), SMIME_SIGNING_KEY_PASSWORD.key());

		assertThat(config.get(ConfigKeys.SMIME_SIGNING_KEY_PASSWORD)).isEqualTo("secret");
		assertThat(diagnostic.getPropertyName()).isEqualTo(SMIME_SIGNING_KEY_PASSWORD.key());
		assertThat(diagnostic.getSourceName()).isEqualTo("legacy application properties");
		assertThat(diagnostic.isRedacted()).isTrue();
		record("case", "spring.compatibility-alias");
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

		assertThat(config.get(ConfigKeys.SMIME_SIGNING_KEY_PASSWORD)).isEqualTo("old secret");
		assertThat(diagnostic.getSourceName()).isEqualTo("low priority canonical source");
		record("rule", "spring.canonical-alias");
		record("case", "spring.canonical-alias-preference");
	}

	@Test
	void reportsWhereTheSimpleJavaMailPropertyWasDeclaredRatherThanThePlaceholderValue() {
		final StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(source("placeholder values", "smtp.target", "smtp.example.test"));
		environment.getPropertySources().addFirst(source("mail settings", SMTP_HOST.key(), "${smtp.target}"));

		final ConfigPropertyDiagnostic diagnostic = diagnostic(load(environment), SMTP_HOST.key());

		assertThat(diagnostic.getDisplayValue()).isEqualTo("smtp.example.test");
		assertThat(diagnostic.getSourceName()).isEqualTo("mail settings");
		record("rule", "spring.placeholder-provenance");
		record("case", "spring.placeholder-source");
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
		record("rule", "spring.wildcard-provenance");
		record("case", "spring.wildcard-source");
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
		record("rule", "spring.relaxed-name");
		record("case", "spring.relaxed-source");
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
