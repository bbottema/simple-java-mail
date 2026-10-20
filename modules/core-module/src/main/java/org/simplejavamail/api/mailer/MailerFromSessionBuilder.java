package org.simplejavamail.api.mailer;

import jakarta.mail.Message;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

/**
 * Build a Mailer around your own {@link Session} with {@code SimpleJavaMail.fromDefaults().mailerBuilder(session)}.
 * If you have a configured SimpleJavaMail factory, call its {@code mailerBuilder(session)} instead to retain that factory's immutable configuration
 * snapshot. This builder offers generic Mailer settings; supply the SMTP server and transport strategy on the Session itself.
 * <p>
 * Supply the SMTP server, authentication and transport settings on the Session. Its other connection settings are the baseline too: ordinary SJM
 * timeout, local-binding, EHLO and certificate-trust defaults do not replace them. Calling a generic connection setter explicitly applies that choice,
 * using the Session's {@code smtp} or {@code smtps} protocol. For a custom protocol, use the provider's own property names instead.
 * Additional Jakarta Mail properties still overlay the supplied Session. Proxy routing deliberately replaces the supported SOCKS host/port properties
 * when configured through this builder.
 * <p>
 * Factory settings supplied through {@code simplejavamail.locked.} properties must also be honored. A locked SMTP username or password cannot be checked
 * reliably on a caller-owned Session: its cached credentials or Authenticator may choose another account even when its visible properties match.
 * Use the factory's {@code mailerBuilder()} without supplying a Session, or remove those credential locks from its configuration source. Other locks
 * are checked against the supplied Session where possible; incompatible settings or opaque TLS handling are rejected before connecting.
 *
 * @see org.simplejavamail.api.mailer.config.TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerFromSessionBuilder<T extends MailerFromSessionBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. It assumes that all protocol-specific connection properties are
	 * configured, including host, port, authentication, transport protocol and any custom SSL factory.
	 * <p>
	 * Explicit generic connection setters, such as {@link org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withSessionTimeout(java.time.Duration)}, replace the corresponding supplied settings.
	 * Leaving those setters unused preserves the Session baseline rather than applying ordinary SJM connection defaults.
	 * Configured additional Jakarta Mail properties still apply. Supplying endpoint and optional authentication choices through
	 * {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withProxyConfig(org.simplejavamail.api.mailer.config.SocksProxyConfig)} updates the supported SOCKS route too.
	 * Clearing a connection setting inherited through {@link java.util.Properties} defaults is rejected if removing it would simply reactivate
	 * that default. Use a new Session with independent Properties containing only the desired settings, or supply an explicit replacement value.
	 * <p>
	 * One Mailer at a time may use the Session and its {@link java.util.Properties} object. Building another Mailer with either is rejected before
	 * changing the first Mailer's configuration, even if the Mailers belong to different factories. Reuse the existing Mailer for concurrent sends,
	 * or create another Session with independent Properties copied from your original SMTP configuration, not from a Session already used by a Mailer.
	 * A second Session that shares the first Session's Properties is not independent. Prefer {@link Session#getInstance(java.util.Properties)} over
	 * {@link Session#getDefaultInstance(java.util.Properties)}, which can return an existing Session.
	 * <p>
	 * The Session becomes available for another Mailer after successful, completed shutdown of its previous owner. Starting shutdown is not enough:
	 * wait for {@link Mailer#close()} or the future returned by {@link Mailer#closeAsync()}. If cleanup fails, use a fresh Session.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	T usingSession(@NotNull Session session);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * Email governance and execution settings use the factory's captured configuration and your builder customizations. Connection settings start
	 * with the supplied Session instead of ordinary SJM connection defaults; explicit connection setters, additional Jakarta Mail properties and
	 * mandatory locks still apply, as documented by {@link #usingSession(Session)}.
	 *
	 * @throws IllegalArgumentException when the Session or its Properties already belongs to another Mailer
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #usingSession(Session)
	 */
	Session getSession();
}
