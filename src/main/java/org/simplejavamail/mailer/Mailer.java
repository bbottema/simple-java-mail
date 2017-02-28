package org.simplejavamail.mailer;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;
import org.simplejavamail.MailException;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.mailer.config.ProxyConfig;
import org.simplejavamail.mailer.config.ServerConfig;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.mailsender.MailSender;
import org.simplejavamail.converter.internal.MimeMessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.EnumSet;
import java.util.Properties;

import static java.lang.String.format;
import static org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria.RFC_COMPLIANT;
import static org.simplejavamail.mailer.config.TransportStrategy.findStrategyForSession;
import static org.simplejavamail.util.ConfigLoader.*;
import static org.simplejavamail.util.ConfigLoader.Property.JAVAXMAIL_DEBUG;
import static org.simplejavamail.util.ConfigLoader.Property.TRANSPORT_STRATEGY;

/**
 * Mailing tool aimed for simplicity, for sending e-mails of any complexity. This includes e-mails with plain text and/or html content, embedded
 * images and separate attachments, SMTP, SMTPS / SSL and SMTP + SSL, custom Session object, DKIM domain signing and even authenticated SOCKS proxy
 * support and threaded batch processing.
 * <p>
 * This mailing tool abstracts the javax.mail API to a higher level easy to use API. This tool works with {@link Email} instances but can also convert
 * traditional {@link MimeMessage} objects to and from {@link Email} object.
 * <p>
 * The e-mail message structure is built to work with all e-mail clients and has been tested with many different webclients as well as some desktop
 * applications.
 * <p>
 * Technically, the resulting email structure is as follows:<br>
 * <p>
 * <pre>
 * - root
 * 	- related
 * 		- alternative
 * 			- mail text
 * 			- mail html text
 * 		- embedded images
 * 	- attachments
 * </pre>
 * <p/>
 * <br> Usage example:<br>
 * <p/>
 * <pre>
 * Email email = new Email();
 * email.setFromAddress(&quot;lollypop&quot;, &quot;lolly.pop@somemail.com&quot;);
 * email.addRecipient(&quot;Sugar Cane&quot;, &quot;sugar.cane@candystore.org&quot;, RecipientType.TO);
 * email.setText(&quot;We should meet up!!&quot;);
 * email.setTextHTML(&quot;&lt;b&gt;We should meet up!&lt;/b&gt;&quot;);
 * email.setSubject(&quot;Hey&quot;);
 * new Mailer(preconfiguredMailSession).sendMail(email);
 * // or:
 * new Mailer(&quot;smtp.someserver.com&quot;, 25, &quot;username&quot;, &quot;password&quot;).sendMail(email);
 * </pre>
 * <p>
 * <a href="http://www.simplejavamail.org">simplejavamail.org</a>
 * <p>
 * <hr/>
 * <p>
 * On a technical note, the {@link Mailer} class is the front facade for the public API. It limits itself to creating Session objects, offering
 * various constructors, sorting missing arguments using available properties and finally email validation. The actual sending and proxy configuration
 * is done by the internal {@link MailSender}. Some internal api is made public through this class for uses other than directly sending emails, such
 * as {@link #setDebug(boolean)} and {@link #signMessageWithDKIM(MimeMessage, Email)}.
 *
 * @author Benny Bottema
 * @see MimeMessageHelper.MimeEmailMessageWrapper
 * @see Email
 */
@SuppressWarnings("WeakerAccess")
public class Mailer {

	private static final Logger LOGGER = LoggerFactory.getLogger(Mailer.class);

	private final MailSender mailSender;

	/**
	 * Email address restriction flags set to {@link EmailAddressCriteria#RFC_COMPLIANT} or overridden by by user with {@link
	 * #setEmailAddressCriteria(EnumSet)}.
	 */
	private EnumSet<EmailAddressCriteria> emailAddressCriteria = RFC_COMPLIANT;

	/**
	 * Custom Session constructor, stores the given mail session for later use. Assumes that *all* properties used to make a connection are configured
	 * (host, port, authentication and transport protocol settings). Will skip proxy.
	 *
	 * @param session A preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 * @see #Mailer(Session, ProxyConfig)
	 */
	public Mailer(final Session session) {
		this(session, new ProxyConfig());
	}

	/**
	 * Custom Session constructor with proxy, stores the given mail session for later use. Assumes that *all* properties used to make a connection are
	 * configured (host, port, authentication and transport protocol settings).
	 * <p>
	 * Only proxy settings are always added if details are provided.
	 * <p>
	 * Also set javax.mail debug mode if a config file was provided for this.
	 *
	 * @param session     A preconfigured mail {@link Session} object with which a {@link Message} can be produced.
	 * @param proxyConfig Remote proxy server details, if the connection should be run through a SOCKS proxy.
	 */
	@SuppressWarnings("SameParameterValue")
	public Mailer(final Session session, final ProxyConfig proxyConfig) {
		if (hasProperty(JAVAXMAIL_DEBUG)) {
			setDebug((Boolean) getProperty(JAVAXMAIL_DEBUG));
		}
		this.mailSender = new MailSender(session, proxyConfig, findStrategyForSession(session));
	}

