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
	 * Delegates to {@link #testConnection(boolean)} using the mailer's configured async default.
	 *
	 * @see MailerGenericBuilder#async()
	 */
	void testConnection();
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on the thread for sending mails so that we don't get into race condition conflicts with emails actually being sent.
	 * <p>
	 * With {@code async=false}, this method throws operation failures directly and returns a completed future after a successful test. With
	 * {@code async=true}, failures from scheduling, connecting and authentication complete the returned future exceptionally.
	 *
	 * @return A future representing the complete connection test.
	 */
	@NotNull CompletableFuture<Void> testConnection(boolean async);
	
	/**
	 * Delegates to {@link #sendMail(Email, boolean)} using the mailer's configured async default.
	 *
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 * @see MailerGenericBuilder#async()
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email);

	/**
	 * Delegates to {@link #sendMailAndGetReceipt(Email, boolean)} using the mailer's configured async default.
	 * <p>
	 * The returned receipt describes SMTP submission acceptance, not final delivery to the recipient mailbox.
	 *
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 * @see MailerGenericBuilder#async()
	 * @see MailSubmissionReceipt
	 */
	@NotNull
	default CompletableFuture<MailSubmissionReceipt> sendMailAndGetReceipt(Email email) {
		return sendMail(email).thenApply(unused -> new MailSubmissionReceipt(email.getId(), null, java.time.Instant.now()));
	}
	
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
	 * If the email should be sent asynchronously - perhaps as part of a batch, then a new thread is started using the <em>executor</em> for
	 * thread pooling.
	 * <p>
	 * If the email should go through an authenticated proxy server, then the SOCKS proxy bridge is started if not already running. When the last
	 * email in a batch has finished, the proxy bridging server is shut down.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the SMTP server. If true, a new thread is started to
	 *              send the email and this method returns immediately.
	 * @return With {@code async=false}, a completed future after a successful send. With {@code async=true}, a future representing preparation,
	 * validation, scheduling and sending; failures complete it exceptionally.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 * @throws MailException If {@code async=false} and the email isn't valid, or another problem occurs during preparation, connection or sending.
	 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
	 * @see #validate(Email)
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email, @SuppressWarnings("SameParameterValue") boolean async);

	/**
	 * Processes and sends one {@link Email}, returning a receipt for the completed submission.
	 * <p>
	 * The send behavior is identical to {@link #sendMail(Email, boolean)}: defaults and overrides are applied, validation runs, MIME conversion happens,
	 * and the message is submitted using the configured transport. If the underlying transport is Angus SMTP, the receipt contains the final SMTP
	 * response available from the transport, such as {@code 250 ... queued as ...}. If no SMTP transport is involved, for example when transport mode
	 * logging-only or a custom mailer is used, the receipt is still returned after successful processing but has no SMTP response.
	 * <p>
	 * This is a submission receipt only. It does not prove final recipient mailbox delivery; use DSN, bounces, read receipts, or provider-specific
	 * mechanisms for that.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the configured send path. If true, a new thread is
	 *              started and this method returns immediately.
	 * @return With {@code async=false}, a completed future containing the receipt after a successful send. With {@code async=true}, a future
	 * representing preparation, validation, scheduling and sending; failures complete it exceptionally.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 * @throws MailException If {@code async=false} and the email isn't valid, or another problem occurs during preparation, connection or sending.
	 * @see SmtpServerResponse
	 * @see #sendMail(Email, boolean)
	 */
	@NotNull
	default CompletableFuture<MailSubmissionReceipt> sendMailAndGetReceipt(Email email, boolean async) {
		return sendMail(email, async).thenApply(unused -> new MailSubmissionReceipt(email.getId(), null, java.time.Instant.now()));
	}

	/**
	 * Runs caller-managed send logic while one SMTP connection is open.
	 * <p>
	 * Use this API when the caller owns the source queue and needs to run work between successful sends, for example marking a database-backed message
	 * as sent before fetching the next pending message:
	 * <pre>{@code
	 * mailer.withOpenConnection(sender -> {
	 *     while (database.hasMorePendingMails()) {
	 *         PersistentMail mail = database.nextPendingMail();
	 *         sender.sendMail(mail.toEmail());
	 *         database.setPending(mail, false);
	 *     }
	 * });
	 * }</pre>
	 * Simple Java Mail owns the SMTP connection and closes it when the callback returns or fails. The delegate does <strong>not</strong> use the
	 * batch-module connection pool, does not queue emails, and does not run asynchronously. Each {@link MailSender#sendMail(Email)} call
	 * applies the same defaults, validation, MIME conversion, and transport mode behavior as {@link #sendMail(Email, boolean)} with {@code async=false}.
	 * Use {@link MailSender#sendMailAndGetReceipt(Email)} inside the callback when caller code needs the SMTP submission receipt before checkpointing.
	 * A custom mailer cannot be used with this API because Simple Java Mail does not own the underlying connection in that configuration.
	 *
	 * @param openConnectionCallback The caller-managed send logic to run while the SMTP connection is open.
	 * @param <E>                    The checked exception type the callback may throw.
	 * @throws E             Any checked exception thrown by caller code.
	 * @throws MailException Can be thrown if opening the SMTP connection fails, an email isn't valid, or sending fails.
	 */
	<E extends Exception> void withOpenConnection(@NotNull OpenConnectionCallback<E> openConnectionCallback) throws E;

	/**
	 * Delegates to {@link #sendMailsInSimpleBatch(Iterable, boolean)} using the mailer's configured async default.
	 *
	 * @see MailerGenericBuilder#async()
	 */
	@NotNull CompletableFuture<Void> sendMailsInSimpleBatch(Iterable<Email> emails);

	/**
	 * Sends multiple emails sequentially over one SMTP connection.
	 * <p>
	 * This is a deliberately small "simple batch" API for caller-managed loops where the caller already owns the source queue or iteration and only
	 * wants to avoid reconnecting for every message. It is <strong>not</strong> the main batch sending API. For queued sending, pooled SMTP
	 * connections, concurrency, asynchronous queueing, cluster coordination, or higher throughput workloads, use the
	 * <a href="https://www.simplejavamail.org/modules.html#batch-module">batch-module</a> instead.
	 * <p>
	 * Each {@link Email} is processed exactly like {@link #sendMail(Email, boolean)}: defaults and overrides are applied, validation runs, the email is
	 * converted to a {@link Message}, and then the message is submitted to the SMTP server. The method stops at the first failure and closes the SMTP
	 * connection and proxy bridge before propagating the exception, or completing the returned future exceptionally when {@code async} is {@code true}.
	 * <p>
	 * The {@code async} flag applies only to this immediate {@link Mailer} API call: {@code false} blocks the caller while the simple batch runs,
	 * {@code true} schedules the whole simple batch as one asynchronous task and returns immediately. It does <strong>not</strong> make the simple
	 * batch itself concurrent; emails are still sent one at a time over one SMTP connection.
	 *
	 * @param emails The emails to send in order.
	 * @param async  If false, this method blocks until all emails have been processed by the SMTP server. If true, a new task is started for the whole
	 *               sequential simple batch and this method returns immediately.
	 * @return With {@code async=false}, a completed future after every email has been sent successfully. With {@code async=true}, a future representing
	 * scheduling and the complete sequential batch; failures complete it exceptionally.
	 * @throws IllegalArgumentException If {@code emails} is {@code null}.
	 * @throws MailException If {@code async=false} and an email isn't valid, or another problem occurs during preparation, connection or sending.
	 * @see #sendMail(Email, boolean)
	 */
	@NotNull CompletableFuture<Void> sendMailsInSimpleBatch(Iterable<Email> emails, boolean async);
	
	/**
	 * Runs this mailer's client-side validation against the supplied {@link Email} instance as it stands.
	 * <p>
	 * In normal validation mode this method:
	 * <ul>
	 *     <li>requires a From recipient and at least one To, Cc or Bcc recipient;</li>
	 *     <li>rejects encoded-word content in address fields and applies the mailer's configured {@link com.sanctionco.jmail.EmailValidator}, if any;</li>
	 *     <li>scans the subject, headers, address fields, attachment metadata and embedded-image metadata for CRLF injection.</li>
	 * </ul>
	 * Completeness here means a sender and recipient; an empty subject or body is permitted.
	 * <p>
	 * This method validates the supplied instance directly. It does not apply the mailer's email defaults or overrides first. The send methods produce
	 * the effective email by applying defaults and overrides and then run the same client-side validation. MIME conversion and the configured maximum
	 * encoded message-size check also happen later in the send pipeline.
	 * <p>
	 * When all client validation is disabled, this method uses lenient validation: findings are logged instead of being thrown to the caller.
	 *
	 * @param email The email instance to validate as-is.
	 *
	 * @return Always <code>true</code> (throws a {@link MailException} exception if validation fails).
	 * @throws MailException If validation fails in normal validation mode.
	 * @see com.sanctionco.jmail.EmailValidator
	 */
	@SuppressWarnings({"SameReturnValue" })
	boolean validate(Email email) throws MailException;

	/**
	 * Releases the resources owned by this {@link Mailer}. This initiates an orderly shutdown of an internally created executor service and, when the
	 * {@value org.simplejavamail.internal.modules.BatchModule#NAME} is present, closes the connection pool registered for this Mailer's {@link Session}.
	 * <p>
	 * Wait for all {@link CompletableFuture}s returned by asynchronous operations before closing the Mailer. Closing waits for connection-pool cleanup,
	 * but does not wait for those asynchronous results on the caller's behalf.
	 * <p>
	 * An executor service provided through {@link MailerGenericBuilder#withExecutorService(java.util.concurrent.ExecutorService)} remains caller-owned
	 * and is not shut down.
	 *
	 * @throws Exception If resource cleanup is interrupted or fails.
	 * @see <a href="https://www.simplejavamail.org/configuration.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
	 */
	@Override
	void close() throws Exception;

	/**
	 * Starts cleanup of the resources associated with this {@link Mailer}. Despite the historical method name, this always initiates an orderly shutdown
	 * of an internally created executor service, including when the {@value org.simplejavamail.internal.modules.BatchModule#NAME} is absent.
	 * <p>
	 * With the batch module present, this also closes the connection pool registered for this Mailer's {@link Session}. The returned future represents that
	 * connection-pool cleanup; it does not represent completion of queued asynchronous sends or termination of the executor. Wait for all asynchronous
	 * operation futures before calling this method.
	 * <p>
	 * In a cluster, call this method or {@link #close()} on every Mailer so every pool registration is removed. An executor service provided through
	 * {@link MailerGenericBuilder#withExecutorService(java.util.concurrent.ExecutorService)} remains caller-owned and is not shut down.
	 * <p>
	 * Prefer {@link #close()} for normal application lifecycle management.
	 *
	 * @return A future that completes when this Mailer's connection-pool cleanup is finished, or an already-completed future when no batch module is present.
	 * @see <a href="https://www.simplejavamail.org/configuration.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
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
