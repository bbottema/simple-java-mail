package org.simplejavamail.mailer;

import org.simplejavamail.internal.clisupport.CliSupported;

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
 * @see org.simplejavamail.mailer.config.TransportStrategy
 */
@CliSupported(paramPrefix = "mailer")
public class MailerFromSessionBuilder extends MailerGenericBuilder<MailerFromSessionBuilder> {
	
	/**
	 * @see #usingSession(Session)
	 */
	private Session session;
	
	/**
	 * Only use this API if you <em>must</em> use your own {@link Session} instance. Assumes that all properties (except session timeout) used to make
	 * a connection are configured (host, port, authentication and transport protocol settings).
	 * <p>
	 * Only proxy can be configured optionally and general connection settings.
	 *
	 * @param session A mostly preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 */
	public MailerFromSessionBuilder usingSession(@Nonnull final Session session) {
		this.session = session;
		return this;
	}
	
	/**
	 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
	 * <p>
	 * For all configurable values: if omitted, a default value will be attempted by looking at property files or manually defined defauls.
	 */
	public Mailer buildMailer() {
		return new Mailer(this);
	}
	
	/**
	 * @see #usingSession(Session)
	 */
	public Session getSession() {
		return session;
	}
}