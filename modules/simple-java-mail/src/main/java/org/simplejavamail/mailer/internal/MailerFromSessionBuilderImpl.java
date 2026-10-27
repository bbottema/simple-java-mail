package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.util.Properties;

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
	
	public MailerFromSessionBuilderImpl(@NotNull final SimpleJavaMailConfig config) {
		this(config, new FactorySendingLimits());
	}

	/** Internal factory wiring; caller-owned Sessions participate in the same factory-local allowance model. */
	public MailerFromSessionBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySendingLimits sendingLimits) {
		super(config, sendingLimits);
	}

	/** Internal factory wiring; named routes resolve within the captured factory snapshot. */
	public MailerFromSessionBuilderImpl(final SimpleJavaMailConfig config, final FactoryMailerRuntime runtime) {
		super(config, runtime);
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
		return buildConfiguredMailer();
	}

	@Override
	MailerImpl constructMailer(final ValidatedMailerPlan plan) {
		return MailerImpl.construct(plan);
	}
	
	/**
	 * @see MailerFromSessionBuilder#getSession()
	 */
	@Override
	public Session getSession() {
		return session;
	}

	@Override
	TransportStrategy transportStrategyForLocks() {
		final Properties effective = new Properties(session.getProperties());
		effective.putAll(getProperties());
		final TransportStrategy protocol = MailerSessionSettings.transportStrategy(effective);
		final TransportStrategy marked = TransportStrategy.findStrategyForSession(session);
		return protocol == TransportStrategy.SMTP && marked != null
				&& marked != TransportStrategy.SMTPS && marked != TransportStrategy.SMTPS_OAUTH2 ? marked : protocol;
	}
}
