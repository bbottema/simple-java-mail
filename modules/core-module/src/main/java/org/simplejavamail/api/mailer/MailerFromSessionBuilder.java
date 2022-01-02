package org.simplejavamail.api.mailer;

import jakarta.mail.Message;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

/**
 * Intermediate builder interface that supports a fixed {@link Session} instance. Allows configuring all generic Mailer settings, but not SMTP and transport strategy
 * details.
 * <p>
 * <strong>Note:</strong> To start creating a new Mailer, you use {@code MailerBuilder} directly instead.
 * <p>
 * <strong>Note:</strong> Any SMTP server properties that can be set on the {@link Session} object by are presumed to be already present in the past
 * {@link Session} instance.
 *
 * @see org.simplejavamail.api.mailer.config.TransportStrategy
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerFromSessionBuilder<T extends MailerFromSessionBuilder<?>> extends MailerGenericBuilder<T> {
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. Assumes that all properties (except session timeout) used to make
	 * a connection are configured (host, port, authentication and transport protocol settings and custom ssl factory if used).
	 * <p>
	 * Only proxy can be configured optionally and general connection settings.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	T usingSession(@NotNull Session session);
	
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
