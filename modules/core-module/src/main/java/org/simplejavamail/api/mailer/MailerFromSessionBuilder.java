package org.simplejavamail.api.mailer;

import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import javax.annotation.Nonnull;
import javax.mail.Message;
import javax.mail.Session;

/**
 * Builder that supports a fixed {@link Session} instance. Allows configuring all generic Mailer settings, but not SMTP and transport strategy
 * details.
 * <p>
 * <strong>Note:</strong> Any SMTP server properties that can be set on the {@link Session} object by are presumed to be already present in the passed
 * {@link Session} instance.
 *
 * @see org.simplejavamail.api.mailer.config.TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerFromSessionBuilder<T extends MailerFromSessionBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. Assumes that all properties (except session timeout) used to make
	 * a connection are configured (host, port, authentication and transport protocol settings).
	 * <p>
	 * Only proxy can be configured optionally and general connection settings.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	T usingSession(@Nonnull Session session);
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * For all configurable values: if omitted, a default value will be attempted by looking at property files or manually defined defauls.
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #usingSession(Session)
	 */
	Session getSession();
}
