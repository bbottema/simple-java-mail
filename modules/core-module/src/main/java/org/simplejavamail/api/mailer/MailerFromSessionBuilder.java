package org.simplejavamail.api.mailer;

import jakarta.mail.Message;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

/**
 * Intermediate builder interface that supports a caller-supplied {@link Session}. Allows configuring generic Mailer settings, but not SMTP server or transport
 * strategy details.
 * <p>
 * Obtain this builder by passing the Session to {@code SimpleJavaMail.mailerBuilder(Session)}.
 * <p>
 * <strong>Note:</strong> SMTP server and transport properties are presumed to be present in the supplied {@link Session}. Proxy routing is the deliberate exception:
 * configuring a proxy through this builder overwrites the supported {@code mail.smtp.socks.host} and {@code mail.smtp.socks.port} properties.
 *
 * @see org.simplejavamail.api.mailer.config.TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerFromSessionBuilder<T extends MailerFromSessionBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. It assumes that all protocol-specific connection properties are
	 * configured, including host, port, authentication, transport protocol and any custom SSL factory.
	 * <p>
	 * Calling {@link #withProxy(String, Integer)} or {@link #withProxy(String, Integer, String, String)} is the deliberate exception: Simple Java Mail
	 * updates the supported {@code mail.smtp.socks.*} route, while leaving the remaining connection configuration under the caller's control.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	T usingSession(@NotNull Session session);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * Generic values not set directly on this builder keep the immutable configuration snapshot captured when the builder was requested from its
	 * configured factory. SMTP connection and transport values remain owned by the supplied {@link Session}, as documented by {@link #usingSession(Session)}.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #usingSession(Session)
	 */
	Session getSession();
}