	/**
	 * No-arg constructor that only works with properly populated config file ("simplejavamail.properties") on the classpath.
	 * <p>
	 * Delegates to {@link #Mailer(ServerConfig, TransportStrategy, ProxyConfig)} and populates as much as possible from the config file (smtp server
	 * details, proxy details, transport strategy) and otherwise defaults to {@link TransportStrategy#SMTP_PLAIN} and skipping proxy.
	 *
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer() {
		this(new ServerConfig(null, null, null, null), null, null);
	}

	/**
	 * @param host     The address URL of the SMTP server to be used.
	 * @param port     The port of the SMTP server.
	 * @param username An optional username, may be <code>null</code>.
	 * @param password An optional password, may be <code>null</code>, but only if username is <code>null</code> as well.
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer(final String host, final Integer port, final String username, final String password) {
		this(new ServerConfig(host, port, username, password), null, null);
	}

	/**
	 * @param serverConfig Remote SMTP server details.
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer(final ServerConfig serverConfig) {
		this(serverConfig, null, null);
	}

	/**
	 * @param host              The address URL of the SMTP server to be used.
	 * @param port              The port of the SMTP server.
	 * @param username          An optional username, may be <code>null</code>.
	 * @param password          An optional password, may be <code>null</code>, but only if username is <code>null</code> as well.
	 * @param transportStrategy The transport protocol configuration type for handling SSL or TLS (or vanilla SMTP)
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer(final String host, final Integer port, final String username, final String password,
			final TransportStrategy transportStrategy) {
		this(new ServerConfig(host, port, username, password), transportStrategy, null);
	}

	/**
	 * @param serverConfig      Remote SMTP server details.
	 * @param transportStrategy The transport protocol configuration type for handling SSL or TLS (or vanilla SMTP)
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer(final ServerConfig serverConfig, final TransportStrategy transportStrategy) {
		this(serverConfig, transportStrategy, null);
	}

	/**
	 * @param serverConfig Remote SMTP server details.
	 * @param proxyConfig  Remote proxy server details, if the connection should be run through a SOCKS proxy.
	 * @see #Mailer(ServerConfig, TransportStrategy, ProxyConfig)
	 */
	public Mailer(final ServerConfig serverConfig, final ProxyConfig proxyConfig) {
		this(serverConfig, null, proxyConfig);
	}

