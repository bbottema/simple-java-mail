package org.simplejavamail.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigDiagnosticGroup.DIAGNOSTICS_AND_VALIDATION;
import static org.simplejavamail.config.ConfigDiagnosticGroup.EMAIL_DEFAULTS;
import static org.simplejavamail.config.ConfigDiagnosticGroup.JAKARTA_MAIL_PROPERTIES;
import static org.simplejavamail.config.ConfigDiagnosticGroup.SMTP_CONNECTION;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;

class ConfigDiagnosticsTest {

	@Test
	void groupsAndFormatsResolvedValuesInStableOrder() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_PORT.key(), "02525");
		properties.setProperty(SMTP_HOST.key(), "smtp.example.test");
		properties.setProperty(JAVAXMAIL_DEBUG.key(), "yes");
		properties.setProperty(DEFAULT_CONTENT_TRANSFER_ENCODING.key(), "quoted-printable");
		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties("application properties", properties).load();

		final ConfigDiagnostics diagnostics = config.getDiagnostics();

		assertThat(diagnostics.getGroups()).containsExactly(SMTP_CONNECTION, DIAGNOSTICS_AND_VALIDATION, EMAIL_DEFAULTS);
		assertThat(diagnostics.getProperties(SMTP_CONNECTION))
				.extracting(ConfigPropertyDiagnostic::getPropertyName)
				.containsExactly(SMTP_HOST.key(), SMTP_PORT.key());
		assertThat(diagnostic(diagnostics, SMTP_PORT.key()).getDisplayValue()).isEqualTo("2525");
		assertThat(diagnostic(diagnostics, JAVAXMAIL_DEBUG.key()).getDisplayValue()).isEqualTo("true");
		assertThat(diagnostic(diagnostics, DEFAULT_CONTENT_TRANSFER_ENCODING.key()).getDisplayValue()).isEqualTo("quoted-printable");
		assertThat(diagnostic(diagnostics, SMTP_HOST.key()).getSourceName()).isEqualTo("application properties");
		assertThat(diagnostics.toString())
				.contains("SMTP connection:")
				.contains("  simplejavamail.smtp.host = smtp.example.test (source: application properties)");
		assertThat(config.getDiagnostics()).isSameAs(diagnostics);
	}

	@Test
	void keepsTheEarlierWinnerWhenAHigherPriorityValueIsBlank() {
		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withMap("base configuration", singleton(SMTP_HOST.key(), "smtp.example.test"))
				.withMap("blank override", singleton(SMTP_HOST.key(), "  "))
				.load();

		final ConfigPropertyDiagnostic diagnostic = diagnostic(config.getDiagnostics(), SMTP_HOST.key());

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("smtp.example.test");
		assertThat(diagnostic.getSourceName()).isEqualTo("base configuration");
	}

	@Test
	void omitsAbsentGroupsAndKeepsEveryCollectionImmutable() {
		final ConfigDiagnostics diagnostics = ConfigLoader.builder()
				.withMap(singleton(SMTP_HOST.key(), "smtp.example.test"))
				.load()
				.getDiagnostics();

		assertThat(diagnostics.getGroups()).containsExactly(SMTP_CONNECTION);
		assertThat(diagnostics.getProperties(JAKARTA_MAIL_PROPERTIES)).isEmpty();
		assertThatThrownBy(() -> diagnostics.getGroups().add(JAKARTA_MAIL_PROPERTIES))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> diagnostics.getProperties(SMTP_CONNECTION).clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> diagnostics.getProperties(JAKARTA_MAIL_PROPERTIES)
				.add(diagnostic(diagnostics, SMTP_HOST.key())))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void redactsEveryKnownSecretFromStructuredAndLegacyTextOutput() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_PASSWORD.key(), "smtp-secret");
		properties.setProperty(PROXY_PASSWORD.key(), "proxy-secret");
		properties.setProperty(SMIME_SIGNING_KEYSTORE.key(), "keystore-secret");
		properties.setProperty(SMIME_SIGNING_KEYSTORE_PASSWORD.key(), "keystore-password-secret");
		properties.setProperty(SMIME_SIGNING_KEY_PASSWORD.key(), "key-password-secret");
		properties.setProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), "dkim-secret");
		properties.setProperty(SMIME_ENCRYPTION_CERTIFICATE.key(), "certificate-secret");
		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(properties).load();

		final List<ConfigPropertyDiagnostic> secretDiagnostics = allProperties(config.getDiagnostics());
		assertThat(secretDiagnostics)
				.extracting(ConfigPropertyDiagnostic::getPropertyName)
				.containsExactlyInAnyOrder(
						SMTP_PASSWORD.key(),
						PROXY_PASSWORD.key(),
						SMIME_SIGNING_KEYSTORE.key(),
						SMIME_SIGNING_KEYSTORE_PASSWORD.key(),
						SMIME_SIGNING_KEY_PASSWORD.key(),
						DKIM_PRIVATE_KEY_FILE_OR_DATA.key(),
						SMIME_ENCRYPTION_CERTIFICATE.key());
		for (ConfigPropertyDiagnostic diagnostic : secretDiagnostics) {
			assertThat(diagnostic.isRedacted()).isTrue();
			assertThat(diagnostic.getDisplayValue()).isEqualTo(ConfigPropertyDiagnostic.REDACTED_VALUE);
		}
		assertThat(config.getDiagnostics().toString())
				.doesNotContain("smtp-secret", "proxy-secret", "keystore-secret", "dkim-secret", "certificate-secret");
		assertThat(config.toString())
				.doesNotContain("smtp-secret", "proxy-secret", "keystore-secret", "dkim-secret", "certificate-secret");
	}

	@Test
	void redactsSuspiciousExtraPropertiesButKeepsOrdinaryJakartaMailSettingsVisible() {
		final Properties properties = new Properties();
		properties.setProperty(extra("mail.smtp.timeout"), "1000");
		properties.setProperty(extra("mail.smtp.auth"), "true");
		properties.setProperty(extra("mail.smtp.password"), "password-value");
		properties.setProperty(extra("mail.smtp.oauth2.access-token"), "token-value");
		properties.setProperty(extra("mail.smtp.clientSecret"), "secret-value");
		properties.setProperty(extra("mail.smtp.private_key"), "private-key-value");
		properties.setProperty(extra("mail.smtp.authorizationId"), "authorization-value");
		properties.setProperty(extra("mail.smtp.serverCertificate"), "certificate-value");
		final ConfigDiagnostics diagnostics = ConfigLoader.builder().withProperties(properties).load().getDiagnostics();

		assertThat(diagnostic(diagnostics, extra("mail.smtp.timeout")).getDisplayValue()).isEqualTo("1000");
		assertThat(diagnostic(diagnostics, extra("mail.smtp.auth")).getDisplayValue()).isEqualTo("true");
		assertThat(diagnostic(diagnostics, extra("mail.smtp.timeout")).isRedacted()).isFalse();
		assertThat(diagnostic(diagnostics, extra("mail.smtp.auth")).isRedacted()).isFalse();
		assertThat(diagnostics.getProperties(JAKARTA_MAIL_PROPERTIES))
				.filteredOn(ConfigPropertyDiagnostic::isRedacted)
				.extracting(ConfigPropertyDiagnostic::getDisplayValue)
				.containsOnly(ConfigPropertyDiagnostic.REDACTED_VALUE);
		assertThat(diagnostics.toString())
				.doesNotContain("password-value", "token-value", "secret-value", "private-key-value", "authorization-value", "certificate-value");
	}

	@Test
	void escapesControlCharactersInDiagnosticValuesAndSourceNames() {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_SUBJECT.key(), "first\r\nsecond\t\u0001");
		final ConfigPropertyDiagnostic diagnostic = ConfigLoader.builder()
				.withProperties("source\nname", properties)
				.load()
				.getDiagnostics()
				.getProperties(EMAIL_DEFAULTS)
				.get(0);

		assertThat(diagnostic.getDisplayValue()).isEqualTo("first\\r\\nsecond\\t\\u0001");
		assertThat(diagnostic.getSourceName()).isEqualTo("source\\nname");
		assertThat(diagnostic.toString()).doesNotContain("\r", "\n", "\t", "\u0001");
	}

	@Test
	void retainsIndividualSourcesAndTypedValuesForWildcardProperties() {
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000715");
		final Map<String, Object> lowPriority = new LinkedHashMap<>();
		lowPriority.put(extra("mail.smtp.timeout"), "1000");
		lowPriority.put(extra("mail.smtp.connectiontimeout"), "500");
		lowPriority.put(cluster("orders", "clusterkey.uuid"), clusterKey.toString());
		lowPriority.put(cluster("orders", "coresize"), "1");
		final Map<String, Object> highPriority = new LinkedHashMap<>();
		highPriority.put(extra("mail.smtp.timeout"), "2000");
		highPriority.put(cluster("orders", "maxsize"), "3");

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withMap("low priority", lowPriority)
				.withMap("high priority", highPriority)
				.load();
		final ConfigDiagnostics diagnostics = config.getDiagnostics();

		assertThat(diagnostic(diagnostics, extra("mail.smtp.timeout")).getSourceName()).isEqualTo("high priority");
		assertThat(diagnostic(diagnostics, extra("mail.smtp.connectiontimeout")).getSourceName()).isEqualTo("low priority");
		assertThat(diagnostic(diagnostics, cluster("orders", "clusterkey.uuid")).getDisplayValue()).isEqualTo(clusterKey.toString());
		assertThat(diagnostic(diagnostics, cluster("orders", "coresize")).getDisplayValue()).isEqualTo("1");
		assertThat(diagnostic(diagnostics, cluster("orders", "maxsize")).getDisplayValue()).isEqualTo("3");
		assertThat(diagnostic(diagnostics, cluster("orders", "coresize")).getSourceName()).isEqualTo("low priority");
		assertThat(diagnostic(diagnostics, cluster("orders", "maxsize")).getSourceName()).isEqualTo("high priority");
		assertThat(config.getPropertySource(EXTRA_PROPERTIES)).isEqualTo("high priority");
		assertThat(config.getPropertySource(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).isEqualTo("high priority");
	}

	@Test
	void keepsCompositeSourceNamesForWildcardAggregates() {
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000715");
		final String timeoutProperty = extra("mail.smtp.timeout");
		final String clusterKeyProperty = cluster("orders", "clusterkey.uuid");
		final String clusterSizeProperty = cluster("orders", "maxsize");
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(timeoutProperty, "1000");
		properties.put(clusterKeyProperty, clusterKey.toString());
		properties.put(clusterSizeProperty, "3");
		final ConfigSource source = new ConfigSource() {
			@Override
			public String getName() {
				return "composite configuration";
			}

			@Override
			public Map<String, ?> getProperties() {
				return properties;
			}

			@Override
			public String getPropertySourceName(final String propertyName) {
				return propertyName.equals(timeoutProperty) ? "application properties" : "pool profile";
			}
		};

		final SimpleJavaMailConfig config = ConfigLoader.builder().withSource(source).load();

		assertThat(diagnostic(config.getDiagnostics(), timeoutProperty).getSourceName()).isEqualTo("application properties");
		assertThat(diagnostic(config.getDiagnostics(), clusterSizeProperty).getSourceName()).isEqualTo("pool profile");
		assertThat(config.getPropertySource(EXTRA_PROPERTIES)).isEqualTo("composite configuration");
		assertThat(config.getPropertySource(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).isEqualTo("composite configuration");
	}

	@Test
	void preservesConnectionPoolAliasesWhenReusingAConfigSnapshot() {
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000715");
		final String clusterKeyProperty = cluster("primary", "clusterkey.uuid");
		final String clusterSizeProperty = cluster("primary", "maxsize");
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(clusterKeyProperty, clusterKey.toString());
		properties.put(clusterSizeProperty, "3");
		final SimpleJavaMailConfig original = ConfigLoader.builder().withMap(properties).load();

		final SimpleJavaMailConfig reused = ConfigLoader.builder().withConfig(original).load();
		final List<String> clusterPropertyNames = allProperties(reused.getDiagnostics()).stream()
				.map(ConfigPropertyDiagnostic::getPropertyName)
				.filter(propertyName -> propertyName.startsWith(cluster("primary", "")))
				.collect(Collectors.toList());

		assertThat(clusterPropertyNames).containsExactly(clusterKeyProperty, clusterSizeProperty);
		assertThat(diagnostic(reused.getDiagnostics(), clusterSizeProperty).getDisplayValue()).isEqualTo("3");
		assertThat(reused.<Map<UUID, ?>>getProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).containsKey(clusterKey);
	}

	@Test
	void customConfigSourcesUseTheirGeneralNameByDefault() {
		final ConfigSource source = new ConfigSource() {
			@Override
			public String getName() {
				return "custom source";
			}

			@Override
			public Map<String, ?> getProperties() {
				return singleton(SMTP_HOST.key(), "smtp.example.test");
			}
		};

		final ConfigPropertyDiagnostic diagnostic = ConfigLoader.builder()
				.withSource(source)
				.load()
				.getDiagnostics()
				.getProperties(SMTP_CONNECTION)
				.get(0);

		assertThat(diagnostic.getSourceName()).isEqualTo("custom source");
	}

	@Test
	void everyRecognizedPropertyHasADiagnosticGroup() {
		for (ConfigLoader.Property property : ConfigLoader.Property.values()) {
			assertThat(PropertySchema.diagnosticGroup(property)).isNotNull();
		}
	}

	private static ConfigPropertyDiagnostic diagnostic(final ConfigDiagnostics diagnostics, final String propertyName) {
		return allProperties(diagnostics).stream()
				.filter(property -> property.getPropertyName().equals(propertyName))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No diagnostic found for " + propertyName));
	}

	private static List<ConfigPropertyDiagnostic> allProperties(final ConfigDiagnostics diagnostics) {
		return diagnostics.getGroups().stream()
				.flatMap(group -> diagnostics.getProperties(group).stream())
				.collect(Collectors.toList());
	}

	private static String extra(final String propertyName) {
		return "simplejavamail.extraproperties." + propertyName;
	}

	private static String cluster(final String alias, final String propertyName) {
		return "simplejavamail.defaults.connectionpool.clusters." + alias + "." + propertyName;
	}

	private static <K, V> Map<K, V> singleton(final K key, final V value) {
		final Map<K, V> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}
}
