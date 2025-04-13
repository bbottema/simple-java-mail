package org.simplejavamail.api.mailer;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Mailing tool created exclusively using {@link MailerRegularBuilder}. This class is the facade to most Simple Java Mail functionality
 * which is related to doing things with an email (server not always relevant, like with validation, S/MIME encryption etc.).
 * <p>
 * The e-mail message structure is built to work with all e-mail clients and has been tested with many different webclients as well as some desktop
 * applications. You can <a href="https://www.simplejavamail.org/rfc-compliant.html#section-explore-multipart">experiment</a>
 * with the various types of emails and resulting mime structure on the Simple Java mail website.
 * <p>
 * <strong>Note: </strong>if the <a href="https://www.simplejavamail.org/modules.html#batch-module">batch-module</a>
 * is loaded when building a mailer, it will also register itself with the cluster using the provided or random cluster key, so other mailers using the same cluster key immediately start having
 * access to this new server.
 * <p>
 * <a href="https://www.simplejavamail.org">simplejavamail.org</a>
 *
 * @see MailerRegularBuilder
 * @see Email
 */
public interface Mailer extends AutoCloseable {
	/**
	 * In case Simple Java Mail falls short somehow, you can get a hold of the internal {@link Session} instance to debug or tweak. Please let us know
	 * why you are needing this on <a href="https://github.com/bbottema/simple-java-mail/issues">simple-java-mail/issues</a>.
	 */
	Session getSession();
	
	/**
	 * Delegates to {@link #testConnection(boolean)} with async == <code>false</code>.
	 */
	void testConnection();
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on the thread for sending mails so that we don't get into race condition conflicts with emails actually being sent.
	 *
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 */
	@NotNull CompletableFuture<Void> testConnection(boolean async);
	
	/**
	 * Delegates to {@link #sendMail(Email, boolean)}, with <code>async = false</code>. This method returns only when the email has been processed by
	 * the target SMTP server.
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email);
	
	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}. First, it will apply all defaults and overrides to the email
	 * instance using {@link EmailGovernance#produceEmailApplyingDefaultsAndOverrides(Email)} . Then it will validate the email. Finally, it will process
	 * the email into a JavaMail {@link Message} object.
	 * <p>
	 * Sends the JavaMail {@link Message} object using {@link Session#getTransport()}. It will call {@link Transport#connect()} assuming all
	 * connection details have been configured in the provided {@link Session} instance and finally {@link Transport#sendMessage(Message,
	 * jakarta.mail.Address[])}.
	 * <p>
	 * Performs a call to {@link Message#saveChanges()} as the Sun JavaMail API indicates it is needed to configure the message headers and providing
	 * a message id.
	 * <p>
	 * If the email should be sent asynchrounously - perhaps as part of a batch, then a new thread is started using the <em>executor</em> for
	 * thread pooling.
	 * <p>
	 * If the email should go through an authenticated proxy server, then the SOCKS proxy bridge is started if not already running. When the last
	 * email in a batch has finished, the proxy bridging server is shut down.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the SMTP server. If true, a new thread is started to
	 *              send the email and this method returns immediately.
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during connection, sending etc.
	 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
	 * @see #validate(Email)
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email, @SuppressWarnings("SameParameterValue") boolean async);
	
	/**
	 * Validates an {@link Email} instance. Validation fails if the subject is missing, content is missing, or no recipients are defined or that
	 * the addresses are missing for NPM notification flags.
	 * <p>
	 * It also checks for illegal characters that would facilitate injection attacks:
	 * <ul>
	 * <li><a href="http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security">http://www.cakesolutions.net/teamblogs/2008/05/08/email-header-injection-security</a></li>
	 * <li><a href="https://security.stackexchange.com/a/54100/110048">https://security.stackexchange.com/a/54100/110048</a></li>
	 * <li><a href="https://www.owasp.org/index.php/Testing_for_IMAP/SMTP_Injection_(OTG-INPVAL-011)">https://www.owasp.org/index.php/Testing_for_IMAP/SMTP_Injection_(OTG-INPVAL-011)</a></li>
	 * <li><a href="http://cwe.mitre.org/data/definitions/93.html">http://cwe.mitre.org/data/definitions/93.html</a></li>
	 * </ul>
	 *
	 * @param email The email that needs to be configured correctly.
	 *
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException Is being thrown in any of the above causes.
	 * @see com.sanctionco.jmail.EmailValidator
	 */
	@SuppressWarnings({"SameReturnValue" })
	boolean validate(Email email) throws MailException;

	/**
	 * Shuts down the connection pool associated with this {@link Mailer} instance and closes remaining open connections. Waits until all connections still in use become available again
	 * to deallocate them as well.
	 * <p>
	 * <strong>Note:</strong> In order to shut down the whole connection pool (in case of clustering), each individual {@link Mailer} instance should be shutdown.
	 * <p>
	 * <strong>Note:</strong> This does *not* shut down the executor service if it was provided by the user.
	 * <p>
	 * <strong>Note:</strong> this is only works in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 */
	Future<Void> shutdownConnectionPool();

	/**
	 * @return The server connection details. Will be {@code null} in case a custom fixed {@link Session} instance is used.
	 * @see MailerRegularBuilder#withSMTPServer(String, Integer, String, String)
	 */
	@Nullable
	ServerConfig getServerConfig();

	/**
	 * @return The transport strategy to be used. Will be {@code null} in case a custom fixed {@link Session} instance is used.
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withTransportStrategy(TransportStrategy)
	 * @see com.sanctionco.jmail.EmailValidator
	 */
	@Nullable
	TransportStrategy getTransportStrategy();

	/**
	 * @return The proxy connection details. Will be empty if no proxy is required.
	 */
	@NotNull
	ProxyConfig getProxyConfig();

	/**
	 * @return The operational parameters defined using a mailer builder. Includes general things like session timeouts, debug mode, SSL config etc.
	 */
	@NotNull
	OperationalConfig getOperationalConfig();

	/**
	 * @return The effective governance applied to each email (default S/MIME signing, email validator etc.).
	 */
	@NotNull
	EmailGovernance getEmailGovernance();
}
