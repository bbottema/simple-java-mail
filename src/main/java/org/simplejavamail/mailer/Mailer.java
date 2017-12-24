package org.simplejavamail.mailer;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;
import org.simplejavamail.MailException;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.email.AttachmentResource;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.mailsender.MailSender;
import org.simplejavamail.mailer.internal.mailsender.OperationalConfig;
import org.simplejavamail.mailer.internal.mailsender.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.mailer.config.TransportStrategy.findStrategyForSession;

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
 * <pre>
 * - root
 * 	- related
 * 		- alternative
 * 			- mail text
 * 			- mail html text
 * 		- embedded images
 * 	- attachments
 * </pre>
 * <p>
 * Usage example:<br>
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
 * <hr>
 * <p>
 * On a technical note, the {@link Mailer} class is the front facade for the public API. It limits itself to creating Session objects, offering
 * various constructors, sorting missing arguments using available properties and finally email validation. The actual sending and proxy configuration
 * is done by the internal {@link MailSender}.
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
	 * See {@link MailerGenericBuiler#withEmailAddressCriteria(EnumSet)}.
	 *
	 * @see EmailAddressCriteria
	 */
	private final EnumSet<EmailAddressCriteria> emailAddressCriteria;
	
	Mailer(@Nonnull final MailerFromSessionBuilder fromSessionBuilder) {
		emailAddressCriteria = fromSessionBuilder.getEmailAddressCriteria();
		final Session session = fromSessionBuilder.getSession();
		final ProxyConfig proxyConfig = fromSessionBuilder.buildProxyConfig();
		this.mailSender = initFromGenericBuilder(findStrategyForSession(session), proxyConfig, session, fromSessionBuilder);
	}
	
	Mailer(@Nonnull final MailerRegularBuilder regularBuilder) {
		emailAddressCriteria = regularBuilder.getEmailAddressCriteria();
		final TransportStrategy transportStrategy = regularBuilder.getTransportStrategy();
		final ServerConfig serverConfig = regularBuilder.buildServerConfig();
		final ProxyConfig proxyConfig = regularBuilder.buildProxyConfig();
		final Session session = createMailSession(serverConfig, transportStrategy);
		this.mailSender = initFromGenericBuilder(transportStrategy, proxyConfig, session, regularBuilder);
	}
	
	private MailSender initFromGenericBuilder(@Nonnull TransportStrategy transportStrategy, @Nonnull ProxyConfig proxyConfig, @Nonnull Session session, @Nonnull final MailerGenericBuiler<?> genericBuiler) {
		OperationalConfig operationalConfig = genericBuiler.buildOperationalConfig();
		return new MailSender(session, operationalConfig, proxyConfig, transportStrategy);
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
	 * In case Simple Java Mail falls short somehow, you can get a hold of the internal {@link Session} instance to debug or tweak. Please let us know
	 * why you are needing this on https://github.com/bbottema/simple-java-mail/issues.
	 */
	public Session getSession() {
		LOGGER.warn("Providing access to Session instance for emergency fall-back scenario. Please let us know why you need it.");
		LOGGER.warn("\t\t> https://github.com/bbottema/simple-java-mail/issues");
		return mailSender.getSession();
	}
	
	/**
	 * @return The operational parameters defined using a mailer builder. Includes general things like session timeouts, debug mode, SSL config etc.
	 */
	public OperationalConfig getOperationalConfig() {
		return mailSender.getOperationalConfig();
	}
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on the thread for sending mails so that we don't get into race condition conflicts with emails actually being sent.
	 */
	public void testConnection() {
		mailSender.testConnection();
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
	public final synchronized void sendMail(final Email email, @SuppressWarnings("SameParameterValue") final boolean async) {
		if (validate(email)) {
			mailSender.send(email, async);
		}
	}
	
	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined or that
	 * the addresses are missing for NPM notification flags.
	 * <p>
	 * It also checks for illegal characters that would facilitate injection attacks:
	 * <p>
	 * <ul>
	 * <li>http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security</li>
	 * <li>https://security.stackexchange.com/a/54100/110048</li>
	 * <li>https://www.owasp.org/index.php/Testing_for_IMAP/SMTP_Injection_(OTG-INPVAL-011)</li>
	 * <li>http://cwe.mitre.org/data/definitions/93.html</li>
	 * </ul>
	 *
	 * @param email The email that needs to be configured correctly.
	 *
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException Is being thrown in any of the above causes.
	 * @see EmailAddressValidator
	 */
	@SuppressWarnings({"SameReturnValue", "WeakerAccess"})
	public boolean validate(final Email email)
			throws MailException {
		// check for mandatory values
		if (email.getRecipients().size() == 0) {
			throw new MailerException(MailerException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailerException(MailerException.MISSING_SENDER);
		} else if (email.isUseDispositionNotificationTo() && email.getDispositionNotificationTo() == null) {
			throw new MailerException(MailerException.MISSING_DISPOSITIONNOTIFICATIONTO);
		} else if (email.isUseReturnReceiptTo() && email.getReturnReceiptTo() == null) {
			throw new MailerException(MailerException.MISSING_RETURNRECEIPTTO);
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
			if (email.getBounceToRecipient() != null && !EmailAddressValidator
					.isValid(email.getBounceToRecipient().getAddress(), emailAddressCriteria)) {
				throw new MailerException(format(MailerException.INVALID_BOUNCETO, email));
			}
			if (email.isUseDispositionNotificationTo() && !EmailAddressValidator
					.isValid(email.getDispositionNotificationTo().getAddress(), emailAddressCriteria)) {
				throw new MailerException(format(MailerException.INVALID_DISPOSITIONNOTIFICATIONTO, email));
			}
			if (email.isUseReturnReceiptTo() && !EmailAddressValidator
					.isValid(email.getReturnReceiptTo().getAddress(), emailAddressCriteria)) {
				throw new MailerException(format(MailerException.INVALID_RETURNRECEIPTTO, email));
			}
		}
		
		// check for illegal values
		scanForInjectionAttack(email.getSubject(), "email.subject");
		for (final Map.Entry<String, String> headerEntry : email.getHeaders().entrySet()) {
			scanForInjectionAttack(headerEntry.getKey(), "email.header.mapEntryKey");
			scanForInjectionAttack(headerEntry.getValue(), "email.header." + headerEntry.getKey());
		}
		for (final AttachmentResource attachment : email.getAttachments()) {
			scanForInjectionAttack(attachment.getName(), "email.attachment.name");
		}
		for (final AttachmentResource embeddedImage : email.getEmbeddedImages()) {
			scanForInjectionAttack(embeddedImage.getName(), "email.embeddedImage.name");
		}
		scanForInjectionAttack(email.getFromRecipient().getName(), "email.fromRecipient.name");
		scanForInjectionAttack(email.getFromRecipient().getAddress(), "email.fromRecipient.address");
		if (!valueNullOrEmpty(email.getReplyToRecipient())) {
			scanForInjectionAttack(email.getReplyToRecipient().getName(), "email.replyToRecipient.name");
			scanForInjectionAttack(email.getReplyToRecipient().getAddress(), "email.replyToRecipient.address");
		}
		if (!valueNullOrEmpty(email.getBounceToRecipient())) {
			scanForInjectionAttack(email.getBounceToRecipient().getName(), "email.bounceToRecipient.name");
			scanForInjectionAttack(email.getBounceToRecipient().getAddress(), "email.bounceToRecipient.address");
		}
		for (final Recipient recipient : email.getRecipients()) {
			scanForInjectionAttack(recipient.getName(), "email.recipient.name");
			scanForInjectionAttack(recipient.getAddress(), "email.recipient.address");
		}
		
		return true;
	}
	
	/**
	 * @param value      Value checked for suspicious newline characters "\n", "\r" and "%0A" (as acknowledged by SMTP servers).
	 * @param valueLabel The name of the field being checked, used for reporting exceptions.
	 */
	private static void scanForInjectionAttack(final String value, final String valueLabel) {
		if (value != null && (value.contains("\n") || value.contains("\r") || value.contains("%0A"))) {
			throw new MailerException(format(MailerException.INJECTION_SUSPECTED, valueLabel, value));
		}
	}
	
	/**
	 * Refer to {@link MimeMessageHelper#signMessageWithDKIM(MimeMessage, Email)}
	 */
	public static MimeMessage signMessageWithDKIM(final MimeMessage messageToSign, final Email emailContainingSigningDetails) {
		return MimeMessageHelper.signMessageWithDKIM(messageToSign, emailContainingSigningDetails);
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