package org.simplejavamail.springsupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Makes the beans provided by {@link SimpleJavaMailSpringSupport} available through Spring Boot's auto-configuration discovery.
 * Each bean backs off independently when the application supplies the same type.
 * <p>
 * Applications normally activate this class by adding {@code simple-java-mail-spring-boot-starter} or by already having {@code spring-module} in a Boot
 * application. They do not need to import this class directly.
 *
 * @see SimpleJavaMailSpringSupport
 */
@AutoConfiguration
@ConditionalOnClass(SimpleJavaMail.class)
public class SimpleJavaMailAutoConfiguration {

	private final SimpleJavaMailSpringBeanFactory springBeanFactory;

	SimpleJavaMailAutoConfiguration(@NotNull final ConfigurableEnvironment environment) {
		this.springBeanFactory = new SimpleJavaMailSpringBeanFactory(environment);
	}

	@Autowired
	private void configureTelemetryActivation(final ListableBeanFactory beanFactory) {
		springBeanFactory.configureTelemetryActivation(beanFactory);
	}

	/**
	 * Supplies the context's immutable configuration unless the application defines its own snapshot.
	 * {@link EnableSjmTelemetry} contributes a fallback below explicit properties when this integration loads the snapshot.
	 *
	 * @return The configuration snapshot for this application context.
	 */
	@Bean
	@ConditionalOnMissingBean(SimpleJavaMailConfig.class)
	public SimpleJavaMailConfig simpleJavaMailConfig() {
		return springBeanFactory.loadConfiguration();
	}

	/**
	 * Supplies a context-local builder factory using whichever configuration snapshot is available.
	 *
	 * @return The configured factory for this application context.
	 */
	@Bean
	@ConditionalOnMissingBean(SimpleJavaMail.class)
	public SimpleJavaMail simpleJavaMail(@NotNull final SimpleJavaMailConfig simpleJavaMailConfig) {
		return springBeanFactory.createSimpleJavaMail(simpleJavaMailConfig);
	}

	/**
	 * Supplies the default Mailer unless the application defines its own, using any selected OAuth2 and mail-send telemetry beans.
	 * Spring closes the Mailer created here, but it does not take ownership of an application's telemetry SDK.
	 *
	 * @return The context's default Mailer.
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTelemetryConfig(org.simplejavamail.api.mailer.config.MailSendTelemetryConfig)
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean(Mailer.class)
	public Mailer defaultMailer(@NotNull final SimpleJavaMail simpleJavaMail,
			@NotNull final ObjectProvider<OAuth2AccessTokenProvider> oauth2AccessTokenProvider,
			@NotNull final ObjectProvider<MailSendTelemetry> mailSendTelemetry) {
		return springBeanFactory.createDefaultMailer(simpleJavaMail, oauth2AccessTokenProvider, mailSendTelemetry);
	}
}
