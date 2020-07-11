package org.simplejavamail.api.mailer;

import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.Session;
import javax.net.ssl.SSLSocketFactory;

/**
 * Default builder for generating Mailer instances. Sets defaults configured for SMTP host, SMTP port, SMTP username, SMTP password and transport
 * strategy.
 * <p>
 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
 * <p>
 * In addition on generic Mailer setting, this builder is used to configure SMTP server details and transport strategy needed to produce a valid
 * {@link Session} instance.
 *
 * @see TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerRegularBuilder<T extends MailerRegularBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * To learn more about the various transport modes, the properties they set and the security
	 * implications, please refer to the full TransportStrategy<br>
	 * <a href="www.javadoc.io/page/org.simplejavamail/simple-java-mail/latest/org/simplejavamail/mailer/config/TransportStrategy.html">javadoc</a>.
	 * <p>
	 * <strong>Note:</strong> if no server port has been set, a default will be taken based on the transport strategy, since every different
	 * connection type uses a different default port.
	 *
	 * @param transportStrategy The name of the transport strategy to use: {@link TransportStrategy#SMTP}, {@link TransportStrategy#SMTPS} or
	 *                                {@link TransportStrategy#SMTP_TLS}. Defaults to {@link TransportStrategy#SMTP}.
	 */
	T withTransportStrategy(@NotNull TransportStrategy transportStrategy);
	
	/**
	 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)}, {@link #withSMTPServerUsername(String)} and {@link
	 * #withSMTPServerPassword(String)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 * @param username Optional username that defaults to pre-configured property if left empty.
	 * @param password Optional password that defaults to pre-configured property if left empty.
	 */
	T withSMTPServer(@Nullable String host, @Nullable Integer port, @Nullable String username, @Nullable String password);
	
	/**
	 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)} and {@link #withSMTPServerUsername(String)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 * @param username Optional username that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable String host, @Nullable Integer port, @Nullable String username);
	
	/**
	 * Delegates to {@link #withSMTPServerHost(String)} and {@link #withSMTPServerPort(Integer)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable String host, @Nullable Integer port);
	
	/**
	 * Sets the optional SMTP host. Will default to pre-configured property if left empty.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerHost(@Nullable String host);
	
	/**
	 * Sets the optional SMTP port. Will default to pre-configured property if not overridden. If left empty,
	 * the default will be determined based on the transport strategy.
	 *
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPort(@Nullable Integer port);
	
	/**
	 * Sets the optional SMTP username. Will default to pre-configured property if left empty.
	 *
	 * @param username Optional username that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerUsername(@Nullable String username);

	/**
	 * Sets the optional SMTP password. Will default to pre-configured property if left empty.
	 *
	 * @param password Optional password that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPassword(@Nullable String password);

	/**
	 * Configures the session with the right property to use your own factory for obtaining SSL connections.
	 * <p>
	 * <strong>Note 1:</strong> Is overridden by custom factory instance if set.
	 * <p>
	 * <strong>Note 2:</strong> Sets the property <code>mail.smtp.ssl.socketFactory.class</code> on the Session.
	 * <br>
	 * <strong>Note 3:</strong> This breaks your setup if you also use authenticated proxy.
	 *
	 * @param factoryClass The fully qualified name of the factory class. Example: <code>javax.net.ssl.SSLSocketFactory</code>
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	T withCustomSSLFactory(@Nullable String factoryClass);

	/**
	 * Configures the session with the right property to use your own factory for obtaining SSL connections.
	 * <p>
	 * <strong>Note 1:</strong> Overrides custom factory class if set.
	 * <p>
	 * <strong>Note 2:</strong> Sets the property <code>mail.smtp.ssl.socketFactory</code> on the Session.
	 * <br>
	 * <strong>Note 3:</strong> This breaks your setup if you also use authenticated proxy.
	 *
	 * @param sslSocketFactoryInstance An instance of the {@link SSLSocketFactory} class.
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactory(String)
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	T withCustomSSLFactoryInstance(@Nullable SSLSocketFactory sslSocketFactoryInstance);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * For all configurable values: if omitted, a default value will be attempted by looking at property files or manually defined defauls.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #withSMTPServerHost(String)
	 */
	@Nullable
	String getHost();
	
	/**
	 * @see #withSMTPServerPort(Integer)
	 */
	@Nullable
	Integer getPort();
	
	/**
	 * @see #withSMTPServerUsername(String)
	 */
	@Nullable
	String getUsername();
	
	/**
	 * @see #withSMTPServerPassword(String)
	 */
	@Nullable
	String getPassword();

	/**
	 * @see #withTransportStrategy(TransportStrategy)
	 */
	@Nullable
	TransportStrategy getTransportStrategy();

	/**
	 * @see #withCustomSSLFactory(String)
	 */
	@Nullable
	String getCustomSSLFactory();
}