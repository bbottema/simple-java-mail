package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigKeys;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import javax.net.ssl.SSLSocketFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.config.ConfigLoader.Property.CUSTOM_SSLFACTORY_CLASS;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.emptyAsNull;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see MailerRegularBuilder
 */
@Slf4j
public class MailerRegularBuilderImpl extends MailerGenericBuilderImpl<MailerRegularBuilderImpl> implements InternalMailerRegularBuilder<MailerRegularBuilderImpl>, org.simplejavamail.api.internal.clisupport.CliRegularMailerConfigurationBuilder {

	/**
	 * @see MailerRegularBuilder#withSMTPServerHost(String)
	 */
	private String host;
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerPort(Integer)
	 */
	private Integer port;
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerUsername(String)
	 */
	private String username;
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerPassword(String)
	 */
	private String password;

	/**
	 * @see MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 */
	@NotNull
	private TransportStrategy transportStrategy;
	private boolean transportStrategyExplicit;

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryClass(String)
	 */
	private String customSSLFactory;

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	private SSLSocketFactory customSSLFactoryInstance;
	private boolean customSSLFactoryInstanceExplicit;

	/**
	 * @see MailerRegularBuilder#withOpportunisticTLS(boolean)
	 */
	private boolean opportunisticTLS;

	public MailerRegularBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
		this(config, new FactorySendingLimits());
	}

	/** Internal factory wiring; builders retain their factory's allowance registry, not a process-global registry. */
	public MailerRegularBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySendingLimits sendingLimits) {
		this(config, new FactoryMailerRuntime(config, sendingLimits));
	}

	/** Internal factory wiring shares named destination history while each built Mailer retains its own lifecycle. */
	public MailerRegularBuilderImpl(final SimpleJavaMailConfig config, final FactoryMailerRuntime runtime) {
		this(runtime.independent(config), runtime);
	}

	MailerRegularBuilderImpl(final FactoryMailerRuntime.Definition definition, final FactoryMailerRuntime runtime) {
		super(definition, runtime);
		final SimpleJavaMailConfig config = definition.config;
		this.opportunisticTLS = config.valueOrProperty(null, ConfigKeys.OPPORTUNISTIC_TLS, DEFAULT_OPPORTUNISTIC_TLS);
		this.host = config.get(ConfigKeys.SMTP_HOST);
		this.port = config.get(ConfigKeys.SMTP_PORT);
		this.username = config.get(ConfigKeys.SMTP_USERNAME);
		this.password = config.get(ConfigKeys.SMTP_PASSWORD);
		this.transportStrategy = config.hasProperty(ConfigKeys.TRANSPORT_STRATEGY)
				? verifyNonnullOrEmpty(config.get(ConfigKeys.TRANSPORT_STRATEGY))
				: TransportStrategy.SMTP;
		this.customSSLFactory = config.get(ConfigKeys.CUSTOM_SSLFACTORY_CLASS);
	}

	/**
	 * @see MailerRegularBuilder#withOpportunisticTLS(boolean)
	 */
	@Override
	public MailerRegularBuilderImpl withOpportunisticTLS(final boolean opportunisticTLS) {
		this.opportunisticTLS = opportunisticTLS;
		customizedSessionSetting(OPPORTUNISTIC_TLS, opportunisticTLS);
		return this;
	}

	/** @see MailerRegularBuilder#isOpportunisticTLS() */
	@Override
	public boolean isOpportunisticTLS() {
		return opportunisticTLS;
	}

	/** @see MailerRegularBuilder#resetOpportunisticTLS() */
	@Override
	public MailerRegularBuilderImpl resetOpportunisticTLS() {
		return withOpportunisticTLS(DEFAULT_OPPORTUNISTIC_TLS);
	}
	
	/**
	 * @see MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 */
	@Override
	public MailerRegularBuilderImpl withTransportStrategy(@NotNull final TransportStrategy transportStrategy) {
		this.transportStrategy = java.util.Objects.requireNonNull(transportStrategy, "transportStrategy");
		transportStrategyExplicit = true;
		customizedSessionSetting(TRANSPORT_STRATEGY, transportStrategy);
		return this;
	}

	/**
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer, String, String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port,
			@Nullable final String username, @Nullable final String password) {
		return withSMTPServerHost(host)
				.withSMTPServerPort(port)
				.withSMTPServerUsername(emptyAsNull(username))
				.withSMTPServerPassword(emptyAsNull(password));
	}

	/**
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer, String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username) {
		return withSMTPServerHost(host)
				.withSMTPServerPort(port)
				.withSMTPServerUsername(username);
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port) {
		return withSMTPServerHost(host)
				.withSMTPServerPort(port);
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerHost(String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerHost(@Nullable final String host) {
		this.host = host;
		customizedSessionSetting(SMTP_HOST, host);
		return this;
	}

	/** @see MailerRegularBuilder#clearSMTPServerHost() */
	@Override
	public MailerRegularBuilderImpl clearSMTPServerHost() {
		return withSMTPServerHost(null);
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerPort(Integer)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerPort(@Nullable final Integer port) {
		this.port = port;
		customizedSessionSetting(SMTP_PORT, port);
		return this;
	}

	/** @see MailerRegularBuilder#clearSMTPServerPort() */
	@Override
	public MailerRegularBuilderImpl clearSMTPServerPort() {
		return withSMTPServerPort(null);
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerUsername(String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerUsername(@Nullable final String username) {
		this.username = emptyAsNull(username);
		customizedSessionSetting(SMTP_USERNAME, this.username);
		return this;
	}

	/** @see MailerRegularBuilder#clearSMTPServerUsername() */
	@Override
	public MailerRegularBuilderImpl clearSMTPServerUsername() {
		return withSMTPServerUsername(null);
	}

	/**
	 * @see MailerRegularBuilder#withSMTPServerPassword(String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerPassword(@Nullable final String password) {
		this.password = emptyAsNull(password);
		customizedSessionSetting(SMTP_PASSWORD, this.password);
		return this;
	}

	/** @see MailerRegularBuilder#clearSMTPServerPassword() */
	@Override
	public MailerRegularBuilderImpl clearSMTPServerPassword() {
		return withSMTPServerPassword(null);
	}

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryClass(String)
	 */
	@Override
	public MailerRegularBuilderImpl withCustomSSLFactoryClass(@Nullable final String customSSLFactory) {
		this.customSSLFactory = customSSLFactory;
		customizedSessionSetting(CUSTOM_SSLFACTORY_CLASS, customSSLFactory);
		return this;
	}

	/** @see MailerRegularBuilder#clearCustomSSLFactoryClass() */
	@Override
	public MailerRegularBuilderImpl clearCustomSSLFactoryClass() {
		return withCustomSSLFactoryClass(null);
	}

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	@Override
	public MailerRegularBuilderImpl withCustomSSLFactoryInstance(@Nullable final SSLSocketFactory customSSLFactoryInstance) {
		this.customSSLFactoryInstance = customSSLFactoryInstance;
		customSSLFactoryInstanceExplicit = true;
		return this;
	}

	/** @see MailerRegularBuilder#clearCustomSSLFactoryInstance() */
	@Override
	public MailerRegularBuilderImpl clearCustomSSLFactoryInstance() {
		return withCustomSSLFactoryInstance(null);
	}

	/**
	 * @see MailerRegularBuilder#buildMailer()
	 */
	@Override
	public Mailer buildMailer() {
		return buildConfiguredMailer();
	}

	@Override
	MailerImpl constructMailer(final ValidatedConnectionPlan plan) {
		warnAboutIgnoredSmtpSettings();
		return new MailerImpl(this, plan);
	}

	private void warnAboutIgnoredSmtpSettings() {
		if (getCustomMailer() != null && host != null) {
			log.warn("Both custom mailer and SMTP server configured, ignoring server configuration");
		}
	}

	@Override
	void validateLockedConfiguration() {
		super.validateLockedConfiguration();
		locksOf(configuration()).verify(SMTP_HOST, host);
		locksOf(configuration()).verify(SMTP_PORT, port);
		locksOf(configuration()).verify(SMTP_USERNAME, username);
		locksOf(configuration()).verify(SMTP_PASSWORD, password);
		locksOf(configuration()).verify(TRANSPORT_STRATEGY, transportStrategy);
		locksOf(configuration()).verify(CUSTOM_SSLFACTORY_CLASS, customSSLFactory);
		locksOf(configuration()).verify(OPPORTUNISTIC_TLS, opportunisticTLS);
		if (customSSLFactoryInstance != null && locksOf(configuration()).contains(CUSTOM_SSLFACTORY_CLASS)) {
			throw locksOf(configuration()).conflict(CUSTOM_SSLFACTORY_CLASS,
					"withCustomSSLFactoryInstance(...) supplies a factory object, but this configuration locks the factory class to instantiate. "
							+ "The supplied object would replace that locked class choice. Remove the builder call, "
							+ "or remove the factory-class lock from the configuration source if your application should supply the instance.");
		}
	}

	@Override
	TransportStrategy transportStrategyForLocks() {
		if (!transportStrategyExplicit) {
			final String protocol = getProperties().getProperty("mail.transport.protocol");
			final boolean oauth2 = OAuth2AccessTokenResolver.isOAuth2Transport(transportStrategy);
			if ("smtps".equals(protocol) && transportStrategy != TransportStrategy.SMTPS && transportStrategy != TransportStrategy.SMTPS_OAUTH2) {
				return oauth2 ? TransportStrategy.SMTPS_OAUTH2 : TransportStrategy.SMTPS;
			}
			if ("smtp".equals(protocol) && (transportStrategy == TransportStrategy.SMTPS || transportStrategy == TransportStrategy.SMTPS_OAUTH2)) {
				return oauth2 ? TransportStrategy.SMTP_OAUTH2 : TransportStrategy.SMTP;
			}
		}
		return transportStrategy;
	}

	@Override
	void validateOwnedConnectionRequirements() {
		if (getCustomMailer() == null) {
			MailerImpl.validateMandatoryStartTls(transportStrategy, getProperties());
			MailerImpl.validateImplicitTlsOAuth2Protocol(transportStrategy, getProperties());
		}
	}

	/**
	 * Keeps supplied SMTP settings available to explicit connection probes, including in logging-only mode.
	 * Logging-only sends can still be built without an SMTP host; CustomMailer continues to own its connection settings.
	 */
	@Nullable
	ServerConfig buildServerConfig(final MailerSessionSettings sessionSettings) {
		if (getCustomMailer() != null) {
			return null;
		}
		final TransportStrategy strategy = requireNonNull(sessionSettings.getTransportStrategy(), "transportStrategy");
		final Map<String, Object> resolved = sessionSettings.getConnectionOverrides();
		final String resolvedHost = stringProperty(resolved.get(strategy.propertyNameHost()));
		final String resolvedUsername = stringProperty(resolved.get(strategy.propertyNameUsername()));
		final Object resolvedPort = resolved.get(strategy.propertyNamePort());
		final String resolvedFactory = stringProperty(resolved.get(strategy.propertyNameSSLSocketFactoryClass()));
		final Object factoryInstance = resolved.get(strategy.propertyNameSSLSocketFactory());
		final SSLSocketFactory resolvedFactoryInstance = factoryInstance instanceof SSLSocketFactory ? (SSLSocketFactory) factoryInstance : null;
		if (!isTransportModeLoggingOnly() || !valueNullOrEmpty(resolvedHost)) {
			checkArgumentNotEmpty(resolvedHost, "SMTP server host missing");
			final int serverPort = resolvedServerPort(resolvedPort, strategy);
			final String resolvedPassword = resolvedAuthenticationSecret(resolved, strategy);
			return new ServerConfigImpl(verifyNonnullOrEmpty(resolvedHost), serverPort, resolvedUsername, resolvedPassword, resolvedFactory, resolvedFactoryInstance);
		}
		return null;
	}

	@Nullable
	private static String stringProperty(@Nullable final Object value) {
		return value instanceof String ? (String) value : null;
	}

	private static int resolvedServerPort(@Nullable final Object resolvedPort, final TransportStrategy strategy) {
		return resolvedPort == null ? strategy.getDefaultServerPort()
				: ConnectionConfigurationResolver.integer(resolvedPort, strategy.propertyNamePort());
	}

	@Nullable
	private String resolvedAuthenticationSecret(final Map<String, Object> resolved, final TransportStrategy strategy) {
		return OAuth2AccessTokenResolver.isOAuth2Transport(strategy)
				? (String) resolved.get(TransportStrategy.OAUTH2_TOKEN_PROPERTY) : password;
	}

	@Override
	void addRegularConnectionDefaults(final Map<String, Object> defaults, final TransportStrategy strategy) {
		addSmtpServerDefaults(defaults, strategy);
		addAuthenticationDefaults(defaults, strategy);
		addSocketFactoryDefaults(defaults, strategy);
		if (strategy == TransportStrategy.SMTP && !opportunisticTLS) {
			defaults.remove("mail.smtp.starttls.enable");
			defaults.remove("mail.smtp.starttls.required");
		}
	}

	private void addSmtpServerDefaults(final Map<String, Object> defaults, final TransportStrategy strategy) {
		if (host != null) {
			defaults.put(strategy.propertyNameHost(), host);
		}
		defaults.put(strategy.propertyNamePort(), port == null ? strategy.getDefaultServerPort() : port);
		if (username != null) {
			defaults.put(strategy.propertyNameUsername(), username);
		}
	}

	private void addAuthenticationDefaults(final Map<String, Object> defaults, final TransportStrategy strategy) {
		if (password != null) {
			if (OAuth2AccessTokenResolver.isOAuth2Transport(strategy)) {
				defaults.put(TransportStrategy.OAUTH2_TOKEN_PROPERTY, password);
			} else {
				defaults.put(strategy.propertyNameAuthenticate(), "true");
			}
		}
	}

	private void addSocketFactoryDefaults(final Map<String, Object> defaults, final TransportStrategy strategy) {
		// Keep the fallback class for inspection and later instance clearing; Session activation selects the instance.
		if (customSSLFactoryInstance != null) {
			defaults.put(strategy.propertyNameSSLSocketFactory(), customSSLFactoryInstance);
		}
		if (customSSLFactory != null) {
			defaults.put(strategy.propertyNameSSLSocketFactoryClass(), customSSLFactory);
		}
	}

	@Override
	Map<String, Object> regularPhysicalChoices(@Nullable final TransportStrategy strategy) {
		final Map<String, Object> choices = new LinkedHashMap<>();
		if (customSSLFactoryInstanceExplicit && strategy != null) {
			choices.put(strategy.propertyNameSSLSocketFactory(), customSSLFactoryInstance);
		}
		return choices;
	}

	/**
	 * @see MailerRegularBuilder#getHost()
	 */
	@Override
	@Nullable
	public String getHost() {
		return host;
	}
	
	/**
	 * @see MailerRegularBuilder#getPort()
	 */
	@Override
	@Nullable
	public Integer getPort() {
		return port;
	}
	
	/**
	 * @see MailerRegularBuilder#getUsername()
	 */
	@Override
	@Nullable
	public String getUsername() {
		return username;
	}
	
	/**
	 * @see MailerRegularBuilder#getPassword()
	 */
	@Override
	@Nullable
	public String getPassword() {
		return password;
	}

	/**
	 * @see MailerRegularBuilder#getTransportStrategy()
	 */
	@Override
	@NotNull
	public TransportStrategy getTransportStrategy() {
		return transportStrategy;
	}

	/**
	 * @see MailerRegularBuilder#getCustomSSLFactory()
	 */
	@Override
	@Nullable
	public String getCustomSSLFactory() {
		return customSSLFactory;
	}

	/** @see MailerRegularBuilder#getCustomSSLFactoryInstance() */
	@Override
	@Nullable
	public SSLSocketFactory getCustomSSLFactoryInstance() {
		return customSSLFactoryInstance;
	}

    @Override void validateTlsConstructionScope(final TlsConfig choices) { }
    @Override void applyRegularTlsChoices(final TlsConfig choices) {
        customSSLFactory = choices.isCustomSSLFactoryClassCleared() ? null
                : stringChoice(choices.getCustomSSLFactoryClass(), ConfigKeys.CUSTOM_SSLFACTORY_CLASS);
        customSSLFactoryInstance = choices.getCustomSSLFactoryInstance();
        customSSLFactoryInstanceExplicit = choices.getCustomSSLFactoryInstance() != null || choices.isCustomSSLFactoryInstanceCleared();
        opportunisticTLS = configuration().valueOrProperty(choices.getOpportunisticTLS(), ConfigKeys.OPPORTUNISTIC_TLS, DEFAULT_OPPORTUNISTIC_TLS);
        if (choices.getCustomSSLFactoryClass() != null || choices.isCustomSSLFactoryClassCleared()) {
            customizedSessionSetting(CUSTOM_SSLFACTORY_CLASS, customSSLFactory);
        }
        if (choices.getOpportunisticTLS() != null) { customizedSessionSetting(OPPORTUNISTIC_TLS, opportunisticTLS); }
    }
}
