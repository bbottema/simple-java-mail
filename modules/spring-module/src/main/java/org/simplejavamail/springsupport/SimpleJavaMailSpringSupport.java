package org.simplejavamail.springsupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * Exposes one immutable {@link SimpleJavaMailConfig}, one configured {@link SimpleJavaMail} factory and one default {@link Mailer} per Spring application
 * context. Spring's {@link ConfigurableEnvironment} remains authoritative for profiles, placeholders and property-source precedence.
 * <p>
 * Inject {@link SimpleJavaMail} when you need a fresh email or Mailer builder. Builder behavior and options are documented by
 * {@link EmailStartingBuilder}, {@link EmailPopulatingBuilder} and {@link MailerRegularBuilder}.
 * <p>
 * Wildcard Session and connection-pool properties can only be discovered in Spring {@link EnumerablePropertySource} instances. Each discovered key is
 * still resolved through the Environment before it is loaded.
 *
 * @see <a href="https://www.simplejavamail.org/spring.html">Spring integration documentation</a>
 */
@Configuration
public class SimpleJavaMailSpringSupport {

	private final SimpleJavaMailSpringBeanFactory springBeanFactory;

	/**
	 * Creates the manual Spring integration for one application context, using that context's Environment as the authoritative property source.
	 *
	 * @param environment The Environment whose profiles, placeholders and property-source precedence should be retained.
	 */
	public SimpleJavaMailSpringSupport(@NotNull final ConfigurableEnvironment environment) {
		this.springBeanFactory = new SimpleJavaMailSpringBeanFactory(environment);
	}

	/**
	 * Resolves the context's immutable configuration once. Localhost is the fallback SMTP host, a conventional {@code simplejavamail.properties}
	 * resource can override it, and all values exposed by Spring override both. Raw environment variables and JVM system properties are not loaded again
	 * outside Spring.
	 */
	@Bean
	public SimpleJavaMailConfig simpleJavaMailConfig() {
		return springBeanFactory.loadConfiguration();
	}

	/**
	 * @return The context-local factory used to obtain fresh builders.
	 */
	@Bean
	public SimpleJavaMail simpleJavaMail(@NotNull final SimpleJavaMailConfig simpleJavaMailConfig) {
		return springBeanFactory.createSimpleJavaMail(simpleJavaMailConfig);
	}

	/**
	 * Builds the context's default Mailer from a fresh {@link MailerRegularBuilder}. Spring closes the Mailer with the application context.
	 */
	@Bean(destroyMethod = "close")
	public Mailer defaultMailer(@NotNull final SimpleJavaMail simpleJavaMail,
			@NotNull final ObjectProvider<OAuth2AccessTokenProvider> oauth2AccessTokenProvider) {
		return springBeanFactory.createDefaultMailer(simpleJavaMail, oauth2AccessTokenProvider);
	}
}
