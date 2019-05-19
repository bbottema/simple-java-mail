package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.mailer.internal.mailsender.MailSenderImpl;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.security.cert.X509Certificate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.api.mailer.config.TransportStrategy.findStrategyForSession;
import static org.simplejavamail.mailer.internal.MailerException.SMIME_MODULE_NOT_AVAILABLE;

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
	public boolean validate(final Email email)
			throws MailException {
		LOGGER.debug("validating email...");
		
		// check for mandatory values
		if (email.getRecipients().size() == 0) {
			throw new MailerException(MailerException.MISSING_RECIPIENT);
		} else if (email.getFromRecipient() == null) {
			throw new MailerException(MailerException.MISSING_SENDER);
		} else if (email.isUseDispositionNotificationTo() && email.getDispositionNotificationTo() == null) {
			throw new MailerException(MailerException.MISSING_DISPOSITIONNOTIFICATIONTO);
		} else if (email.isUseReturnReceiptTo() && email.getReturnReceiptTo() == null) {
			throw new MailerException(MailerException.MISSING_RETURNRECEIPTTO);
		} else
			if (!emailAddressCriteria.isEmpty()) {
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
						.isValid(checkNonEmptyArgument(email.getDispositionNotificationTo(), "dispositionNotificationTo").getAddress(), emailAddressCriteria)) {
					throw new MailerException(format(MailerException.INVALID_DISPOSITIONNOTIFICATIONTO, email));
				}
				if (email.isUseReturnReceiptTo() && !EmailAddressValidator
						.isValid(checkNonEmptyArgument(email.getReturnReceiptTo(), "returnReceiptTo").getAddress(), emailAddressCriteria)) {
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
		
		LOGGER.debug("...no problems found");
		
		return true;
	}
	
	/**
	 * @param value      Value checked for suspicious newline characters "\n", "\r" and "%0A" (as acknowledged by SMTP servers).
	 * @param valueLabel The name of the field being checked, used for reporting exceptions.
	 */
	private static void scanForInjectionAttack(final @Nullable String value, final String valueLabel) {
		if (value != null && (value.contains("\n") || value.contains("\r") || value.contains("%0A"))) {
			throw new MailerException(format(MailerException.INJECTION_SUSPECTED, valueLabel, value));
		}
	}

	/**
	 * Refer to {@link MimeMessageHelper#signMessageWithDKIM(MimeMessage, Email)}
	 */
	@SuppressWarnings("unused")
	public static MimeMessage signMessageWithDKIM(@Nonnull final MimeMessage messageToSign, @Nonnull final Email emailContainingSigningDetails) {
		return MimeMessageHelper.signMessageWithDKIM(messageToSign, emailContainingSigningDetails);
	}

	/**
	 * Depending on the Email configuration, signs and then encrypts message (both steps optional), using the S/MIME module.
	 *
	 * @see SMIMEModule#signAndOrEncryptEmail(Session, MimeMessage, Email)
	 */
	@SuppressWarnings("unused")
	public static MimeMessage signAndOrEncryptMessageWithSmime(@Nonnull final Session session, @Nonnull final MimeMessage messageToProtect, @Nonnull final Email emailContainingSmimeDetails) {
		if (ModuleLoader.smimeModuleAvailable()) {
			return ModuleLoader.loadSmimeModule().signAndOrEncryptEmail(session, messageToProtect, emailContainingSmimeDetails);
		} else {
			throw new MailerException(SMIME_MODULE_NOT_AVAILABLE);
		}
	}
	
	/**
	 * Simple Authenticator used to create a {@link Session} object with in {@link #createMailSession(ServerConfig, TransportStrategy)}.
	 */
	private static class SmtpAuthenticator extends Authenticator {
		private final ServerConfig serverConfig;
		
		SmtpAuthenticator(final ServerConfig serverConfig) {
			this.serverConfig = serverConfig;
		}
		
		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			return new PasswordAuthentication(serverConfig.getUsername(), serverConfig.getPassword());
		}
	}
}