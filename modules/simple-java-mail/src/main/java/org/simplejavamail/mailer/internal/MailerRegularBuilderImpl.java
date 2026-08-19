package org.simplejavamail.mailer.internal;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.SimpleJavaMailConfig;

import javax.net.ssl.SSLSocketFactory;

import static java.util.Optional.ofNullable;
import static org.simplejavamail.config.ConfigLoader.Property.CUSTOM_SSLFACTORY_CLASS;
import static org.simplejavamail.config.ConfigLoader.Property.OPPORTUNISTIC_TLS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.emptyAsNull;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see MailerRegularBuilder
 */
@Slf4j
public class MailerRegularBuilderImpl extends MailerGenericBuilderImpl<MailerRegularBuilderImpl> implements MailerRegularBuilder<MailerRegularBuilderImpl> {

	private static final boolean DEFAULT_OPPORTUNISTIC_TLS = true;
	
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

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryClass(String)
	 */
	private String customSSLFactory;

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	private SSLSocketFactory customSSLFactoryInstance;

	/**
	 * @see MailerRegularBuilder#withOpportunisticTLS(boolean)
	 */
	private boolean opportunisticTLS;

	public MailerRegularBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
		super(config);
		this.opportunisticTLS = config.valueOrProperty(null, OPPORTUNISTIC_TLS, DEFAULT_OPPORTUNISTIC_TLS);
		this.host = config.getStringProperty(SMTP_HOST);
		this.port = config.getIntegerProperty(SMTP_PORT);
		this.username = config.getStringProperty(SMTP_USERNAME);
		this.password = config.getStringProperty(SMTP_PASSWORD);
		this.transportStrategy = config.hasProperty(TRANSPORT_STRATEGY)
				? verifyNonnullOrEmpty(config.getProperty(TRANSPORT_STRATEGY))
				: TransportStrategy.SMTP;
		this.customSSLFactory = config.getStringProperty(CUSTOM_SSLFACTORY_CLASS);
	}

	/**
	 * @see MailerRegularBuilder#withOpportunisticTLS(boolean)
	 */
	@Override
	public MailerRegularBuilderImpl withOpportunisticTLS(final boolean opportunisticTLS) {
		this.opportunisticTLS = opportunisticTLS;
		return this;
	}

	boolean isOpportunisticTLS() {
		return opportunisticTLS;
	}
	
	/**
	 * @see MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 */
	@Override
	public MailerRegularBuilderImpl withTransportStrategy(@NotNull final TransportStrategy transportStrategy) {
		this.transportStrategy = transportStrategy;
		return this;
	}

	/**
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer, String, String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username, @Nullable final String password) {
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
		return this;
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerPort(Integer)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerPort(@Nullable final Integer port) {
		this.port = port;
		return this;
	}
	
	/**
	 * @see MailerRegularBuilder#withSMTPServerUsername(String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerUsername(@Nullable final String username) {
		this.username = username;
		return this;
	}

	/**
	 * @see MailerRegularBuilder#withSMTPServerPassword(String)
	 */
	@Override
	public MailerRegularBuilderImpl withSMTPServerPassword(@Nullable final String password) {
		this.password = password;
		return this;
	}

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryClass(String)
	 */
	@Override
	public MailerRegularBuilderImpl withCustomSSLFactoryClass(@Nullable final String customSSLFactory) {
		this.customSSLFactory = customSSLFactory;
		return this;
	}

	/**
	 * @see MailerRegularBuilder#withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	@Override
	public MailerRegularBuilderImpl withCustomSSLFactoryInstance(@Nullable final SSLSocketFactory customSSLFactoryInstance) {
		this.customSSLFactoryInstance = customSSLFactoryInstance;
		return this;
	}

	/**
	 * @see MailerRegularBuilder#buildMailer()
	 */
	@Override
	public Mailer buildMailer() {
		return new MailerImpl(this);
	}

	/**
	 * For internal use.
	 */
	@Nullable
	ServerConfig buildServerConfig() {
		if (!isTransportModeLoggingOnly() && getCustomMailer() == null) {
			checkArgumentNotEmpty(host, "SMTP server host missing");
			final int serverPort = ofNullable(port).orElse(transportStrategy.getDefaultServerPort());
			return new ServerConfigImpl(verifyNonnullOrEmpty(getHost()), serverPort, username, password, customSSLFactory, customSSLFactoryInstance);
		} else if (getCustomMailer() != null && host != null) {
			log.warn("Both custom mailer and SMTP server configured, ignoring server configuration");
		}
		return null;
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
}
