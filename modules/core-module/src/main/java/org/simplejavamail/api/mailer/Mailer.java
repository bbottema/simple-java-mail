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
	 * Returns the Jakarta Mail {@link Session} used by this mailer.
	 * <p>
	 * This provides direct access for integrations that need provider APIs or Session features not surfaced by the builder. The Session is shared by the
	 * mailer, so apply changes before starting concurrent mail operations.
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
	 * Sends one email on the calling thread and returns only after the configured send path completes.
	 * <p>
	 * This method ignores the mailer's configured async default. Preparation, validation, connection and submission failures are thrown directly.
	 * Use {@link #sendMailAsync(Email)} when the caller needs a future instead.
	 *
	 * @param email The information for the email to be sent.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 * @throws MailException If the email is invalid or another problem occurs during preparation, connection or sending.
	 * @throws MailSubmissionException If transport submission fails or only partially succeeds.
	 */
	default void sendMailSync(Email email) {
		sendMail(email, false).join();
	}

	/**
	 * Schedules one email for asynchronous sending, independently of the mailer's configured async default.
	 * <p>
	 * The returned future covers preparation, validation, scheduling, connection and submission. Operational failures complete it exceptionally;
	 * a {@code null} email remains an immediate contract violation.
	 *
	 * @param email The information for the email to be sent.
	 * @return A future representing the complete send attempt.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 */
	@NotNull
	default CompletableFuture<Void> sendMailAsync(Email email) {
		return sendMail(email, true);
	}

	/**
	 * Sends one email on the calling thread and returns its provider-neutral submission receipt.
	 * <p>
	 * This method ignores the mailer's configured async default. It reports failed and partial submissions by throwing
	 * {@link MailSubmissionException}, whose receipt contains the facts captured during the same attempt.
	 *
	 * @param email The information for the email to be sent.
	 * @return The receipt for the completed submission.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 * @throws MailException If the email is invalid or another problem occurs during preparation, connection or sending.
	 * @throws MailSubmissionException If transport submission fails or only partially succeeds.
	 */
	@NotNull
	default MailSubmissionReceipt sendMailAndGetReceiptSync(Email email) {
		return sendMailAndGetReceipt(email, false).join();
	}

	/**
	 * Schedules one email for asynchronous sending and completes with its provider-neutral submission receipt.
	 * <p>
	 * This method ignores the mailer's configured async default. Failed and partial submissions complete the future exceptionally with
	 * {@link MailSubmissionException}; a {@code null} email remains an immediate contract violation.
	 *
	 * @param email The information for the email to be sent.
	 * @return A future representing the complete send attempt and its receipt.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 */
	@NotNull
	default CompletableFuture<MailSubmissionReceipt> sendMailAndGetReceiptAsync(Email email) {
		return sendMailAndGetReceipt(email, true);
	}

	/**
	 * Delegates to {@link #sendMail(Email, boolean)} using the mailer's configured async default.
	 * <p>
	 * Prefer {@link #sendMailSync(Email)} or {@link #sendMailAsync(Email)} when the execution mode should be visible at the call site.
	 *
	 * @return A {@link CompletableFuture} that is completed immediately if not <em>async</em>.
	 * @see MailerGenericBuilder#async()
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email);

	/**
	 * Delegates to {@link #sendMailAndGetReceipt(Email, boolean)} using the mailer's configured async default.
	 * <p>
	 * Prefer {@link #sendMailAndGetReceiptSync(Email)} or {@link #sendMailAndGetReceiptAsync(Email)} when the execution mode should be visible at the
	 * call site. The returned receipt describes the provider-neutral SMTP submission outcome, not final delivery to the recipient mailbox.
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
	 * Processes a composed {@link Email} into a completely configured {@link Message}: defaults and overrides are applied through
	 * {@link EmailGovernance#produceEmailApplyingDefaultsAndOverrides(Email)}, validation runs, and the governed content is rendered. An exact email
	 * instead validates its explicit envelope and reuses its authoritative EML representation without content governance or rebuilding.
	 * <p>
	 * Connects the transport obtained from {@link Session#getTransport()} using the Session configuration. A matching transport adapter owns
	 * provider-specific submission; ordinary provider-neutral messages can fall back to {@link Transport#sendMessage(Message, jakarta.mail.Address[])}.
	 * <p>
	 * Jakarta Mail can finalize ordinary composed messages through {@link Message#saveChanges()}. Exact EML and protected content use an explicit
	 * preservation contract so provider finalization cannot invalidate authoritative bytes.
	 * <p>
	 * If the email should be sent asynchronously - perhaps as part of a batch, then a new thread is started using the <em>executor</em> for
	 * thread pooling.
	 * <p>
	 * If the email should go through an authenticated proxy server, then the SOCKS proxy bridge is started if not already running. When the last
	 * email in a batch has finished, the proxy bridging server is shut down.
	 * <p>
	 * Exact email follows the same asynchronous execution, pooling, proxy, receipt, and observer path. Its explicit SMTP envelope is used and a compatible
	 * transport adapter submits the supplied EML bytes unchanged.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the SMTP server. If true, a new thread is started to
	 *              send the email and this method returns immediately.
	 * @return With {@code async=false}, a completed future after a successful send. With {@code async=true}, a future representing preparation,
	 * validation, scheduling and sending; failures complete it exceptionally.
	 * @throws IllegalArgumentException If {@code email} is {@code null}.
	 * @throws MailException If {@code async=false} and the email isn't valid, or another problem occurs during preparation, connection or sending.
	 * @throws MailSubmissionException If {@code async=false} and transport submission fails. The exception retains the original Jakarta Mail failure
	 * and any known accepted, valid-unsent and invalid recipient addresses. With {@code async=true}, it completes the future exceptionally instead.
	 * @see java.util.concurrent.Executors#newFixedThreadPool(int)
	 * @see #validate(Email)
	 * @see #sendMailSync(Email)
	 * @see #sendMailAsync(Email)
	 */
	@NotNull CompletableFuture<Void> sendMail(Email email, @SuppressWarnings("SameParameterValue") boolean async);

	/**
	 * Processes and sends one {@link Email}, returning a receipt for the completed submission.
	 * <p>
	 * The send behavior is identical to {@link #sendMail(Email, boolean)}: defaults and overrides are applied, validation runs, MIME conversion happens,
	 * and the message is submitted using the configured transport. The receipt reports provider-neutral acceptance status and immutable accepted,
	 * valid-unsent and invalid recipient addresses. If the provider exposes a server response, such as {@code 250 ... queued as ...}, that response is
	 * available separately. If no observable transport is involved, for example when transport mode logging-only or a custom mailer is used, the
	 * receipt has status {@link MailSubmissionStatus#UNKNOWN}.
	 * <p>
	 * A failed or partial submission throws {@link MailSubmissionException} for synchronous sends, or completes the asynchronous future with that
	 * exception. Its receipt exposes the same status and recipient groups while its cause remains the original Jakarta Mail exception. An
	 * {@link MailSubmissionStatus#UNKNOWN} failure may already have reached the server; do not automatically retry it unless duplicate submission is
	 * acceptable or otherwise prevented.
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
	 * @throws MailSubmissionException If {@code async=false} and transport submission fails. With {@code async=true}, it completes the future
	 * exceptionally instead.
	 * @see SmtpServerResponse
	 * @see MailSubmissionStatus
	 * @see MailSubmissionException
	 * @see #sendMail(Email, boolean)
	 * @see #sendMailAndGetReceiptSync(Email)
	 * @see #sendMailAndGetReceiptAsync(Email)
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
	 * applies the same defaults, validation, MIME conversion, and transport mode behavior as {@link #sendMailSync(Email)}.
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
	 * Each {@link Email} is processed exactly like {@link #sendMail(Email, boolean)}, including that method's exact-email behavior. The method stops at
	 * the first failure and closes the SMTP connection and proxy bridge before propagating the exception, or completing the returned future exceptionally
	 * when {@code async} is {@code true}.
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
	 * Prepares the supplied {@link Email} through this mailer's normal send-time pipeline without opening an SMTP connection.
	 * <p>
	 * For a composed email, defaults and overrides are applied to a separate effective Email, ordinary client-side validation runs, the complete MIME
	 * message is rendered, configured S/MIME, OpenPGP and DKIM processing runs, and the final encoded size is checked against the configured maximum. For
	 * an exact email, those transforming steps are deliberately bypassed: its explicit envelope is validated, its authoritative EML is parsed without
	 * alteration, and its supplied byte length is checked against the configured maximum. The returned snapshot
	 * exposes the effective Email, defensive EML bytes, encoded size, Message-ID and transport envelope addresses.
	 * <p>
	 * <strong>When to use:</strong> choose rehearsal when the caller will inspect or retain any of those prepared facts, for example for a preview, EML
	 * export, size diagnostics or transport-envelope inspection. A successful rehearsal already performs the same preparation and checks as
	 * {@link #validate(Email)}; do not call {@code validate(email)} before this method, because that prepares the message twice. Use
	 * {@code validate(email)} instead when the caller needs only a success-or-exception gate and will discard the prepared result.
	 * <p>
	 * The supplied Email is not changed. A later send prepares a composed message again, so generated dates, MIME boundaries, Message-IDs and
	 * cryptographic output can differ unless the caller fixes those inputs. Exact email is the exception: its authoritative bytes remain identical.
	 *
	 * @param email The email to rehearse with this mailer's configuration.
	 * @return The immutable result of this preparation.
	 * @throws MailException If validation, MIME construction or security processing fails.
	 * @throws EmailTooBigException If the final encoded message exceeds the configured maximum size.
	 * @see #rehearse(Email, boolean)
	 * @see #validate(Email)
	 */
	@NotNull
	MailRehearsal rehearse(Email email) throws MailException;

	/**
	 * Prepares the supplied {@link Email} without opening an SMTP connection, with control over expensive final-message processing.
	 * <p>
	 * For a composed email, defaults, overrides, ordinary validation and base MIME construction always run. When
	 * {@code processSecurityAndValidateSize} is {@code true},
	 * configured S/MIME, OpenPGP and DKIM processing also runs and the final encoded size is checked. When it is {@code false}, the returned bytes and
	 * encoded size describe the unsecured base MIME message and the configured maximum size is not enforced. All other snapshot fields remain available.
	 * Exact email is never transformed in either mode; the flag controls only validation of its supplied byte length against the configured maximum.
	 * Neither mode invokes transport-mode logging or a custom mailer.
	 * <p>
	 * Choose this result-returning overload instead of {@link #validate(Email, boolean)} when the caller needs the base or final EML, size, effective
	 * Email, Message-ID or envelope facts. A successful call has already validated to the requested depth.
	 *
	 * @param email The email to rehearse with this mailer's configuration.
	 * @param processSecurityAndValidateSize Whether to run configured message security and validate the resulting encoded size.
	 * @return The immutable result of this preparation.
	 * @throws MailException If validation, MIME construction or requested security processing fails.
	 * @throws EmailTooBigException If final-size validation is requested and the encoded message exceeds the configured maximum size.
	 * @see #rehearse(Email)
	 * @see #validate(Email, boolean)
	 */
	@NotNull
	MailRehearsal rehearse(Email email, boolean processSecurityAndValidateSize) throws MailException;
	
	/**
	 * Checks whether the supplied {@link Email} can be prepared for sending through this mailer, without opening an SMTP connection.
	 * <p>
	 * <strong>When to use:</strong> choose this method when the caller needs only a success-or-exception gate, such as rejecting an invalid request or
	 * asserting in a test that this Mailer can prepare the message. It delegates to {@link #rehearse(Email)} and discards that method's snapshot; it is
	 * not a cheaper or less thorough preparation path. If the caller needs the effective Email, EML bytes, size, Message-ID or envelope addresses, call
	 * {@code rehearse(email)} directly instead of validating first.
	 * <p>
	 * For composed email, the mailer's defaults and overrides are applied first. In normal validation mode this method then:
	 * <ul>
	 *     <li>requires a From recipient and at least one To, Cc or Bcc recipient;</li>
	 *     <li>rejects encoded-word content in address fields and applies the mailer's configured {@link com.sanctionco.jmail.EmailValidator}, if any;</li>
	 *     <li>scans the subject, headers, address fields, attachment metadata and embedded-image metadata for CRLF injection.</li>
	 *     <li>builds the complete MIME message, including configured S/MIME, OpenPGP and DKIM processing;</li>
	 *     <li>checks the final encoded message size against the configured maximum, if any.</li>
	 * </ul>
	 * Completeness here means a sender and recipient; an empty subject or body is permitted.
	 * Exact email instead requires canonical, parseable EML and at least one explicit SMTP-envelope recipient. It deliberately bypasses mailer defaults,
	 * overrides, composed-content validation, and configured security processing; full validation checks the supplied byte length against the configured
	 * maximum without changing the message.
	 * <p>
	 * The supplied Email is not changed. Validation creates a separate governed Email and MIME message for composed mail; exact mail retains its original
	 * Email and bytes. Sending still performs its own size check. When all client validation is disabled, ordinary validation findings are logged instead
	 * of being thrown, but MIME construction, security processing, encoded-size failures, and exact-mail invariants still fail this rehearsal.
	 *
	 * @param email The email to validate with this mailer's configuration.
	 *
	 * @return Always <code>true</code> after {@link #rehearse(Email)} succeeds. Invalid preparation is reported by exception, never by returning
	 * {@code false}.
	 * @throws MailException If validation, MIME construction or security processing fails.
	 * @throws EmailTooBigException If the final encoded message exceeds the configured maximum size.
	 * @see com.sanctionco.jmail.EmailValidator
	 * @see #rehearse(Email)
	 */
	@SuppressWarnings({"SameReturnValue" })
	boolean validate(Email email) throws MailException;

	/**
	 * Checks whether the supplied {@link Email} can be prepared for sending through this mailer, with control over expensive final-message processing.
	 * <p>
	 * For a composed email, defaults, overrides, ordinary validation and base MIME construction always run. When
	 * {@code processSecurityAndValidateSize} is {@code true},
	 * configured S/MIME, OpenPGP and DKIM processing also runs and the final encoded message size is checked. When it is {@code false}, validation stops
	 * after constructing the unsecured base MIME message and does not enforce the configured maximum size.
	 * Exact email is never transformed in either mode; the flag controls only its supplied-byte size check.
	 * <p>
	 * Neither mode opens an SMTP connection or changes the supplied Email.
	 * Use this success-or-exception overload when the caller does not need the prepared result. If it needs any result fields, call
	 * {@link #rehearse(Email, boolean)} directly; validating first would prepare the message twice.
	 *
	 * @param email The email to validate with this mailer's configuration.
	 * @param processSecurityAndValidateSize Whether to run configured message security and validate the resulting encoded size.
	 * @return Always <code>true</code> after {@link #rehearse(Email, boolean)} succeeds. Invalid preparation is reported by exception, never by returning
	 * {@code false}.
	 * @throws MailException If validation, MIME construction or requested security processing fails.
	 * @throws EmailTooBigException If final-size validation is requested and the encoded message exceeds the configured maximum size.
	 * @see #validate(Email)
	 * @see #rehearse(Email, boolean)
	 */
	@SuppressWarnings({"SameReturnValue" })
	boolean validate(Email email, boolean processSecurityAndValidateSize) throws MailException;

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
	 * @return The effective operational parameters of this built Mailer, including builder overrides and generated defaults.
	 * @see org.simplejavamail.config.SimpleJavaMailConfig#getDiagnostics()
	 */
	@NotNull
	OperationalConfig getOperationalConfig();

	/**
	 * @return The effective governance applied to each email (default S/MIME signing, email validator etc.).
	 */
	@NotNull
	EmailGovernance getEmailGovernance();
}
