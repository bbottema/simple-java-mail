package org.simplejavamail.springsupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Collections;
import java.util.Map;

/**
 * Keeps manual Spring configuration and Spring Boot auto-configuration on the same Environment resolution and bean-construction path.
 */
final class SimpleJavaMailSpringBeanFactory {

	private static final Map<String, Object> SPRING_DEFAULTS =
			Collections.<String, Object>singletonMap(Property.SMTP_HOST.key(), "localhost");

	private final ConfigurableEnvironment environment;
	private boolean telemetryActivation;

	SimpleJavaMailSpringBeanFactory(@NotNull final ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	void configureTelemetryActivation(@NotNull final ListableBeanFactory beanFactory) {
		// The annotation belongs to this context, even when another context supplies a parent factory or integration bean.
		telemetryActivation = beanFactory.getBeanNamesForType(SjmTelemetryActivation.class, false, false).length != 0;
	}

	SimpleJavaMailConfig loadConfiguration() {
		final ConfigLoader loader = ConfigLoader.builder().withMap("Spring defaults", SPRING_DEFAULTS);
		if (telemetryActivation) {
			loader.withMap("@EnableSjmTelemetry", Collections.singletonMap(Property.TELEMETRY_ENABLED.key(), true));
		}
		return loader
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.withSource(new SpringEnvironmentConfigSource(environment))
				.load();
	}

	SimpleJavaMail createSimpleJavaMail(@NotNull final SimpleJavaMailConfig simpleJavaMailConfig) {
		return SimpleJavaMail.withConfig(simpleJavaMailConfig);
	}

	Mailer createDefaultMailer(@NotNull final SimpleJavaMail simpleJavaMail,
			@NotNull final ObjectProvider<OAuth2AccessTokenProvider> oauth2AccessTokenProvider,
			@NotNull final ObjectProvider<MailSendTelemetry> mailSendTelemetry) {
		final MailerRegularBuilder<?> builder = simpleJavaMail.mailerBuilder();
		final OAuth2AccessTokenProvider configuredAccessTokenProvider = oauth2AccessTokenProvider.getIfAvailable();
		if (configuredAccessTokenProvider != null) {
			builder.withOAuth2AccessTokenProvider(configuredAccessTokenProvider);
		}
		final MailSendTelemetry configuredTelemetry = mailSendTelemetry.getIfAvailable();
		if (configuredTelemetry != null) {
			builder.withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(configuredTelemetry).build());
		}
		return builder.buildMailer();
	}
}
