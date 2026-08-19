package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH;
import static org.simplejavamail.config.ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONTENT_TRANSFER_ENCODING;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_HEADER_CANONICALIZATION;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG;
import static org.simplejavamail.config.ConfigLoader.Property.JAVAXMAIL_DEBUG_OUTPUT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;

class ConfigLoaderInstanceTest {

	@Test
	void resolvesEachPropertyByItsDeclaredTypeAndLaterSourcesWin() {
		final Properties lowPriority = new Properties();
		lowPriority.setProperty("simplejavamail.smtp.host", "low.example.test");
		lowPriority.setProperty("simplejavamail.smtp.port", "2525");
		lowPriority.setProperty("simplejavamail.smtp.password", "123456");
		lowPriority.setProperty("simplejavamail.defaults.subject", "true");
		lowPriority.setProperty("simplejavamail.javaxmail.debug", "yes");
		lowPriority.setProperty("simplejavamail.transportstrategy", "SMTP_TLS");

		final Properties highPriority = new Properties();
		highPriority.setProperty("simplejavamail.smtp.host", "high.example.test");

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withProperties("low", lowPriority)
				.withProperties("high", highPriority)
				.load();

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("high.example.test");
		assertThat(config.getIntegerProperty(SMTP_PORT)).isEqualTo(2525);
		assertThat(config.getStringProperty(SMTP_PASSWORD)).isEqualTo("123456");
		assertThat(config.getStringProperty(ConfigLoader.Property.DEFAULT_SUBJECT)).isEqualTo("true");
		assertThat(config.getBooleanProperty(JAVAXMAIL_DEBUG)).isTrue();
		assertThat(config.<TransportStrategy>getProperty(TRANSPORT_STRATEGY)).isEqualTo(TransportStrategy.SMTP_TLS);
	}

	@Test
	void blankHigherPriorityValueDoesNotHideLowerPriorityValue() {
		final Properties lowPriority = new Properties();
		lowPriority.setProperty(SMTP_HOST.key(), "smtp.example.test");
		final Properties highPriority = new Properties();
		highPriority.setProperty(SMTP_HOST.key(), "  ");

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withProperties("low", lowPriority)
				.withProperties("high", highPriority)
				.load();

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("smtp.example.test");
	}

