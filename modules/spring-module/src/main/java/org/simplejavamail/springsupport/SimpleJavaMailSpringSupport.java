package org.simplejavamail.springsupport;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.ConfigSource;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * @see <a href="https://www.simplejavamail.org/configuration.html#section-spring-support">Spring support documentation</a>
 */
@Configuration
public class SimpleJavaMailSpringSupport {

	private static final String EXTRA_PROPERTIES_PREFIX = "simplejavamail.extraproperties.";
	private static final String CONNECTIONPOOL_CLUSTERS_PREFIX = "simplejavamail.defaults.connectionpool.clusters.";

	private static final Map<String, String[]> COMPATIBILITY_ALIASES = compatibilityAliases();

	private final ConfigurableEnvironment environment;

	public SimpleJavaMailSpringSupport(@NotNull final ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	/**
	 * Resolves the context's immutable configuration once. A conventional {@code simplejavamail.properties} resource remains the low-priority source;
	 * all values exposed by Spring override it. Raw environment variables and JVM system properties are not loaded again outside Spring.
	 */
	@Bean
	public SimpleJavaMailConfig simpleJavaMailConfig() {
		return ConfigLoader.builder()
				.withClasspathResource(ConfigLoader.DEFAULT_CONFIG_FILENAME)
				.withSource(springEnvironmentSource())
				.load();
	}

	/**
	 * @return The context-local factory used to obtain fresh builders.
	 */
	@Bean
	public SimpleJavaMail simpleJavaMail(@NotNull final SimpleJavaMailConfig simpleJavaMailConfig) {
		return SimpleJavaMail.withConfig(simpleJavaMailConfig);
	}

	/**
	 * Builds the context's default Mailer from a fresh {@link MailerRegularBuilder}. Spring closes the Mailer with the application context.
	 */
	@Bean(destroyMethod = "close")
	public Mailer defaultMailer(@NotNull final SimpleJavaMail simpleJavaMail,
			@NotNull final ObjectProvider<OAuth2AccessTokenProvider> oauth2AccessTokenProvider) {
		final MailerRegularBuilder<?> builder = simpleJavaMail.mailerBuilder();
		final OAuth2AccessTokenProvider provider = oauth2AccessTokenProvider.getIfAvailable();
		if (provider != null) {
			builder.withOAuth2AccessTokenProvider(provider);
		}
		return builder.buildMailer();
	}

	private ConfigSource springEnvironmentSource() {
		return new ConfigSource() {
			@Override
			public String getName() {
				return "Spring Environment";
			}

			@Override
			public Map<String, ?> getProperties() {
				final Map<String, Object> resolved = new LinkedHashMap<>();
				for (Property property : Property.values()) {
					if (property == Property.EXTRA_PROPERTIES || property == Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS) {
						continue;
					}
					final String value = resolveScalarProperty(property.key());
					if (value != null) {
						resolved.put(property.key(), value);
					}
				}
				discoverWildcardProperties(resolved);
				return resolved;
			}
		};
	}

	@Nullable
	private String resolveScalarProperty(final String canonicalKey) {
		final String canonicalValue = environment.getProperty(canonicalKey);
		if (canonicalValue != null) {
			return canonicalValue;
		}
		for (String alias : aliasesFor(canonicalKey)) {
			final String aliasValue = environment.getProperty(alias);
			if (aliasValue != null) {
				return aliasValue;
			}
		}
		return null;
	}

	private void discoverWildcardProperties(final Map<String, Object> resolved) {
		for (PropertySource<?> source : environment.getPropertySources()) {
			if (source instanceof EnumerablePropertySource) {
				for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
					if (name.startsWith(EXTRA_PROPERTIES_PREFIX) || name.startsWith(CONNECTIONPOOL_CLUSTERS_PREFIX)) {
						final String value = environment.getProperty(name);
						if (value != null) {
							resolved.put(name, value);
						}
					}
				}
			}
		}
	}

	private static String[] aliasesFor(final String canonicalKey) {
		final String[] explicit = COMPATIBILITY_ALIASES.get(canonicalKey);
		if (explicit != null) {
			return explicit;
		}
		if (canonicalKey.indexOf('_') >= 0) {
			return new String[] { canonicalKey.replace('_', '-') };
		}
		return new String[0];
	}

	private static Map<String, String[]> compatibilityAliases() {
		final Map<String, String[]> aliases = new LinkedHashMap<>();
		aliases.put(Property.JAVAXMAIL_DEBUG_OUTPUT.key(), new String[] { "simplejavamail.javaxmail.debug-out" });
		aliases.put(Property.CUSTOM_SSLFACTORY_CLASS.key(), new String[] { "simplejavamail.custom.sslfactory.clazz" });
		aliases.put(Property.DEFAULT_POOL_KEEP_ALIVE_TIME.key(), new String[] { "simplejavamail.defaults.poolsize-more.keepalivetime" });
		aliases.put(Property.SMIME_SIGNING_KEYSTORE_PASSWORD.key(), new String[] { "simplejavamail.smime.signing.keystore-password" });
		aliases.put(Property.SMIME_SIGNING_KEY_ALIAS.key(), new String[] { "simplejavamail.smime.signing.key-alias" });
		aliases.put(Property.SMIME_SIGNING_KEY_PASSWORD.key(), new String[] { "simplejavamail.smime.signing.key-password" });
		aliases.put(Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), new String[] { "simplejavamail.dkim.signing.private-key-file-or-data" });
		aliases.put(Property.DKIM_SIGNING_DOMAIN.key(), new String[] { "simplejavamail.dkim.signing.signing-domain" });
		aliases.put(Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST.key(),
				new String[] { "simplejavamail.dkim.signing.excluded-headers-from-default-signing-list" });
		return Collections.unmodifiableMap(aliases);
	}
}