	/**
	 * Main constructor which produces a new {@link Session} on the fly. Use this if you don't have a mail session configured in your web container,
	 * or Spring context etc.
	 * <p>
	 * Also set javax.mail debug mode if a config file was provided for this.
	 *
	 * @param serverConfig      Remote SMTP server details.
	 * @param transportStrategy The transport protocol configuration type for handling SSL or TLS (or vanilla SMTP)
	 * @param proxyConfig       Remote proxy server details, if the connection should be run through a SOCKS proxy.
	 */
	public Mailer(final ServerConfig serverConfig, final TransportStrategy transportStrategy, final ProxyConfig proxyConfig) {
		final TransportStrategy effectiveTransportStrategy = valueOrProperty(transportStrategy, TRANSPORT_STRATEGY, TransportStrategy.SMTP_PLAIN);
		final Session session = createMailSession(serverConfig, effectiveTransportStrategy);
		this.mailSender = new MailSender(session, proxyConfig, effectiveTransportStrategy);
		this.emailAddressCriteria = null;

		if (hasProperty(JAVAXMAIL_DEBUG)) {
			setDebug((Boolean) getProperty(JAVAXMAIL_DEBUG));
		}
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
	 * @return A fully configured <code>Session</code> instance complete with transport protocol settings.
	 * @see TransportStrategy#generateProperties()
	 * @see TransportStrategy#propertyNameHost()
	 * @see TransportStrategy#propertyNamePort()
	 * @see TransportStrategy#propertyNameUsername()
	 * @see TransportStrategy#propertyNameAuthenticate()
	 */
	@SuppressWarnings("WeakerAccess")
	public static Session createMailSession(final ServerConfig serverConfig, final TransportStrategy transportStrategy) {
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
	 * In case Simple Java Mail falls short somehow, you can get a hold of the internal {@link Session} instance to debug or tweak. Please let us know
	 * why you are needing this on https://github.com/bbottema/simple-java-mail/issues.
	 */
	public Session getSession() {
		LOGGER.warn("Providing access to Session instance for emergency fall-back scenario. Please let us know why you need it.");
		LOGGER.warn("\t>https://github.com/bbottema/simple-java-mail/issues");
		return mailSender.getSession();
	}

	/**
	 * Calls {@link Session#setDebug(boolean)} so that it generates debug information. To get more information out of the underlying JavaMail
	 * framework or out of Simple Java Mail, increase logging config of your chosen logging framework (examples <a
	 * href="http://www.simplejavamail.org/#/proxy">here</a>).
	 *
	 * @param debug Flag to indicate debug mode yes/no.
	 * @see Property#JAVAXMAIL_DEBUG
	 */
	public void setDebug(final boolean debug) {
		mailSender.setDebug(debug);
	}

	/**
	 * Sets the transport mode for this mail sender to logging only, which means no mail will be actually sent out.
	 *
	 * @param transportModeLoggingOnly Flag to indicate logging mode yes/no.
	 */
	public synchronized void setTransportModeLoggingOnly(final boolean transportModeLoggingOnly) {
		mailSender.setTransportModeLoggingOnly(transportModeLoggingOnly);
	}

	/**
	 * @return Whether this Mailer is set to only log or also actually send emails through an SMTP server (which is the default).
	 */
	public boolean isTransportModeLoggingOnly() {
		return mailSender.isTransportModeLoggingOnly();
	}

	/**
	 * Configures the current session to trust all hosts and don't validate any SSL keys. The property "mail.smtp.ssl.trust" is set to "*".
	 * <p>
	 * Refer to https://javamail.java.net/nonav/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust
	 */
	public void trustAllSSLHosts(final boolean trustAllHosts) {
		mailSender.trustAllHosts(trustAllHosts);
	}

	/**
	 * Configures the current session to white list all provided hosts and don't validate SSL keys for them. The property "mail.smtp.ssl.trust" is set
	 * to a comma separated list.
	 * <p>
	 * Refer to https://javamail.java.net/nonav/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust
	 */
	public void trustSSLHosts(final String... hosts) {
		mailSender.trustHosts(hosts);
	}

	/**
	 * Copies all property entries into the {@link Session} using {@link Session#getProperties()}.
	 *
	 * @param properties The source properties to add or override in the internal {@link Session} instance.
	 */
	public void applyProperties(final Properties properties) {
		mailSender.applyProperties(properties);
	}

	/**
	 * @param poolSize The maximum number of threads when sending emails in async fashion.
	 * @see Property#DEFAULT_POOL_SIZE
	 */
	public void setThreadPoolSize(final int poolSize) {
		mailSender.setThreadPoolSize(poolSize);
	}

	/**
	 * Delegates to {@link #sendMail(Email, boolean)}, with <code>async = false</code>. This method returns only when the email has been processed by
	 * the target SMTP server.
	 */
	public final void sendMail(final Email email) {
		sendMail(email, false);
	}

	/**
	 * @see MailSender#send(Email, boolean)
	 * @see #validate(Email)
	 */
	public final synchronized void sendMail(final Email email, final boolean async) {
		if (validate(email)) {
			mailSender.send(email, async);
		}
	}

	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined.
	 *
	 * @param email The email that needs to be configured correctly.
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException Is being thrown in any of the above causes.
	 * @see EmailAddressValidator
	 */
	@SuppressWarnings({ "SameReturnValue", "WeakerAccess" })
	public boolean validate(final Email email)
			throws MailException {
		if (email.getText() == null && email.getTextHTML() == null) {
			throw new MailerException(MailerException.MISSING_CONTENT);
		} else if (email.getSubject() == null || email.getSubject().equals("")) {
			throw new MailerException(MailerException.MISSING_SUBJECT);
		} else if (email.getRecipients().size() == 0) {
			throw new MailerException(MailerException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailerException(MailerException.MISSING_SENDER);
		} else if (emailAddressCriteria != null) {
			if (!EmailAddressValidator.isValid(email.getFromRecipient().getAddress(), emailAddressCriteria)) {
				throw new MailerException(format(MailerException.INVALID_SENDER, email));
			}
			for (final Recipient recipient : email.getRecipients()) {
				if (!EmailAddressValidator.isValid(recipient.getAddress(), emailAddressCriteria)) {
					throw new MailerException(format(MailerException.INVALID_RECIPIENT, email));
				}
			}
			if (email.getReplyToRecipient() != null && !EmailAddressValidator
					.isValid(email.getReplyToRecipient().getAddress(), emailAddressCriteria)) {
				throw new MailerException(format(MailerException.INVALID_REPLYTO, email));
			}
		}
		return true;
	}

	/**
	 * Refer to {@link MimeMessageHelper#signMessageWithDKIM(MimeMessage, Email)}
	 */
	public static MimeMessage signMessageWithDKIM(final MimeMessage message, final Email email) {
		return MimeMessageHelper.signMessageWithDKIM(message, email);
	}

	/**
	 * Overrides the default email address validation restrictions {@link #emailAddressCriteria} when validating and sending emails using the current
	 * <code>Mailer</code> instance.
	 */
	public void setEmailAddressCriteria(final EnumSet<EmailAddressCriteria> emailAddressCriteria) {
		this.emailAddressCriteria = emailAddressCriteria;
	}

	/**
	 * Simple Authenticator used to create a {@link Session} object with in {@link #createMailSession(ServerConfig, TransportStrategy)}.
	 */
	private static class SmtpAuthenticator extends Authenticator {
		private final ServerConfig serverConfig;

		public SmtpAuthenticator(final ServerConfig serverConfig) {
			this.serverConfig = serverConfig;
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			return new PasswordAuthentication(serverConfig.getUsername(), serverConfig.getPassword());
		}
	}
}
