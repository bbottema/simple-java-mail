package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.SimpleOptional;

import javax.net.ssl.SSLSocketFactory;

import static org.simplejavamail.config.ConfigLoader.Property.CUSTOM_SSLFACTORY_CLASS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.TRANSPORT_STRATEGY;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

/**
 * @see MailerRegularBuilder
 */
public class MailerRegularBuilderImpl extends MailerGenericBuilderImpl<MailerRegularBuilderImpl> implements MailerRegularBuilder<MailerRegularBuilderImpl> {
	
	/**
	 * @see #withSMTPServerHost(String)
	 */
	private String host;
	
	/**
	 * @see #withSMTPServerPort(Integer)
	 */
	private Integer port;
	
	/**
	 * @see #withSMTPServerUsername(String)
	 */
	private String username;
	
	/**
	 * @see #withSMTPServerPassword(String)
	 */
	private String password;
	
	/**
	 * @see #withTransportStrategy(TransportStrategy)
	 */
	@NotNull
	private TransportStrategy transportStrategy;

	/**
	 * @see #withCustomSSLFactoryClass(String)
	 */
	private String customSSLFactory;

	/**
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	private SSLSocketFactory customSSLFactoryInstance;

	/**
	 * Sets defaults configured for SMTP host, SMTP port, SMTP username, SMTP password and transport strategy.
	 * <p>
	 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
	 *
	 * @deprecated Used internally. Don't use this. Use one of the static {@link org.simplejavamail.mailer.MailerBuilder} methods instead.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public MailerRegularBuilderImpl() {
		if (hasProperty(SMTP_HOST)) {
			withSMTPServerHost(assumeNonNull(ConfigLoader.getStringProperty(SMTP_HOST)));
		}
		if (hasProperty(SMTP_PORT)) {
			withSMTPServerPort(assumeNonNull(ConfigLoader.getIntegerProperty(SMTP_PORT)));
		}
		if (hasProperty(SMTP_USERNAME)) {
			withSMTPServerUsername(assumeNonNull(ConfigLoader.getStringProperty(SMTP_USERNAME)));
		}
		if (hasProperty(SMTP_PASSWORD)) {
			withSMTPServerPassword(assumeNonNull(ConfigLoader.getStringProperty(SMTP_PASSWORD)));
		}
		this.transportStrategy = TransportStrategy.SMTP;
		if (hasProperty(TRANSPORT_STRATEGY)) {
			withTransportStrategy(assumeNonNull(ConfigLoader.<TransportStrategy>getProperty(TRANSPORT_STRATEGY)));
		}
		if (hasProperty(CUSTOM_SSLFACTORY_CLASS)) {
			withCustomSSLFactoryClass(assumeNonNull(ConfigLoader.getStringProperty(CUSTOM_SSLFACTORY_CLASS)));
		}
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
				.withSMTPServerUsername(username)
				.withSMTPServerPassword(password);
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
	ServerConfig buildServerConfig() {
		vallidateServerConfig();
		final int serverPort = SimpleOptional.ofNullable(port).orElse(transportStrategy.getDefaultServerPort());
		return new ServerConfigImpl(assumeNonNull(getHost()), serverPort, username, password, customSSLFactory, customSSLFactoryInstance);
	}

	private void vallidateServerConfig() {
		checkArgumentNotEmpty(host, "SMTP server host missing");
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
