package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG_OUTPUT;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;

class ConfigLoaderTest {

	@Test
	void snapshotValueSelectionUsesExplicitThenConfiguredThenFallback() {
		final Properties source = new Properties();
		source.setProperty(DEFAULT_SUBJECT.key(), "configured");
		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(source).load();

		assertThat(config.valueOrProperty("explicit", DEFAULT_SUBJECT, "fallback")).isEqualTo("explicit");
		assertThat(config.valueOrProperty(null, DEFAULT_SUBJECT, "fallback")).isEqualTo("configured");
		assertThat(config.valueOrProperty("", DEFAULT_SUBJECT, "fallback")).isEqualTo("configured");
		assertThat(config.valueOrProperty(null, SMTP_HOST, "fallback")).isEqualTo("fallback");
		assertThat(config.hasProperty(DEFAULT_SUBJECT)).isTrue();
		assertThat(config.hasProperty(SMTP_HOST)).isFalse();
	}

	@Test
	void loadsConventionalClasspathPropertiesIntoDeclaredTypes() {
		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.load();

		assertThat(config.getBooleanProperty(JAVAXMAIL_DEBUG)).isTrue();
		assertThat(config.<SessionDebugOutput>getProperty(JAVAXMAIL_DEBUG_OUTPUT)).isEqualTo(SessionDebugOutput.STDERR);
		assertThat(config.<TransportStrategy>getProperty(TRANSPORT_STRATEGY)).isEqualTo(TransportStrategy.SMTPS);
		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("smtp.default.com");
		assertThat(config.getIntegerProperty(SMTP_PORT)).isEqualTo(25);
		assertThat(config.getStringProperty(SMTP_PASSWORD)).isEqualTo("password");
		assertThat(config.getStringProperty(DEFAULT_FROM_ADDRESS)).isEqualTo("from@default.com");
		assertThat(config.<ContentTransferEncoding>getProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)).isEqualTo(ContentTransferEncoding.BINARY);
		assertThat(config.getPropertySource(SMTP_HOST)).isEqualTo("classpath:" + ConfigLoader.DEFAULT_CONFIG_FILENAME);
	}

	@Test
	void laterInputStreamAddsAndOverridesValuesWithoutMutatingEarlierSnapshot() {
		final SimpleJavaMailConfig first = ConfigLoader.builder()
				.withInputStream("base", stream(
						"simplejavamail.smtp.host=base.example.test\n" +
								"simplejavamail.smtp.port=25\n"))
				.load();
		final SimpleJavaMailConfig merged = ConfigLoader.builder()
				.withConfig(first)
				.withInputStream("override", stream(
						"simplejavamail.smtp.host=override.example.test\n" +
								"simplejavamail.proxy.username=proxy-user\n"))
				.load();

		assertThat(first.getStringProperty(SMTP_HOST)).isEqualTo("base.example.test");
		assertThat(merged.getStringProperty(SMTP_HOST)).isEqualTo("override.example.test");
		assertThat(merged.getIntegerProperty(SMTP_PORT)).isEqualTo(25);
		assertThat(merged.getStringProperty(PROXY_USERNAME)).isEqualTo("proxy-user");
		assertThat(merged.getPropertySource(SMTP_HOST)).isEqualTo("override");
	}

	@Test
	void environmentAndSystemSourcesUseTheirDocumentedNamesAndPrecedence() {
		final Map<String, String> environment = new LinkedHashMap<>();
		environment.put("SIMPLEJAVAMAIL_SMTP_HOST", "environment.example.test");
		environment.put("SIMPLEJAVAMAIL_SMTP_PORT", "2525");
		final Properties system = new Properties();
		system.setProperty(SMTP_HOST.key(), "system.example.test");

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withMap("file", singleton(SMTP_HOST.key(), "file.example.test"))
				.withEnvironmentVariables(environment)
				.withSystemProperties(system)
				.load();

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("system.example.test");
		assertThat(config.getIntegerProperty(SMTP_PORT)).isEqualTo(2525);
		assertThat(config.getPropertySource(SMTP_HOST)).isEqualTo("system properties");
		assertThat(config.getPropertySource(SMTP_PORT)).isEqualTo("environment variables");
	}

	@Test
	void wildcardEnvironmentPropertiesKeepLiteralDottedNames() {
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000301");
		final Map<String, String> environment = new LinkedHashMap<>();
		environment.put("simplejavamail.extraproperties.mail.smtp.timeout", "1234");
		environment.put("simplejavamail.defaults.connectionpool.clusters." + clusterKey + ".maxsize", "4");
		environment.put("SIMPLEJAVAMAIL_EXTRAPROPERTIES_IGNORED", "ignored");

		final SimpleJavaMailConfig config = ConfigLoader.builder().withEnvironmentVariables(environment).load();

		assertThat(config.<Map<String, String>>getProperty(EXTRA_PROPERTIES))
				.hasSize(1)
				.containsEntry("mail.smtp.timeout", "1234");
		assertThat(config.<Map<UUID, ConnectionPoolClusterConfig>>getProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)
				.get(clusterKey).getMaxSize()).isEqualTo(4);
	}

	@Test
	void parsesConnectionPoolClustersByAliasAndUuid() {
		final UUID ordersKey = UUID.fromString("00000000-0000-0000-0000-000000000101");
		final UUID bulkKey = UUID.fromString("00000000-0000-0000-0000-000000000202");
		final Properties source = new Properties();
		source.setProperty("simplejavamail.defaults.connectionpool.clusters.orders.clusterkey.uuid", ordersKey.toString());
		source.setProperty("simplejavamail.defaults.connectionpool.clusters.orders.coresize", "0");
		source.setProperty("simplejavamail.defaults.connectionpool.clusters.orders.maxsize", "2");
		source.setProperty("simplejavamail.defaults.connectionpool.clusters.orders.loadbalancing.strategy", "ROUND_ROBIN");
		source.setProperty("simplejavamail.defaults.connectionpool.clusters." + bulkKey + ".maxsize", "8");

		final Map<UUID, ConnectionPoolClusterConfig> clusters = ConfigLoader.builder()
				.withProperties(source)
				.load()
				.getProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);

		assertThat(clusters).containsOnlyKeys(ordersKey, bulkKey);
		assertThat(clusters.get(ordersKey).getCoreSize()).isEqualTo(0);
		assertThat(clusters.get(ordersKey).getMaxSize()).isEqualTo(2);
		assertThat(clusters.get(ordersKey).getLoadBalancingStrategy()).isEqualTo(LoadBalancingStrategy.ROUND_ROBIN);
		assertThat(clusters.get(bulkKey).getMaxSize()).isEqualTo(8);
	}

	@Test
	void ignoresUnknownProcessPropertiesButRejectsUnknownCallerProperties() {
		final Properties process = new Properties();
		process.setProperty("unrelated.jvm.property", "ignored");
		process.setProperty(SMTP_HOST.key(), "smtp.example.test");

		assertThat(ConfigLoader.builder().withSystemProperties(process).load().getStringProperty(SMTP_HOST))
				.isEqualTo("smtp.example.test");
		assertThatThrownBy(() -> ConfigLoader.builder()
				.withMap(singleton("simplejavamail.unknown", "value"))
				.load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("simplejavamail.unknown")
				.hasMessageContaining("map");
	}

	@Test
	void missingClasspathResourceIsEmptyAndMalformedResourceIsReported() {
		assertThat(ConfigLoader.builder().withClasspathResource("missing.properties").load().asMap()).isEmpty();
		assertThatThrownBy(() -> ConfigLoader.builder().withClasspathResource("malformed.properties").load())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("classpath:malformed.properties");
	}

	private static ByteArrayInputStream stream(final String properties) {
		return new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8));
	}

	private static <K, V> Map<K, V> singleton(final K key, final V value) {
		final Map<K, V> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}
}
