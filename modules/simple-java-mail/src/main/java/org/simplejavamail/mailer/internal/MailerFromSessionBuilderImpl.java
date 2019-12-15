package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;

import org.jetbrains.annotations.NotNull;
import javax.mail.Session;

/**
 * @see MailerFromSessionBuilder
 */
public class MailerFromSessionBuilderImpl
		extends MailerGenericBuilderImpl<MailerFromSessionBuilderImpl>
		implements MailerFromSessionBuilder<MailerFromSessionBuilderImpl> {
	
	/**
	 * @see #usingSession(Session)
	 */
	private Session session;
	
	/**
	 * @deprecated Used internally. Don't use this. Instead use {@link org.simplejavamail.mailer.MailerBuilder#usingSession(Session)}.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public MailerFromSessionBuilderImpl() {
	}
	
	/**
	 * @see MailerFromSessionBuilder#usingSession(Session)
	 */
	@Override
	public MailerFromSessionBuilderImpl usingSession(@NotNull final Session session) {
		this.session = session;
		return this;
	}
	
	/**
	 * @see MailerFromSessionBuilder#buildMailer()
	 */
	@Override
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	public MailerImpl buildMailer() {
		return new MailerImpl(this);
	}
	
	/**
	 * @see MailerFromSessionBuilder#getSession()
	 */
	@Override
	public Session getSession() {
		return session;
	}
}