package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import javax.net.ssl.SSLSocketFactory;

/**
 * Default builder for generating Mailer instances. Sets defaults configured for SMTP host, SMTP port, SMTP username, SMTP password and transport
 * strategy.
 * <p>
 * Obtain this builder from {@code SimpleJavaMail.mailerBuilder()}. Explicit calls on the returned builder override values from that factory's immutable
 * configuration snapshot.
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
	 * Controls whether plain {@link TransportStrategy#SMTP} attempts an optional STARTTLS upgrade when the server offers it. It defaults to {@code true};
	 * setting it to {@code false} is an escape hatch for SMTP servers that break during STARTTLS negotiation.
	 * <p>
	 * A configured snapshot supplies the initial value when this builder is created. Calling this method afterwards overrides that value for the Mailer
	 * built from this builder.
	 * <p>
	 * This setting is deliberately limited to plain SMTP. It has no effect on mandatory TLS in {@link TransportStrategy#SMTP_TLS}, OAuth2 transport, or
	 * implicit TLS in {@link TransportStrategy#SMTPS}.
	 *
	 * @param opportunisticTLS Whether plain SMTP should attempt STARTTLS when offered.
	 */
	T withOpportunisticTLS(boolean opportunisticTLS);

	/**
	 * Selects how the Mailer connects and whether TLS is optional or required. See {@link TransportStrategy} for the protocols,
	 * generated properties and security implications.
	 * <p>
	 * <strong>Note:</strong> if no server port has been set, a default will be taken based on the transport strategy, since every different
	 * connection type uses a different default port.
	 * <p>
	 * Supplying credentials does not change the strategy. Choose {@link TransportStrategy#SMTP_TLS} when password authentication must require TLS;
	 * plain {@link TransportStrategy#SMTP} remains opportunistic. See {@link #buildMailer()} for validation of conflicting extra properties.
	 *
	 * @param transportStrategy The name of the transport strategy to use: {@link TransportStrategy#SMTP}, {@link TransportStrategy#SMTPS},
	 *                                {@link TransportStrategy#SMTP_TLS}, or {@link TransportStrategy#SMTP_OAUTH2}. Defaults to {@link TransportStrategy#SMTP}.
	 */
	T withTransportStrategy(@NotNull TransportStrategy transportStrategy);

	/**
	 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)}, {@link #withSMTPServerUsername(String)} and {@link
	 * #withSMTPServerPassword(String)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 * @param username Optional SMTP server username that defaults to pre-configured property if left empty.
	 * @param password Optional SMTP server password or OAUTH2 token (in case of TransportStrategy.SMTP_OAUTH2)
	 *                    that defaults to pre-configured property if left empty.
	 */
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port, @Nullable @Cli.Optional String username, @Nullable @Cli.Optional String password);

	/**
	 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)} and {@link #withSMTPServerUsername(String)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 * @param username Optional username that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port, @Nullable @Cli.Optional String username);
	
	/**
	 * Delegates to {@link #withSMTPServerHost(String)} and {@link #withSMTPServerPort(Integer)}.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port);
	
	/**
	 * Sets the optional SMTP host. Will default to pre-configured property if left empty.
	 *
	 * @param host Optional host that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerHost(@Nullable @Cli.Optional String host);
	
	/**
	 * Sets the optional SMTP port. Will default to pre-configured property if not overridden. If left empty,
	 * the default will be determined based on the transport strategy.
	 *
	 * @param port Optional port number that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPort(@Nullable @Cli.Optional Integer port);
	
	/**
	 * Sets the optional SMTP username. Will default to pre-configured property if left empty.
	 *
	 * @param username Optional username that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerUsername(@Nullable @Cli.Optional String username);

	/**
	 * Sets the optional SMTP password or OAUTH2 token (in case of TransportStrategy.SMTP_OAUTH2). Will default to pre-configured property if left empty.
	 *
	 * @param password Optional password or OAUTH2 token that defaults to pre-configured property if left empty.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPassword(@Nullable @Cli.Optional String password);

	/**
	 * Uses your SSL socket factory class, for example to supply a client certificate or a private trust store.
	 * <p>
	 * A supplied factory instance takes precedence. The class needs a public static {@code getDefault()} method returning an
	 * {@link SSLSocketFactory}; initialization stays lazy until the factory is used. For a modular application using the managed Angus
	 * provider, export its package to {@code org.simplejavamail.mailprovider.angus}, or supply an instance instead.
	 * <p>
	 * On an owned Angus Session, a failure in your factory stops connection setup by default instead of retrying with the default factory.
	 * An explicit {@code mail.smtp.socketFactory.fallback=true} (or the {@code mail.smtps} equivalent) retains that advanced behavior,
	 * but can abandon your factory's client identity or trust decisions. Failed TLS wrapping closes its connected socket before cleanup or fallback.
	 * This does not add physical cancellation support to a custom factory. Caller-owned Sessions keep their own settings.
	 * <p>
	 * Sets {@code mail.smtp.ssl.socketFactory.class} or {@code mail.smtps.ssl.socketFactory.class}. A custom factory can bypass the provider's
	 * SOCKS socket creation, so check authenticated-proxy usage with your factory.
	 *
	 * @param factoryClass The fully qualified name of the factory class. Example: <code>javax.net.ssl.SSLSocketFactory</code>
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	T withCustomSSLFactoryClass(@Nullable @Cli.Optional String factoryClass);

	/**
	 * Uses your SSL socket factory instance, for example to supply a client certificate or a private trust store.
	 * <p>
	 * Takes precedence over a configured factory class. No reflective module access to your implementation package is needed.
	 * For an owned Angus Session, omitted fallback is disabled: failure in your factory stops connection setup rather than silently
	 * trying the default factory. Explicit transport-specific {@code socketFactory.fallback=true} remains available for deliberate fallback.
	 * Failed TLS wrapping closes the connected socket, retaining the factory failure and suppressing any secondary close failure.
	 * The owned Session holds a cleanup decorator; keep your original reference for implementation-specific factory operations.
	 * <p>
	 * Sets {@code mail.smtp.ssl.socketFactory} or {@code mail.smtps.ssl.socketFactory}. A custom factory can bypass the provider's SOCKS
	 * socket creation, so check authenticated-proxy usage with your factory. This does not add physical cancellation support.
	 * Caller-owned Sessions keep their own settings.
	 *
	 * @param sslSocketFactoryInstance An instance of the {@link SSLSocketFactory} class.
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactoryClass(String)
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	T withCustomSSLFactoryInstance(@Nullable @Cli.Optional SSLSocketFactory sslSocketFactoryInstance);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * Values not set directly on this builder keep the immutable configuration snapshot captured when the builder was requested from its configured
	 * factory. Later configuration loads do not change the resulting Mailer.
	 * <p>
	 * When Simple Java Mail creates a Session with the standard Angus provider, UTF-8 encoding is on by default
	 * ({@code mail.mime.allowutf8=true}). That lets you send addresses such as {@code josé@example.org} when the server supports them, without
	 * making ordinary mail depend on SMTPUTF8. An address such as {@code José <jose@example.org>} still uses MIME encoding for the display name.
	 * <p>
	 * The managed transport checks what each email actually needs and won't send it if the server doesn't advertise support. If you've verified
	 * that a legacy server and its onward delivery route can handle the unchanged email anyway, you can opt in with {@link #withLegacySmtpContentSupport(boolean)}.
	 * <p>
	 * If you want UTF-8 encoding disabled, use {@code withProperty("mail.mime.allowutf8", false)}. If you supply your own Session, we leave its settings alone.
	 * <p>
	 * For {@link TransportStrategy#SMTP_TLS} and {@link TransportStrategy#SMTP_OAUTH2}, an extra {@code mail.smtp.starttls.required} property
	 * must be {@link Boolean#TRUE} or a case-insensitive {@code "true"} string, without surrounding whitespace. Omit it to use the strategy default.
	 * Other values fail construction before proxy, pool or send-operation setup, including in logging-only mode. Remove the conflicting override
	 * to keep mandatory TLS. For opportunistic username/password authentication, choose {@link TransportStrategy#SMTP} instead;
	 * do not switch access tokens to the password strategy as a workaround.
	 * <p>
	 * This check applies to Sessions created by this builder, unless a {@link CustomMailer} owns the transport. Caller-supplied Sessions are not checked.
	 * It validates this particular override at construction time; it does not freeze the Session, validate custom providers or socket factories, or
	 * change certificate-trust and server-identity settings.
	 *
	 * @throws org.simplejavamail.MailException If an extra property disables mandatory STARTTLS on a library-owned SMTP transport.
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
	 * @see #withCustomSSLFactoryClass(String)
	 */
	@Nullable
	String getCustomSSLFactory();
}
