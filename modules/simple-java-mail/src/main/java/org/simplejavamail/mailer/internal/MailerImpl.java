package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.mailer.MailerHelper;
import org.simplejavamail.mailer.internal.mailsender.MailSenderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.EnumSet;
import java.util.Properties;

import static org.simplejavamail.api.mailer.config.TransportStrategy.findStrategyForSession;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see Mailer
 */
public class MailerImpl implements Mailer {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MailerImpl.class);
	
	private final MailSender mailSender;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@Nonnull
	private final EnumSet<EmailAddressCriteria> emailAddressCriteria;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 */
	@Nullable
	private final TransportStrategy transportStrategy;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withSMTPServer(String, Integer, String, String)
	 */
	@Nullable
	private final ServerConfig serverConfig;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withProxy(String, Integer, String, String)
	 */
	@Nonnull
	private final ProxyConfig proxyConfig;
	
	MailerImpl(@Nonnull final MailerFromSessionBuilderImpl fromSessionBuilder) {
		this.serverConfig = null;
		this.transportStrategy = null;
		this.emailAddressCriteria = fromSessionBuilder.getEmailAddressCriteria();
		this.proxyConfig = fromSessionBuilder.buildProxyConfig();
		final Session session = fromSessionBuilder.getSession();
		TransportStrategy strategyInUse = findStrategyForSession(checkNonEmptyArgument(session, "session"));
		this.mailSender = initFromGenericBuilder(strategyInUse, proxyConfig, session, fromSessionBuilder);
	}
	
	MailerImpl(@Nonnull final MailerRegularBuilderImpl regularBuilder) {
		this.serverConfig = regularBuilder.buildServerConfig();
		this.transportStrategy = regularBuilder.getTransportStrategy();
		this.emailAddressCriteria = regularBuilder.getEmailAddressCriteria();
		this.proxyConfig = regularBuilder.buildProxyConfig();
		final Session session = createMailSession(serverConfig, transportStrategy);
		this.mailSender = initFromGenericBuilder(transportStrategy, proxyConfig, session, regularBuilder);
	}
	
	private MailSender initFromGenericBuilder(@Nullable TransportStrategy transportStrategy, @Nonnull ProxyConfig proxyConfig, @Nonnull Session session, @Nonnull final MailerGenericBuilderImpl<?> genericBuiler) {
		OperationalConfig operationalConfig = genericBuiler.buildOperationalConfig();
		return new MailSenderImpl(session, operationalConfig, proxyConfig, transportStrategy);
	}
	
	/**
	 * Instantiates and configures the {@link Session} instance. Delegates resolving transport protocol specific properties to the given {@link
	 * TransportStrategy} in two ways: <ol> <li>request an initial property list which the strategy may pre-populate</li> <li>by requesting the
	 * property names according to the respective transport protocol it handles (for the host property for example it would be
	 * <em>"mail.smtp.host"</em> for SMTP and <em>"mail.smtps.host"</em> for SMTPS)</li> </ol>
	 * <p>
	 * Furthermore adds proxy SOCKS properties if a proxy configuration was provided, overwriting any SOCKS properties already present.
	 *
	 * @param serverConfig      Remote SMTP server details.
	 * @param transportStrategy The transport protocol strategy enum that actually handles the session configuration. Session configuration meaning
	 *                          setting the right properties for the appropriate transport type (ie. <em>"mail.smtp.host"</em> for SMTP,
	 *                          <em>"mail.smtps.host"</em> for SMTPS).
	 *
	 * @return A fully configured <code>Session</code> instance complete with transport protocol settings.
	 * @see TransportStrategy#generateProperties()
	 * @see TransportStrategy#propertyNameHost()
	 * @see TransportStrategy#propertyNamePort()
	 * @see TransportStrategy#propertyNameUsername()
	 * @see TransportStrategy#propertyNameAuthenticate()
	 */
	@SuppressWarnings("WeakerAccess")
	public static Session createMailSession(@Nonnull final ServerConfig serverConfig, @Nonnull final TransportStrategy transportStrategy) {
		final Properties props = transportStrategy.generateProperties();
		props.put(transportStrategy.propertyNameHost(), serverConfig.getHost());
		props.put(transportStrategy.propertyNamePort(), String.valueOf(serverConfig.getPort()));
		
		if (serverConfig.getUsername() != null) {
			props.put(transportStrategy.propertyNameUsername(), serverConfig.getUsername());
		}
		
		if (serverConfig.getPassword() != null) {
			props.put(transportStrategy.propertyNameAuthenticate(), "true");
			return Session.getInstance(props, new SmtpAuthenticator(serverConfig));
		} else {
			return Session.getInstance(props);
		}
	}
	
	/**
	 * @see Mailer#testConnection()
	 */
	@Override
	public void testConnection() {
		this.testConnection(false);
	}

	/**
	 * @see Mailer#testConnection(boolean)
	 */
	@Override
	@Nullable
	public AsyncResponse testConnection(boolean async) {
		return mailSender.testConnection(async);
	}
	
	/**
	 * @see Mailer#sendMail(Email)
	 */
	@Override
	public final void sendMail(final Email email) {
		sendMail(email, getOperationalConfig().isAsync());
	}
	
	/**
	 * @see Mailer#sendMail(Email, boolean)
	 */
	@Override
	@Nullable
	public final synchronized AsyncResponse sendMail(final Email email, @SuppressWarnings("SameParameterValue") final boolean async) {
		if (validate(email)) {
			return mailSender.send(email, async);
		}
		throw new AssertionError("Email not valid, but no MailException was thrown for it");
	}

	/**
	 * @see Mailer#validate(Email)
	 */
	@Override
	@SuppressWarnings({"SameReturnValue"})
	public boolean validate(@Nonnull final Email email)
			throws MailException {
		return MailerHelper.validate(email, emailAddressCriteria);
	}

	/**
	 * @see Mailer#getSession()
	 */
	@Override
	public Session getSession() {
		LOGGER.warn("Providing access to Session instance for emergency fall-back scenario. Please let us know why you need it.");
		LOGGER.warn("\t\t> https://github.com/bbottema/simple-java-mail/issues");
		return mailSender.getSession();
	}

	/**
	 * @see Mailer#getServerConfig()
	 */
	@Override
	@Nullable
	public ServerConfig getServerConfig() {
		return this.serverConfig;
	}

	/**
	 * @see Mailer#getTransportStrategy()
	 */
	@Override
	@Nullable
	public TransportStrategy getTransportStrategy() {
		return this.transportStrategy;
	}

	/**
	 * @see Mailer#getProxyConfig()
	 */
	@Override
	@Nonnull
	public ProxyConfig getProxyConfig() {
		return this.proxyConfig;
	}

	/**
	 * @see Mailer#getOperationalConfig()
	 */
	@Override
	@Nonnull
	public OperationalConfig getOperationalConfig() {
		return mailSender.getOperationalConfig();
	}

	/**
	 * @see Mailer#getEmailAddressCriteria()
	 */
	@Override
	@Nonnull
	public EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}
}