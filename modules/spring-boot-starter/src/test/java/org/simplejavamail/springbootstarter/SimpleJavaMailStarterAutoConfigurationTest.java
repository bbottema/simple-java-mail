package org.simplejavamail.springbootstarter;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.springsupport.SimpleJavaMailAutoConfiguration;
import org.simplejavamail.springsupport.SimpleJavaMailSpringSupport;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.PropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJavaMailStarterAutoConfigurationTest {

	@Test
	void discoversAndCreatesAllDefaultsWithoutSmtpConfiguration() {
		try (ConfigurableApplicationContext context = startApplication()) {
			assertThat(context.getBeansOfType(SimpleJavaMailConfig.class)).containsOnlyKeys("simpleJavaMailConfig");
			assertThat(context.getBeansOfType(SimpleJavaMail.class)).containsOnlyKeys("simpleJavaMail");
			assertThat(context.getBeansOfType(Mailer.class)).containsOnlyKeys("defaultMailer");
			assertThat(context.getBean(SimpleJavaMailConfig.class).getStringProperty(ConfigLoader.Property.SMTP_HOST)).isEqualTo("localhost");
			assertThat(context.getBean(Mailer.class).getServerConfig().getHost()).isEqualTo("localhost");
			assertThat(context.getBean(Mailer.class).getOperationalConfig().getOAuth2AccessTokenProvider()).isNull();
		}
	}

	@Test
	void closesTheAutoConfiguredMailerWithTheApplicationContext() {
		final ConfigurableApplicationContext context = startApplication();
		final ExecutorService mailerOwnedExecutor = context.getBean(Mailer.class).getOperationalConfig().getExecutorService();

		try {
			assertThat(mailerOwnedExecutor.isShutdown()).isFalse();
		} finally {
			context.close();
		}

		assertThat(mailerOwnedExecutor.isShutdown()).isTrue();
	}

	@Test
	void customConfigurationFeedsTheDefaultFactoryAndMailer() {
		try (ConfigurableApplicationContext context = startApplication(CustomConfigBeanConfiguration.class)) {
			final SimpleJavaMailConfig customConfig = context.getBean("customConfig", SimpleJavaMailConfig.class);

			assertThat(context.getBean(SimpleJavaMailConfig.class)).isSameAs(customConfig);
			assertThat(context.getBean(SimpleJavaMail.class).mailerBuilder().getHost()).isEqualTo("custom-config.example.test");
			assertThat(context.getBean(Mailer.class).getServerConfig().getHost()).isEqualTo("custom-config.example.test");
		}
	}

	@Test
	void customFactoryFeedsTheDefaultMailerWhileConfigurationRemainsAvailable() {
		try (ConfigurableApplicationContext context = startApplication(CustomFactoryBeanConfiguration.class)) {
			final SimpleJavaMail customFactory = context.getBean("customFactory", SimpleJavaMail.class);

			assertThat(context.getBean(SimpleJavaMail.class)).isSameAs(customFactory);
			assertThat(context.getBeansOfType(SimpleJavaMailConfig.class)).containsOnlyKeys("simpleJavaMailConfig");
			assertThat(context.getBean(Mailer.class).getServerConfig().getHost()).isEqualTo("custom-factory.example.test");
		}
	}

	@Test
	void customMailerReplacesOnlyTheMailerAndRemainsApplicationOwned() throws Exception {
		final ConfigurableApplicationContext context = startApplication(CustomMailerBeanConfiguration.class);
		final Mailer customMailer = context.getBean(Mailer.class);
		final ExecutorService customMailerExecutor = customMailer.getOperationalConfig().getExecutorService();

		try {
			assertThat(context.getBeansOfType(SimpleJavaMailConfig.class)).containsOnlyKeys("simpleJavaMailConfig");
			assertThat(context.getBeansOfType(SimpleJavaMail.class)).containsOnlyKeys("simpleJavaMail");
			assertThat(context.getBeansOfType(Mailer.class)).containsOnlyKeys("customMailer");
			context.close();

			assertThat(customMailerExecutor.isShutdown()).isFalse();
		} finally {
			context.close();
			customMailer.close();
		}
		assertThat(customMailerExecutor.isShutdown()).isTrue();
	}

	@Test
	void explicitManualImportAndAutoConfigurationProduceOneBeanOfEachType() {
		try (ConfigurableApplicationContext context = startApplication(ManualSpringConfiguration.class)) {
			assertThat(context.getBeansOfType(SimpleJavaMailConfig.class)).containsOnlyKeys("simpleJavaMailConfig");
			assertThat(context.getBeansOfType(SimpleJavaMail.class)).containsOnlyKeys("simpleJavaMail");
			assertThat(context.getBeansOfType(Mailer.class)).containsOnlyKeys("defaultMailer");
			assertThat(context.getBeanFactory().getBeanDefinition("simpleJavaMailConfig").getFactoryBeanName())
					.isEqualTo("org.simplejavamail.springsupport.SimpleJavaMailSpringSupport");
		}
	}

	@Test
	void autoConfiguredApplicationContextsKeepIndependentSnapshotsAndLifecycles() {
		try (ConfigurableApplicationContext ordersContext = applicationBuilder().run(
				"--simplejavamail.smtp.host=orders.example.test",
				"--simplejavamail.defaults.subject=Orders");
				ConfigurableApplicationContext reportingContext = applicationBuilder().run(
						"--simplejavamail.smtp.host=reporting.example.test",
						"--simplejavamail.defaults.subject=Reporting")) {
			final SimpleJavaMailConfig ordersConfig = ordersContext.getBean(SimpleJavaMailConfig.class);
			final SimpleJavaMailConfig reportingConfig = reportingContext.getBean(SimpleJavaMailConfig.class);
			final SimpleJavaMail ordersMail = ordersContext.getBean(SimpleJavaMail.class);
			final SimpleJavaMail reportingMail = reportingContext.getBean(SimpleJavaMail.class);
			final ExecutorService reportingMailerExecutor =
					reportingContext.getBean(Mailer.class).getOperationalConfig().getExecutorService();

			assertThat(ordersConfig).isNotSameAs(reportingConfig);
			assertThat(ordersMail).isNotSameAs(reportingMail);
			assertThat(ordersMail.mailerBuilder().getHost()).isEqualTo("orders.example.test");
			assertThat(reportingMail.mailerBuilder().getHost()).isEqualTo("reporting.example.test");
			assertThat(ordersConfig.getStringProperty(ConfigLoader.Property.DEFAULT_SUBJECT)).isEqualTo("Orders");
			assertThat(reportingConfig.getStringProperty(ConfigLoader.Property.DEFAULT_SUBJECT)).isEqualTo("Reporting");

			ordersContext.close();

			assertThat(reportingContext.isActive()).isTrue();
			assertThat(reportingMailerExecutor.isShutdown()).isFalse();
			assertThat(reportingMail.mailerBuilder().getHost()).isEqualTo("reporting.example.test");
		}
	}

	@Test
	void applicationOAuthProviderIsAppliedToTheDefaultMailer() {
		try (ConfigurableApplicationContext context = oauthApplicationBuilder(OneOAuthProviderConfiguration.class).run()) {
			assertThat(context.getBean(Mailer.class).getOperationalConfig().getOAuth2AccessTokenProvider())
					.isSameAs(context.getBean(OAuth2AccessTokenProvider.class));
		}
	}

	@Test
	void primaryOAuthProviderIsSelectedWhenSeveralExist() {
		try (ConfigurableApplicationContext context = oauthApplicationBuilder(PrimaryOAuthProviderConfiguration.class).run()) {
			assertThat(context.getBean(Mailer.class).getOperationalConfig().getOAuth2AccessTokenProvider())
					.isSameAs(context.getBean("primaryOAuthProvider", OAuth2AccessTokenProvider.class));
		}
	}

	@Test
	void ambiguousOAuthProviderFailureNamesAllCandidates() {
		assertThatThrownBy(() -> oauthApplicationBuilder(AmbiguousOAuthProviderConfiguration.class).run())
				.hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
				.hasStackTraceContaining("firstOAuthProvider")
				.hasStackTraceContaining("secondOAuthProvider");
	}

	@Test
	void activeProfilesPlaceholdersAndPropertySourcePrecedenceRemainAuthoritative() {
		try (ConfigurableApplicationContext context = applicationBuilder(ProfilePropertyConfiguration.class)
				.profiles("integration")
				.run(
						"--profile.smtp.host=command-line.example.test",
						"--simplejavamail.smtp.port=2526",
						"--simplejavamail.extraproperties.source=command-line")) {
			final SimpleJavaMailConfig config = context.getBean(SimpleJavaMailConfig.class);

			assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("integration");
			assertThat(config.getStringProperty(ConfigLoader.Property.SMTP_HOST)).isEqualTo("command-line.example.test");
			assertThat(config.getIntegerProperty(ConfigLoader.Property.SMTP_PORT)).isEqualTo(2526);
			assertThat(config.<Map<String, String>>getProperty(ConfigLoader.Property.EXTRA_PROPERTIES))
					.containsEntry("source", "command-line");
		}
	}

	@Test
	void standardBootExclusionDisablesAllDefaults() {
		try (ConfigurableApplicationContext context = applicationBuilder()
				.run("--spring.autoconfigure.exclude=" + SimpleJavaMailAutoConfiguration.class.getName())) {
			assertThat(context.getBeansOfType(SimpleJavaMailConfig.class)).isEmpty();
			assertThat(context.getBeansOfType(SimpleJavaMail.class)).isEmpty();
			assertThat(context.getBeansOfType(Mailer.class)).isEmpty();
		}
	}

	private static ConfigurableApplicationContext startApplication(final Class<?>... userConfigurations) {
		return applicationBuilder(userConfigurations).run();
	}

	private static SpringApplicationBuilder oauthApplicationBuilder(final Class<?>... userConfigurations) {
		return applicationBuilder(userConfigurations).properties("simplejavamail.transportstrategy=SMTP_OAUTH2");
	}

	private static SpringApplicationBuilder applicationBuilder(final Class<?>... userConfigurations) {
		final List<Class<?>> applicationSources = new ArrayList<>();
		applicationSources.add(TestApplication.class);
		applicationSources.addAll(Arrays.asList(userConfigurations));
		return new SpringApplicationBuilder(applicationSources.toArray(new Class<?>[0]))
				.web(WebApplicationType.NONE)
				.registerShutdownHook(false)
				.properties("spring.main.banner-mode=off", "spring.main.log-startup-info=false");
	}

	private static SimpleJavaMailConfig configurationForHost(final String smtpHost) {
		return ConfigLoader.builder()
				.withMap(Collections.<String, Object>singletonMap(ConfigLoader.Property.SMTP_HOST.key(), smtpHost))
				.load();
	}

	@SpringBootConfiguration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class TestApplication {
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomConfigBeanConfiguration {

		@Bean
		SimpleJavaMailConfig customConfig() {
			return configurationForHost("custom-config.example.test");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomFactoryBeanConfiguration {

		@Bean
		SimpleJavaMail customFactory() {
			return SimpleJavaMail.withConfig(configurationForHost("custom-factory.example.test"));
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomMailerBeanConfiguration {

		@Bean(destroyMethod = "")
		Mailer customMailer() {
			return SimpleJavaMail.withConfig(configurationForHost("custom-mailer.example.test")).mailerBuilder().buildMailer();
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Import(SimpleJavaMailSpringSupport.class)
	static class ManualSpringConfiguration {
	}

	@Configuration(proxyBeanMethods = false)
	static class OneOAuthProviderConfiguration {

		@Bean
		OAuth2AccessTokenProvider oauthProvider() {
			return () -> "one";
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class PrimaryOAuthProviderConfiguration {

		@Bean
		@Primary
		OAuth2AccessTokenProvider primaryOAuthProvider() {
			return () -> "primary";
		}

		@Bean
		OAuth2AccessTokenProvider secondaryOAuthProvider() {
			return () -> "secondary";
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class AmbiguousOAuthProviderConfiguration {

		@Bean
		OAuth2AccessTokenProvider firstOAuthProvider() {
			return () -> "first";
		}

		@Bean
		OAuth2AccessTokenProvider secondOAuthProvider() {
			return () -> "second";
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("integration")
	@PropertySource("classpath:profile-simplejavamail.properties")
	static class ProfilePropertyConfiguration {
	}
}
