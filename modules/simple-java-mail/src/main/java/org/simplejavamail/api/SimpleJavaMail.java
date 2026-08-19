package org.simplejavamail.api;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.ConfiguredEmailConverter;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.mailer.internal.MailerFromSessionBuilderImpl;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;

import static java.util.Objects.requireNonNull;

/**
 * Immutable entry point for creating email and Mailer builders that all use one configuration snapshot.
 * <p>
 * Keep one instance in application scope and request a fresh builder for each independent construction flow. The factory owns no Mailer resources and does
 * not reload configuration.
 */
public final class SimpleJavaMail {

	private final SimpleJavaMailConfig config;

	private SimpleJavaMail(@NotNull final SimpleJavaMailConfig config) {
		this.config = requireNonNull(config, "config");
	}

	/**
	 * Uses the conventional {@code simplejavamail.properties}, environment-variable, and system-property sources in that order. The immutable snapshot is
	 * initialized lazily on first use. Spring configuration is never consulted by this method.
	 *
	 * @return The conventional configured factory.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public static SimpleJavaMail fromDefaults() {
		return DefaultsHolder.INSTANCE;
	}

	/**
	 * Creates a factory for an explicit immutable configuration snapshot.
	 *
	 * @param config The snapshot used by every builder produced by the factory.
	 * @return A configured factory independent from every other factory.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public static SimpleJavaMail withConfig(@NotNull final SimpleJavaMailConfig config) {
		return new SimpleJavaMail(config);
	}

	/**
	 * @return A fresh email starter governed by {@link EmailStartingBuilder}.
	 * @see EmailStartingBuilder
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public EmailStartingBuilder emailBuilder() {
		return new EmailStartingBuilderImpl(config);
	}

	/**
	 * @return A conversion entry point that creates email builders from this factory's configuration snapshot.
	 * @see ConfiguredEmailConverter
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public ConfiguredEmailConverter converter() {
		return new ConfiguredEmailConverter(config);
	}

	/**
	 * @return A fresh regular Mailer builder governed by {@link MailerRegularBuilder}.
	 * @see MailerRegularBuilder
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public MailerRegularBuilder<?> mailerBuilder() {
		return new MailerRegularBuilderImpl(config);
	}

	/**
	 * Creates a fresh Mailer builder around the caller-provided Session.
	 *
	 * @see MailerFromSessionBuilder#usingSession(Session)
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public MailerFromSessionBuilder<?> mailerBuilder(@NotNull final Session session) {
		return new MailerFromSessionBuilderImpl(config).usingSession(session);
	}

	private static final class DefaultsHolder {
		private static final SimpleJavaMail INSTANCE = new SimpleJavaMail(ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.withEnvironmentVariables()
				.withSystemProperties()
				.load());
	}
}
