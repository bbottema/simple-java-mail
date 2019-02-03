package org.simplejavamail.api.mailer;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressValidator;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.EnumSet;
import javax.mail.internet.MimeMessage;

/**
 * Mailing tool created exclusively using {@link MailerRegularBuilder}, aimed for simplicity for sending e-mails of any complexity. This includes e-mails
 * with plain text and/or html content, embedded images and separate attachments, SMTP, SMTPS / SSL and SMTP + SSL, custom Session object, DKIM domain
 * signing and even authenticated SOCKS proxy support and threaded batch processing.
 * <p>
 * This mailing tool abstracts the javax.mail API to a higher level easy to use API. This tool works with {@link Email} instances but can also convert
 * traditional {@link MimeMessage} objects to and from {@link Email} object.
 * <p>
 * The e-mail message structure is built to work with all e-mail clients and has been tested with many different webclients as well as some desktop
 * applications.
 * <p>
 * Technically, the resulting email structure is as follows:<br>
 * <pre>
 * - mixed root
 * 	- related
 * 		- alternative
 * 			- mail tekst
 * 			- mail html tekst
 * 		- embedded images
 * 	- forwarded message
 * 	- attachments
 * </pre>
 * <p>
 * Usage example (see <a href="http://www.simplejavamail.org/#/features">simplejavamail.org/features</a> for examples):<br>
 * <pre>
 * Email email = EmailBuilder.startingBlank()
 * 		.from(&quot;lollypop&quot;, &quot;lolly.pop@somemail.com&quot;);
 * 		.to(&quot;Sugar Cane&quot;, &quot;sugar.cane@candystore.org&quot;);
 * 		.withPlainText(&quot;We should meet up!!&quot;);
 * 		.withHTMLText(&quot;&lt;b&gt;We should meet up!&lt;/b&gt;&quot;);
 * 		.withSubject(&quot;Hey&quot;);
 *
 * MailerBuilder.usingSession(preconfiguredMailSession)
 * 		.buildMailer()
 * 		.sendMail(email);
 * // or:
 * MailerBuilder.withSMTPServer(&quot;smtp.someserver.com&quot;, 25, &quot;username&quot;, &quot;password&quot;)
 * 		.buildMailer()
 * 		.sendMail(email);
 * </pre>
 * <p>
 * <a href="http://www.simplejavamail.org">simplejavamail.org</a> <hr>
 * <p>
 * On a technical note, the {@link Mailer} interface is the front facade for the public API. It limits itself to preparing for sending, but the actual
 * sending and proxy configuration is done by the internal {@link MailSender}.
 *
 * @see MailerRegularBuilder
 * @see Email
 */
@SuppressWarnings("WeakerAccess")
public interface Mailer {
	/**
	 * In case Simple Java Mail falls short somehow, you can get a hold of the internal {@link Session} instance to debug or tweak. Please let us know
	 * why you are needing this on https://github.com/bbottema/simple-java-mail/issues.
	 */
	Session getSession();
	
	/**
	 * @return The server connection details. Will be {@code null} in case a custom fixed {@link Session} instance is used.
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer, String, String)
	 */
	@Nullable
	ServerConfig getServerConfig();
	
	/**
	 * @return The transport strategy to be used. Will be {@code null} in case a custom fixed {@link Session} instance is used.
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 * @see EmailAddressCriteria
	 */
	@Nullable
	TransportStrategy getTransportStrategy();
	
	/**
	 * @return The proxy connection details. Will be empty if no proxy is required.
	 */
	@Nonnull
	ProxyConfig getProxyConfig();
	
	/**
	 * @return The operational parameters defined using a mailer builder. Includes general things like session timeouts, debug mode, SSL config etc.
	 */
	@Nonnull
	OperationalConfig getOperationalConfig();
	
	/**
	 * @return The effective validation criteria used for email validation. Returns an empty set if no validation should be done.
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 * @see EmailAddressCriteria
	 */
	@Nonnull
	EnumSet<EmailAddressCriteria> getEmailAddressCriteria();
	
	/**
	 * Delegates to {@link #testConnection(boolean)} with async == <code>false</code>.
	 */
	void testConnection();
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on the thread for sending mails so that we don't get into race condition conflicts with emails actually being sent.
	 *
	 * @return An AsyncResponse in case of async == true, otherwise <code>null</code>.
	 */
	AsyncResponse testConnection(boolean async);
	
	/**
	 * Delegates to {@link #sendMail(Email, boolean)}, with <code>async = false</code>. This method returns only when the email has been processed by
	 * the target SMTP server.
	 */
	void sendMail(Email email);
	
	/**
	 * @see MailSender#send(Email, boolean)
	 * @see #validate(Email)
	 */
	@Nullable
	AsyncResponse sendMail(Email email, @SuppressWarnings("SameParameterValue") boolean async);
	
	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined or that
	 * the addresses are missing for NPM notification flags.
	 * <p>
	 * It also checks for illegal characters that would facilitate injection attacks:
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
	boolean validate(Email email)
			throws MailException;
}
