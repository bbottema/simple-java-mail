package org.simplejavamail.api;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJavaMailFactoryTest {

	@Test
	void conventionalFactoryLoadsLazilyOnceAndKeepsOneSnapshotAcrossBuilderVariants() throws Exception {
		final String hostProperty = ConfigLoader.Property.SMTP_HOST.key();
		final String proxyProperty = ConfigLoader.Property.PROXY_HOST.key();
		final String previousHost = System.getProperty(hostProperty);
		final String previousProxy = System.getProperty(proxyProperty);
		final ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
		final String classpathProperties = hostProperty + "=classpath.example.test\n"
				+ proxyProperty + "=classpath-proxy.example.test\n"
				+ ConfigLoader.Property.DEFAULT_SUBJECT.key() + "=Classpath subject\n";

		try (IsolatedSimpleJavaMailRuntime runtime = isolatedRuntime(classpathProperties)) {
			Thread.currentThread().setContextClassLoader(runtime);
			final Class<?> simpleJavaMailType = Class.forName("org.simplejavamail.api.SimpleJavaMail", true, runtime);
			assertThat(runtime.getDefaultResourceLookups()).isZero();

			System.setProperty(hostProperty, "system-before.example.test");
			System.setProperty(proxyProperty, "system-proxy-before.example.test");
			final Object defaults = simpleJavaMailType.getMethod("fromDefaults").invoke(null);

			assertThat(runtime.getDefaultResourceLookups()).isOne();
			assertThat(simpleJavaMailType.getMethod("fromDefaults").invoke(null)).isSameAs(defaults);
			assertThat(readRegularBuilderValue(simpleJavaMailType, defaults, "getHost"))
					.isEqualTo("system-before.example.test");
			assertThat(readRegularBuilderValue(simpleJavaMailType, defaults, "getProxyHost"))
					.isEqualTo("system-proxy-before.example.test");

			final Object emailStarter = simpleJavaMailType.getMethod("emailBuilder").invoke(defaults);
			final Object emailBuilder = emailStarter.getClass().getMethod("startingBlank").invoke(emailStarter);
			final Object completedEmail = emailBuilder.getClass().getMethod("buildEmailCompletedWithDefaultsAndOverrides").invoke(emailBuilder);
			assertThat(completedEmail.getClass().getMethod("getSubject").invoke(completedEmail)).isEqualTo("Classpath subject");

			System.setProperty(hostProperty, "system-after.example.test");
			System.setProperty(proxyProperty, "system-proxy-after.example.test");
			assertThat(readRegularBuilderValue(simpleJavaMailType, defaults, "getHost"))
					.isEqualTo("system-before.example.test");

			final Class<?> sessionType = Class.forName("jakarta.mail.Session", true, runtime);
			final Object session = sessionType.getMethod("getInstance", Properties.class).invoke(null, new Properties());
			final Object sessionBuilder = simpleJavaMailType.getMethod("mailerBuilder", sessionType).invoke(defaults, session);
			assertThat(sessionBuilder.getClass().getMethod("getSession").invoke(sessionBuilder)).isSameAs(session);
			assertThat(sessionBuilder.getClass().getMethod("getProxyHost").invoke(sessionBuilder))
					.isEqualTo("system-proxy-before.example.test");

			final Class<?> configLoaderType = Class.forName("org.simplejavamail.config.ConfigLoader", true, runtime);
			final Object loader = configLoaderType.getMethod("builder").invoke(null);
			configLoaderType.getMethod("withSystemProperties").invoke(loader);
			final Object reloadedConfig = configLoaderType.getMethod("load").invoke(loader);
			final Class<?> configType = Class.forName("org.simplejavamail.config.SimpleJavaMailConfig", true, runtime);
			final Object reloadedFactory = simpleJavaMailType.getMethod("withConfig", configType).invoke(null, reloadedConfig);
			assertThat(simpleJavaMailType.getMethod("getConfig").invoke(reloadedFactory)).isSameAs(reloadedConfig);
			assertThat(readRegularBuilderValue(simpleJavaMailType, reloadedFactory, "getHost"))
					.isEqualTo("system-after.example.test");

			assertThat(Arrays.stream(simpleJavaMailType.getMethods()).map(method -> method.getName()))
					.noneMatch(name -> name.equals("reset") || name.equals("reload") || name.startsWith("setConfig"));
			assertThat(runtime.getDefaultResourceLookups()).isOne();
		} finally {
			Thread.currentThread().setContextClassLoader(previousContextClassLoader);
			restoreSystemProperty(hostProperty, previousHost);
			restoreSystemProperty(proxyProperty, previousProxy);
		}
	}

	@Test
	void factoryExposesTheExactImmutableConfigurationSnapshot() throws Exception {
		final SimpleJavaMailConfig config = config("smtp.example.test", 2525);
		final SimpleJavaMail factory = SimpleJavaMail.withConfig(config);

		assertThat(factory.getConfig()).isSameAs(config);
		assertThat(factory.getConfig().getDiagnostics()).isSameAs(config.getDiagnostics());
		assertThat(SimpleJavaMail.class.getMethod("getConfig").getAnnotation(Cli.ExcludeApi.class)).isNotNull();
	}

	@Test
	void conventionalFactoryStillWorksWhenTheOptionalClasspathFileIsMissing() throws Exception {
		final String hostProperty = ConfigLoader.Property.SMTP_HOST.key();
		final String previousHost = System.getProperty(hostProperty);
		try (IsolatedSimpleJavaMailRuntime runtime = isolatedRuntime(null)) {
			System.setProperty(hostProperty, "system-only.example.test");
			final Class<?> simpleJavaMailType = Class.forName("org.simplejavamail.api.SimpleJavaMail", true, runtime);
			final Object defaults = simpleJavaMailType.getMethod("fromDefaults").invoke(null);

			assertThat(readRegularBuilderValue(simpleJavaMailType, defaults, "getHost"))
					.isEqualTo("system-only.example.test");
			assertThat(runtime.getDefaultResourceLookups()).isOne();
		} finally {
			restoreSystemProperty(hostProperty, previousHost);
		}
	}

	@Test
	void factoriesKeepTheirOwnSnapshotsAndReturnFreshBuilders() {
		final SimpleJavaMail factoryA = SimpleJavaMail.withConfig(config("a.example.test", 2525));
		final SimpleJavaMail factoryB = SimpleJavaMail.withConfig(config("b.example.test", 2526));

		final MailerRegularBuilder<?> builderA1 = factoryA.mailerBuilder();
		final MailerRegularBuilder<?> builderA2 = factoryA.mailerBuilder();
		final MailerRegularBuilder<?> builderB = factoryB.mailerBuilder();

		assertThat(builderA1.getHost()).isEqualTo("a.example.test");
		assertThat(builderA1.getPort()).isEqualTo(2525);
		assertThat(builderB.getHost()).isEqualTo("b.example.test");
		assertThat(builderB.getPort()).isEqualTo(2526);
		assertThat(builderA1.getClusterKey()).isNotEqualTo(builderA2.getClusterKey());

		builderA1.withSMTPServerHost("override.example.test");
		assertThat(builderA2.getHost()).isEqualTo("a.example.test");
		assertThat(factoryA.emailBuilder().startingBlank()).isNotNull();
	}

	@Test
	void suppliesAConfiguredBuilderForACallerSession() {
		final Session session = Session.getInstance(new Properties());
		final MailerFromSessionBuilder<?> builder = SimpleJavaMail.withConfig(config("ignored.example.test", 2525))
				.mailerBuilder(session);

		assertThat(builder.getSession()).isSameAs(session);
	}

	@Test
	void configuredConversionAndCopiesKeepTheFactorySnapshot() throws Exception {
		final SimpleJavaMail strictFactory = SimpleJavaMail.withConfig(embeddedImageConfig(true));
		final SimpleJavaMail passiveFactory = SimpleJavaMail.withConfig(embeddedImageConfig(false));
		final MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
		mimeMessage.setContent("<html><img src=\"missing.png\"></html>", "text/html; charset=UTF-8");
		mimeMessage.saveChanges();

		assertThatThrownBy(() -> strictFactory.converter().mimeMessageToEmailBuilder(mimeMessage).buildEmail())
				.isInstanceOf(MailException.class)
				.hasMessageContaining("missing.png");
		final Email converted = passiveFactory.converter().mimeMessageToEmailBuilder(mimeMessage).buildEmail();
		assertThat(converted.getEmbeddedImages()).isEmpty();

		assertThatThrownBy(() -> strictFactory.emailBuilder().copying(converted).buildEmail())
				.isInstanceOf(MailException.class)
				.hasMessageContaining("missing.png");
		assertThat(passiveFactory.emailBuilder().copying(converted).buildEmail().getEmbeddedImages()).isEmpty();
	}

	@Test
	void clusterSettingsRemainDetachedPerFactoryAndPerMailer() throws Exception {
		final UUID clusterA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
		final UUID clusterB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
		final Map<String, Object> sourceA = clusterConfig(clusterA, 1, 2);
		final Map<String, Object> sourceB = clusterConfig(clusterB, 3, 4);
		final SimpleJavaMail factoryA = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("factory A", sourceA).load());
		final SimpleJavaMail factoryB = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("factory B", sourceB).load());

		sourceA.put("simplejavamail.defaults.connectionpool.clusters." + clusterA + ".maxsize", "99");

		final Mailer mailerA = factoryA.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
		final Mailer mailerB = factoryB.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
		try {
			final Map<UUID, ConnectionPoolClusterConfig> clustersA = mailerA.getOperationalConfig().getConnectionPoolClusterConfigs();
			final Map<UUID, ConnectionPoolClusterConfig> clustersB = mailerB.getOperationalConfig().getConnectionPoolClusterConfigs();

			assertThat(mailerA.getOperationalConfig().getConnectionPoolMaxSize()).isEqualTo(1);
			assertThat(mailerB.getOperationalConfig().getConnectionPoolMaxSize()).isEqualTo(3);
			assertThat(clustersA).containsOnlyKeys(clusterA);
			assertThat(clustersB).containsOnlyKeys(clusterB);
			assertThat(clustersA.get(clusterA).getMaxSize()).isEqualTo(2);
			assertThat(clustersB.get(clusterB).getMaxSize()).isEqualTo(4);
			assertThatThrownBy(() -> clustersA.put(clusterB, clustersB.get(clusterB)))
					.isInstanceOf(UnsupportedOperationException.class);
		} finally {
			mailerB.close();
			mailerA.close();
		}
	}

	private static SimpleJavaMailConfig config(final String host, final int port) {
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), host);
		properties.setProperty(ConfigLoader.Property.SMTP_PORT.key(), Integer.toString(port));
		return ConfigLoader.builder().withProperties(properties).load();
	}

	private static SimpleJavaMailConfig embeddedImageConfig(final boolean enableResolution) {
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR.key(), Boolean.toString(enableResolution));
		properties.setProperty(ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL.key(), "true");
		return ConfigLoader.builder().withProperties(properties).load();
	}

	private static Map<String, Object> clusterConfig(final UUID clusterKey, final int globalMaxSize, final int clusterMaxSize) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE.key(), "0");
		properties.put(ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE.key(), Integer.toString(globalMaxSize));
		properties.put("simplejavamail.defaults.connectionpool.clusters." + clusterKey + ".maxsize", Integer.toString(clusterMaxSize));
		return properties;
	}

	private static Object readRegularBuilderValue(final Class<?> simpleJavaMailType, final Object factory, final String getter) throws Exception {
		final Object builder = simpleJavaMailType.getMethod("mailerBuilder").invoke(factory);
		return builder.getClass().getMethod(getter).invoke(builder);
	}

	private static IsolatedSimpleJavaMailRuntime isolatedRuntime(final String classpathProperties) throws Exception {
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final List<URL> urls = new ArrayList<>();
		for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
			urls.add(new File(entry).toURI().toURL());
		}
		return new IsolatedSimpleJavaMailRuntime(urls.toArray(new URL[0]), classpathProperties);
	}

	private static void restoreSystemProperty(final String key, final String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static final class IsolatedSimpleJavaMailRuntime extends URLClassLoader {
		private final URL defaultResource;
		private final AtomicInteger defaultResourceLookups = new AtomicInteger();

		private IsolatedSimpleJavaMailRuntime(final URL[] urls, final String classpathProperties) throws Exception {
			super(urls, ClassLoader.getSystemClassLoader().getParent());
			defaultResource = classpathProperties == null ? null : inMemoryResource(classpathProperties);
		}

		@Override
		public URL getResource(final String name) {
			if (ConfigLoader.DEFAULT_CONFIG_FILENAME.equals(name)) {
				defaultResourceLookups.incrementAndGet();
				return defaultResource;
			}
			return super.getResource(name);
		}

		private int getDefaultResourceLookups() {
			return defaultResourceLookups.get();
		}

		private static URL inMemoryResource(final String content) throws Exception {
			final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			return new URL(null, "memory:simplejavamail.properties", new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(final URL url) {
					return new URLConnection(url) {
						@Override
						public void connect() {
						}

						@Override
						public InputStream getInputStream() {
							return new ByteArrayInputStream(bytes);
						}
					};
				}
			});
		}
	}
}
