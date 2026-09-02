package org.simplejavamail.springsupport;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;

public abstract class SimpleJavaMailSpringSupportTest {

	@Autowired
	private SimpleJavaMailConfig config;

	@Autowired
	private SimpleJavaMail simpleJavaMail;

	@Autowired
	private Mailer defaultMailer;

	@Autowired
	private ApplicationContext applicationContext;

	protected void performConfigAssertions() {
		assertScalarPropertyResolution();
		assertConnectionPoolClusterResolution();
		assertExtraPropertyResolution();
		assertFactoryAndMailerBeans();
	}

	private void assertScalarPropertyResolution() {
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_CC_NAME)).isEqualTo("CC Default"); // from normal simplejavamail.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_BCC_NAME)).isEqualTo("BCC Spring"); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("7bit"); // from normal simplejavamail.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("binary"); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("base64"); // from normal simplejavamail.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)).isEqualTo("FAILURE,DELAY"); // from normal simplejavamail.properties
		assertThat(getProperty(ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)).isEqualTo("HEADERS_ONLY"); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.JAVAXMAIL_DEBUG_OUTPUT)).isEqualTo("STDERR"); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.SMTP_CLIENT_HOSTNAME)).isEqualTo("mailer.spring.example.com"); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.SMTP_LOCAL_ADDRESS)).isEqualTo("192.0.2.30"); // from Spring application.properties
		assertThat(config.getIntegerProperty(ConfigLoader.Property.SMTP_LOCAL_PORT)).isEqualTo(25259); // from Spring application.properties
		assertThat(getProperty(ConfigLoader.Property.DKIM_SELECTOR)).isNull(); // not set in any properties
	}

	private void assertConnectionPoolClusterResolution() {
		final UUID ordersCluster = UUID.fromString("00000000-0000-0000-0000-000000000301");
		final Map<UUID, ConnectionPoolClusterConfig> clusterConfigs = config.getProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
		assertThat(clusterConfigs).containsKey(ordersCluster);
		assertThat(clusterConfigs.get(ordersCluster).getCoreSize()).isEqualTo(0);
		assertThat(clusterConfigs.get(ordersCluster).getMaxSize()).isEqualTo(3);
		assertThat(clusterConfigs.get(ordersCluster).getLoadBalancingStrategy()).isEqualTo(LoadBalancingStrategy.RANDOM_ACCESS);
	}

	private void assertExtraPropertyResolution() {
		final Map<String, String> loaded = config.getProperty(EXTRA_PROPERTIES);
		final Map<String, String> expected = new HashMap<>();
		expected.put("one", "1"); // from normal simplejavamail.properties
		expected.put("two", "two"); // overridden from Spring application.properties
		expected.put("three", "three"); // from Spring application.properties only
		assertThat(loaded).containsExactlyInAnyOrderEntriesOf(expected);
	}

	private void assertFactoryAndMailerBeans() {
		assertThat(simpleJavaMail.mailerBuilder()).isNotSameAs(simpleJavaMail.mailerBuilder());
		assertThat(simpleJavaMail.getConfig()).isSameAs(config);
		assertThat(defaultMailer).isNotNull();
		assertThat(applicationContext.containsBean("defaultMailerBuilder")).isFalse();
	}

	private @Nullable String getProperty(final ConfigLoader.Property property) {
		final Object value = config.getProperty(property);
		return value != null ? value.toString() : null;
	}
}