	@Test
	void snapshotsAreDetachedDeeplyImmutableAndSecretSafe() {
		final Properties source = new Properties();
		source.setProperty(SMTP_HOST.key(), "smtp.example.test");
		source.setProperty(SMTP_PASSWORD.key(), "do-not-print-me");
		source.setProperty("simplejavamail.extraproperties.mail.smtp.timeout", "1234");

		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(source).load();
		source.setProperty(SMTP_HOST.key(), "mutated.example.test");

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("smtp.example.test");
		assertThat(config.toString()).doesNotContain("do-not-print-me");
		assertThat(config.toString()).contains("<redacted>");
		assertThatThrownBy(() -> config.asMap().put(SMTP_HOST, "other"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> config.<Map<String, String>>getProperty(EXTRA_PROPERTIES).put("x", "y"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void correctsTheCrossedEmbeddedImageKeys() {
		final Properties source = new Properties();
		source.setProperty("simplejavamail.embeddedimages.dynamicresolution.outside.base.url", "true");
		source.setProperty("simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath", "false");

		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(source).load();

		assertThat(config.getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL)).isTrue();
		assertThat(config.getBooleanProperty(EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH)).isFalse();
	}

	@Test
	void customSourcesAreOrderedAndNamed() {
		final Map<String, Object> firstValues = new LinkedHashMap<>();
		firstValues.put(SMTP_HOST.key(), "first.example.test");
		final ConfigSource first = source("first", firstValues);
		final ConfigSource second = source("second", Collections.<String, Object>singletonMap(SMTP_HOST.key(), "second.example.test"));

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withSource(first)
				.withSource(second)
				.load();

		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("second.example.test");
		assertThat(config.getPropertySource(SMTP_HOST)).isEqualTo("second");
	}

	@Test
	void independentSnapshotsDoNotShareState() {
		final Properties sourceA = new Properties();
		sourceA.setProperty(SMTP_HOST.key(), "a.example.test");
		final Properties sourceB = new Properties();
		sourceB.setProperty(SMTP_HOST.key(), "b.example.test");

		final SimpleJavaMailConfig configA = ConfigLoader.builder().withProperties(sourceA).load();
		final SimpleJavaMailConfig configB = ConfigLoader.builder().withProperties(sourceB).load();

		assertThat(configA.getStringProperty(SMTP_HOST)).isEqualTo("a.example.test");
		assertThat(configB.getStringProperty(SMTP_HOST)).isEqualTo("b.example.test");
	}

	@Test
	void parsesOnlyTheWinningValue() {
		final Properties invalidLowPriority = new Properties();
		invalidLowPriority.setProperty(SMTP_PORT.key(), "not-an-integer");
		final Properties validHighPriority = new Properties();
		validHighPriority.setProperty(SMTP_PORT.key(), "2525");

		assertThat(ConfigLoader.builder()
				.withProperties("invalid but overridden", invalidLowPriority)
				.withProperties("winner", validHighPriority)
				.load()
				.getIntegerProperty(SMTP_PORT)).isEqualTo(2525);

		assertThatThrownBy(() -> ConfigLoader.builder()
				.withProperties("valid but overridden", validHighPriority)
				.withProperties("invalid winner", invalidLowPriority)
				.load())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(SMTP_PORT.key())
				.hasMessageContaining("invalid winner");
	}

	@Test
	void samplesEachSourceOncePerLoadAndAllowsASecondIndependentLoad() {
		final AtomicInteger samples = new AtomicInteger();
		final Map<String, Object> mutableValues = new LinkedHashMap<>();
		mutableValues.put(SMTP_HOST.key(), "first.example.test");
		final ConfigLoader loader = ConfigLoader.builder().withSource(new ConfigSource() {
			@Override
			public String getName() {
				return "mutable test source";
			}

			@Override
			public Map<String, ?> getProperties() {
				samples.incrementAndGet();
				return mutableValues;
			}
		});

		final SimpleJavaMailConfig first = loader.load();
		mutableValues.put(SMTP_HOST.key(), "second.example.test");
		final SimpleJavaMailConfig second = loader.load();

		assertThat(samples).hasValue(2);
		assertThat(first.getStringProperty(SMTP_HOST)).isEqualTo("first.example.test");
		assertThat(second.getStringProperty(SMTP_HOST)).isEqualTo("second.example.test");
	}

	@Test
	void inputStreamsAreConsumedAndClosedImmediately() {
		final CloseTrackingInputStream valid = new CloseTrackingInputStream(
				(SMTP_HOST.key() + "=smtp.example.test\n").getBytes(StandardCharsets.UTF_8));
		final ConfigLoader loader = ConfigLoader.builder().withInputStream("tracked", valid);

		assertThat(valid.closed).isTrue();
		assertThat(loader.load().getStringProperty(SMTP_HOST)).isEqualTo("smtp.example.test");

		final BrokenInputStream broken = new BrokenInputStream();
		assertThatThrownBy(() -> ConfigLoader.builder().withInputStream("broken", broken))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("broken");
		assertThat(broken.closed).isTrue();
	}

	@Test
	void explicitClassLoaderOwnsClasspathLookup() {
		final AtomicInteger lookups = new AtomicInteger();
		final ClassLoader classLoader = new ClassLoader() {
			@Override
			public InputStream getResourceAsStream(final String name) {
				lookups.incrementAndGet();
				return "custom.properties".equals(name)
						? new ByteArrayInputStream((SMTP_HOST.key() + "=custom.example.test\n").getBytes(StandardCharsets.UTF_8))
						: null;
			}
		};

		final SimpleJavaMailConfig config = ConfigLoader.builder()
				.withClasspathResource("custom.properties", classLoader)
				.load();

		assertThat(lookups).hasValue(1);
		assertThat(config.getStringProperty(SMTP_HOST)).isEqualTo("custom.example.test");
	}

	@Test
	void parsesAllNonStringSchemaFamilies() {
		final Properties source = new Properties();
		source.setProperty(JAVAXMAIL_DEBUG.key(), "yes");
		source.setProperty(JAVAXMAIL_DEBUG_OUTPUT.key(), "SLF4J");
		source.setProperty(TRANSPORT_STRATEGY.key(), "SMTP_OAUTH2");
		source.setProperty(DEFAULT_CONTENT_TRANSFER_ENCODING.key(), "quoted-printable");
		source.setProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION.key(), "HEADERS_ONLY");
		source.setProperty(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY.key(), "ROUND_ROBIN");
		source.setProperty(DKIM_SIGNING_HEADER_CANONICALIZATION.key(), "RELAXED");

		final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties(source).load();

		assertThat(config.getBooleanProperty(JAVAXMAIL_DEBUG)).isTrue();
		assertThat(config.<SessionDebugOutput>getProperty(JAVAXMAIL_DEBUG_OUTPUT)).isEqualTo(SessionDebugOutput.SLF4J);
		assertThat(config.<TransportStrategy>getProperty(TRANSPORT_STRATEGY)).isEqualTo(TransportStrategy.SMTP_OAUTH2);
		assertThat(config.<ContentTransferEncoding>getProperty(DEFAULT_CONTENT_TRANSFER_ENCODING)).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
		assertThat(config.<DeliveryStatusNotification.ReturnOption>getProperty(DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION))
				.isEqualTo(DeliveryStatusNotification.ReturnOption.HEADERS_ONLY);
		assertThat(config.<LoadBalancingStrategy>getProperty(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)).isEqualTo(LoadBalancingStrategy.ROUND_ROBIN);
		assertThat(config.<DkimConfig.Canonicalization>getProperty(DKIM_SIGNING_HEADER_CANONICALIZATION)).isEqualTo(DkimConfig.Canonicalization.RELAXED);
	}

	@Test
	void validationErrorsIdentifyTheSourceWithoutLeakingSecretValues() {
		final Map<String, Object> source = new LinkedHashMap<>();
		source.put(SMTP_PASSWORD.key(), new SecretValue("never-print-this"));

		assertThatThrownBy(() -> ConfigLoader.builder().withMap("secret source", source).load())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(SMTP_PASSWORD.key())
				.hasMessageContaining("secret source")
				.hasMessageNotContaining("never-print-this");
	}

	@Test
	void existingSnapshotsRemainValidSourcesIncludingWildcardValues() {
		final UUID clusterKey = UUID.fromString("00000000-0000-0000-0000-000000000404");
		final Map<String, Object> baseValues = new LinkedHashMap<>();
		baseValues.put("simplejavamail.extraproperties.mail.smtp.timeout", "1000");
		baseValues.put("simplejavamail.defaults.connectionpool.clusters." + clusterKey + ".maxsize", "3");
		final SimpleJavaMailConfig base = ConfigLoader.builder().withMap(baseValues).load();
		final Map<String, Object> overrides = new LinkedHashMap<>();
		overrides.put("simplejavamail.extraproperties.mail.smtp.timeout", "2000");
		overrides.put(SMTP_HOST.key(), "smtp.example.test");

		final SimpleJavaMailConfig merged = ConfigLoader.builder()
				.withConfig(base)
				.withMap("overrides", overrides)
				.load();

		assertThat(merged.<Map<String, String>>getProperty(EXTRA_PROPERTIES)).containsEntry("mail.smtp.timeout", "2000");
		assertThat(merged.<Map<UUID, ?>>getProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS)).containsKey(clusterKey);
		assertThat(merged.getStringProperty(SMTP_HOST)).isEqualTo("smtp.example.test");
	}

	@Test
	void parallelLoadsRemainIsolated() throws Exception {
		final ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			final List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				final String expected = "smtp-" + i + ".example.test";
				futures.add(executor.submit(new Callable<String>() {
					@Override
					public String call() {
						return ConfigLoader.builder()
								.withMap(Collections.<String, Object>singletonMap(SMTP_HOST.key(), expected))
								.load()
								.getStringProperty(SMTP_HOST);
					}
				}));
			}
			for (int i = 0; i < futures.size(); i++) {
				assertThat(futures.get(i).get()).isEqualTo("smtp-" + i + ".example.test");
			}
		} finally {
			executor.shutdownNow();
		}
	}

	private static ConfigSource source(final String name, final Map<String, ?> properties) {
		return new ConfigSource() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public Map<String, ?> getProperties() {
				return properties;
			}
		};
	}

	private static final class CloseTrackingInputStream extends ByteArrayInputStream {
		private boolean closed;

		private CloseTrackingInputStream(final byte[] data) {
			super(data);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	private static final class BrokenInputStream extends InputStream {
		private boolean closed;

		@Override
		public int read() throws IOException {
			throw new IOException("broken test stream");
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class SecretValue {
		private final String value;

		private SecretValue(final String value) {
			this.value = value;
		}

		@Override
		public String toString() {
			return value;
		}
	}
}
