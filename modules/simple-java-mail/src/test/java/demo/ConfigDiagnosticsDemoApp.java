package demo;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.util.Properties;

import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_FROM_NAME;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_POOL_SIZE;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_REPLYTO_ADDRESS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SESSION_TIMEOUT_MILLIS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_SUBJECT;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUST_ALL_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUSTED_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_CLIENT_HOSTNAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_LOCAL_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;

/**
 * Demonstrates configuration provenance diagnostics with a realistic mix of sources, overrides, wildcard properties and secrets.
 * <p>
 * Every value in this demo is fake and held in memory. Running it does not build a Mailer or make a network connection. Some numeric and boolean values
 * deliberately use unusual spelling to show that diagnostics display the parsed value, while the line break in one source name and the default subject
 * demonstrates log-safe escaping.
 * </p>
 */
public final class ConfigDiagnosticsDemoApp {

	private static final String EXTRA_PROPERTIES_PREFIX = "simplejavamail.extraproperties.";
	private static final String CLUSTER_PROPERTIES_PREFIX = "simplejavamail.defaults.connectionpool.clusters.";

	private ConfigDiagnosticsDemoApp() {
	}

	public static void main(final String[] arguments) {
		final SimpleJavaMailConfig config = loadExampleConfiguration();
		final SimpleJavaMail simpleJavaMail = SimpleJavaMail.withConfig(config);

		System.out.println(simpleJavaMail.getConfig().getDiagnostics());
	}

	private static SimpleJavaMailConfig loadExampleConfiguration() {
		return ConfigLoader.builder()
				.withProperties("classpath simplejavamail.properties", classpathProperties())
				.withProperties("application.properties", applicationProperties())
				.withProperties("application-production.properties", productionProperties())
				.withProperties("remote config\nstore", remoteConfigProperties())
				.withProperties("systemEnvironment", systemEnvironmentProperties())
				.withProperties("systemProperties", systemProperties())
				.withProperties("commandLineArgs", commandLineProperties())
				.withProperties("empty emergency overrides", blankEmergencyOverrides())
				.load();
	}

	private static Properties classpathProperties() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_CLIENT_HOSTNAME.key(), "mailer.internal.example.org");
		properties.setProperty(DEFAULT_FROM_ADDRESS.key(), "noreply@example.org");
		properties.setProperty(DEFAULT_POOL_SIZE.key(), "04");
		properties.setProperty(DEFAULT_TRUST_ALL_HOSTS.key(), "no");
		properties.setProperty(extraProperty("mail.smtp.timeout"), "3000");
		return properties;
	}

	private static Properties applicationProperties() {
		final Properties properties = new Properties();
		properties.setProperty(TRANSPORT_STRATEGY.key(), "SMTP_TLS");
		properties.setProperty(SMTP_HOST.key(), "smtp.default.example.org");
		properties.setProperty(SMTP_PORT.key(), "0587");
		properties.setProperty(SMTP_USERNAME.key(), "application-user");
		properties.setProperty(OPPORTUNISTIC_TLS.key(), "true");
		properties.setProperty(DEFAULT_FROM_NAME.key(), "Example Notifications");
		properties.setProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY.key(), "FAILURE,DELAY");
		properties.setProperty(clusterProperty("primary", "clusterkey.uuid"), "00000000-0000-0000-0000-000000000715");
		properties.setProperty(clusterProperty("primary", "coresize"), "01");
		properties.setProperty(clusterProperty("primary", "maxsize"), "04");
		properties.setProperty(clusterProperty("primary", "expireafter.millis"), "060000");
		properties.setProperty(clusterProperty("primary", "loadbalancing.strategy"), "ROUND_ROBIN");
		properties.setProperty(DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), "fake-dkim-private-key");
		properties.setProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR.key(), "assets/mail");
		properties.setProperty(extraProperty("mail.smtp.auth"), "true");
		properties.setProperty(extraProperty("mail.smtp.timeout"), "5000");
		return properties;
	}

	private static Properties productionProperties() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_HOST.key(), "smtp.eu-west.example.org");
		properties.setProperty(SMTP_USERNAME.key(), "notifications-prod");
		properties.setProperty(PROXY_HOST.key(), "proxy.production.example.org");
		properties.setProperty(PROXY_PASSWORD.key(), "fake-proxy-password");
		properties.setProperty(DEFAULT_SUBJECT.key(), "Nightly status\r\n(production)");
		properties.setProperty(clusterProperty("primary", "maxsize"), "08");
		properties.setProperty(DKIM_SELECTOR.key(), "mail-2026");
		properties.setProperty(extraProperty("mail.smtp.timeout"), "7000");
		properties.setProperty(extraProperty("mail.smtp.oauth2.access-token"), "fake-oauth2-token");
		return properties;
	}

	private static Properties remoteConfigProperties() {
		final Properties properties = new Properties();
		properties.setProperty(DEFAULT_REPLYTO_ADDRESS.key(), "support@example.org");
		properties.setProperty(DEFAULT_SESSION_TIMEOUT_MILLIS.key(), "045000");
		properties.setProperty(DEFAULT_TRUSTED_HOSTS.key(), "smtp.eu-west.example.org");
		return properties;
	}

	private static Properties systemEnvironmentProperties() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_PASSWORD.key(), "fake-smtp-password");
		properties.setProperty(JAVAXMAIL_DEBUG.key(), "yes");
		properties.setProperty(extraProperty("mail.smtp.connectiontimeout"), "1200");
		return properties;
	}

	private static Properties systemProperties() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_LOCAL_PORT.key(), "02526");
		properties.setProperty(SMIME_SIGNING_KEY_PASSWORD.key(), "fake-smime-key-password");
		properties.setProperty(clusterProperty("primary", "claimtimeout.millis"), "02000");
		return properties;
	}

	private static Properties commandLineProperties() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_PORT.key(), "02525");
		properties.setProperty(clusterProperty("primary", "maxsize"), "12");
		properties.setProperty(extraProperty("mail.smtp.timeout"), "9000");
		return properties;
	}

	private static Properties blankEmergencyOverrides() {
		final Properties properties = new Properties();
		properties.setProperty(SMTP_USERNAME.key(), "   ");
		properties.setProperty(PROXY_HOST.key(), "");
		return properties;
	}

	private static String extraProperty(final String propertyName) {
		return EXTRA_PROPERTIES_PREFIX + propertyName;
	}

	private static String clusterProperty(final String alias, final String propertyName) {
		return CLUSTER_PROPERTIES_PREFIX + alias + '.' + propertyName;
	}
}
