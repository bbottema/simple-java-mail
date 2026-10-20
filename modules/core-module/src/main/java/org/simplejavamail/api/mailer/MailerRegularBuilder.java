package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.TransportStrategy;

/**
 * Configure an SMTP connection and build a Mailer with {@code SimpleJavaMail.fromDefaults().mailerBuilder()}.
 * If you have a configured SimpleJavaMail factory, call its {@code mailerBuilder()} instead to use its immutable configuration snapshot.
 * <p>
 * The factory supplies the initial SMTP host, port, username, password and transport strategy. Builder calls can replace these ordinary configured
 * values; factory-scoped locked settings still apply. This builder also offers the generic Mailer settings and creates the {@link Session} needed
 * for the selected connection.
 *
 * @see TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerRegularBuilder<T extends MailerRegularBuilder<?>> extends MailerGenericBuilder<T> {

	/**
	 * Selects how the Mailer connects and whether TLS is optional or required. See {@link TransportStrategy} for the protocols,
	 * generated properties and security implications.
	 * <p>
	 * <strong>Note:</strong> if no server port has been set, a default will be taken based on the transport strategy, since every different
	 * connection type uses a different default port.
	 * <p>
	 * Supplying credentials does not change the strategy. Choose {@link TransportStrategy#SMTP_TLS} when password authentication must require TLS;
	 * plain {@link TransportStrategy#SMTP} remains opportunistic. See {@link org.simplejavamail.api.mailer.MailerRegularBuilder#buildMailer()} for validation of conflicting extra properties.
	 * The OAuth2 presets use a fixed token in the password position or the runtime provider configured through
	 * {@link #withOAuth2AccessTokenProvider(org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider)}.
	 *
	 * @param transportStrategy The name of the transport strategy to use: {@link TransportStrategy#SMTP}, {@link TransportStrategy#SMTPS},
	 *                                {@link TransportStrategy#SMTP_TLS}, {@link TransportStrategy#SMTP_OAUTH2}, or
	 *                                {@link TransportStrategy#SMTPS_OAUTH2}. Defaults to {@link TransportStrategy#SMTP}.
	 */
	T withTransportStrategy(@NotNull TransportStrategy transportStrategy);

	/**
	 * Replaces the host, port, username and password together, including values initially loaded from properties.
	 * A null argument clears that field; it does not restore a captured property. Empty username and password strings also clear their fields.
	 * A cleared port uses the selected transport strategy's default when building; an ordinary SMTP Mailer still needs a host.
	 * <p>
	 * Shorter overloads leave the fields they do not accept unchanged. For a host-only change that retains the configured port and credentials, use
	 * {@link #withSMTPServerHost(String)}. To remove stored credentials explicitly, pass null for both username and password here, or call their
	 * named clear methods. Runtime credential providers remain separate. All supplied values and clear operations remain subject to configuration locks.
	 *
	 * @param host SMTP host to use, or null to remove it.
	 * @param port SMTP port to use, or null to use the transport strategy's default.
	 * @param username SMTP username to use, or null/empty to remove it.
	 * @param password SMTP password or OAuth2 token to use, or null/empty to remove it.
	 */
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port,
			@Nullable @Cli.Optional String username, @Nullable @Cli.Optional String password);

	/**
	 * Replaces the host, port and username, leaving the existing password unchanged.
	 * Null arguments clear the corresponding fields rather than restoring captured properties; a null port selects the transport strategy's default.
	 * Unlike the four-argument overload, this overload passes an empty username through unchanged. To change or remove credentials together,
	 * use {@link #withSMTPServer(String, Integer, String, String)}.
	 *
	 * @param host SMTP host to use, or null to remove it.
	 * @param port SMTP port to use, or null to use the transport strategy's default.
	 * @param username SMTP username to use, or null to remove it without removing the password.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port, @Nullable @Cli.Optional String username);
	
	/**
	 * Replaces the host and port, leaving the existing username and password unchanged.
	 * Null arguments clear those fields rather than restoring captured properties; a null port selects the transport strategy's default.
	 * See {@link #withSMTPServer(String, Integer, String, String)} when credentials should change with the destination.
	 *
	 * @param host SMTP host to use, or null to remove it.
	 * @param port SMTP port to use, or null to use the transport strategy's default.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServer(@Nullable @Cli.Optional String host, @Nullable @Cli.Optional Integer port);
	
	/**
	 * Changes only the SMTP host, retaining the configured port and credentials.
	 * Passing null or calling {@link #clearSMTPServerHost()} removes the host; it does not restore a captured property.
	 * An ordinary SMTP Mailer needs a host when built. Configuration locks still apply, and previously built Mailers remain unchanged.
	 *
	 * @param host SMTP host to use, or null to remove it.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerHost(@Nullable @Cli.Optional String host);

	/**
	 * Removes the configured SMTP host without restoring a property value.
	 * @see #withSMTPServerHost(String)
	 */
	T clearSMTPServerHost();
	
	/**
	 * Changes only the SMTP port. Passing null or calling {@link #clearSMTPServerPort()} removes the configured value so the selected transport strategy
	 * supplies its default port when building. This does not restore a captured property or change any other SMTP setting. Configuration locks still apply.
	 *
	 * @param port SMTP port to use, or null to use the transport strategy's default.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPort(@Nullable @Cli.Optional Integer port);

	/**
	 * Removes the configured port so the transport strategy supplies its default.
	 * @see #withSMTPServerPort(Integer)
	 */
	T clearSMTPServerPort();
	
	/**
	 * Changes only the SMTP username, leaving the password unchanged. Passing null or calling {@link #clearSMTPServerUsername()} removes the username,
	 * not its password or runtime token provider. Remove both stored credentials when neither should be used; a password without a username is invalid.
	 * This does not restore a captured property or normalize an empty string. Configuration locks still apply.
	 *
	 * @param username SMTP username to use, or null to remove it.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerUsername(@Nullable @Cli.Optional String username);

	/**
	 * Removes only the stored SMTP username.
	 * @see #withSMTPServerUsername(String)
	 */
	T clearSMTPServerUsername();

	/**
	 * Changes only the SMTP password or fixed OAuth2 token. Passing null or calling {@link #clearSMTPServerPassword()} removes that stored value,
	 * leaving the username and any runtime token provider unchanged. This does not restore a captured property or normalize an empty string.
	 * Use {@link #clearOAuth2AccessTokenProvider()} separately if a runtime token provider should also be removed. Configuration locks still apply.
	 *
	 * @param password SMTP password or fixed OAuth2 token to use, or null to remove it.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of another API method")
	T withSMTPServerPassword(@Nullable @Cli.Optional String password);

	/**
	 * Removes only the stored SMTP password or fixed OAuth2 token.
	 * @see #withSMTPServerPassword(String)
	 */
	T clearSMTPServerPassword();

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
	 * that a legacy server and its onward delivery route can handle the unchanged email anyway, you can opt in with {@link org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withLegacySmtpContentSupport(boolean)}.
	 * <p>
	 * If you want UTF-8 encoding disabled, use {@code withProperty("mail.mime.allowutf8", false)}. If you supply your own Session, we leave its settings alone.
	 * <p>
	 * For {@link TransportStrategy#SMTP_TLS} and {@link TransportStrategy#SMTP_OAUTH2}, an extra {@code mail.smtp.starttls.required} property
	 * must be {@link Boolean#TRUE} or a case-insensitive {@code "true"} string, without surrounding whitespace. Omit it to use the strategy default.
	 * Other values fail construction before proxy, pool or send-operation setup, including in logging-only mode. Remove the conflicting override
	 * to keep mandatory TLS. For opportunistic username/password authentication, choose {@link TransportStrategy#SMTP} instead;
	 * do not switch access tokens to the password strategy as a workaround.
	 * <p>
	 * For {@link TransportStrategy#SMTPS_OAUTH2}, an extra {@code mail.transport.protocol} must retain {@code smtps}.
	 * A contradictory protocol fails construction before resource setup rather than sending an access token through another transport.
	 * <p>
	 * This check applies to Sessions created by this builder, unless a {@link CustomMailer} owns the transport. Caller-supplied Sessions are not checked.
	 * It validates this particular override at construction time; it does not freeze the Session, validate custom providers or socket factories, or
	 * change certificate-trust and server-identity settings.
	 *
	 * @throws org.simplejavamail.MailException If an extra property contradicts mandatory TLS on a library-owned SMTP transport.
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

}
