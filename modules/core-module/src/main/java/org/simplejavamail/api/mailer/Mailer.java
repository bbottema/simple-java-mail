package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.Optional;
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
 * Asynchronous send operations expose {@link MailSend#getCompletion()} and optional {@link MailSend#requestCancellation()} control. Cancelling a detached
 * completion view does not cancel sending. A configured {@link MailerGenericBuilder#withMailSendTimeout(java.time.Duration) total timeout}
 * includes preparation, queue admission, execution and cleanup, but not observer work. Cancellation and timeout describe why work stopped,
 * not whether SMTP accepted the message: inspect any receipt on {@link MailSendCancelledException} or {@link MailSendTimeoutException}.
 * <p>
 * An inline observer runs before completion. With {@link MailerGenericBuilder#withMailSendObserver(MailSendObserver, java.util.concurrent.Executor)},
 * only the handoff is attempted before completion; the application owns callback execution and its executor.
 *
 * @see MailerRegularBuilder
 * @see Email
 */
public interface Mailer extends AutoCloseable {
	/**
	 * Returns a fresh, content-free snapshot of this Mailer's built-in async executor, or empty for a caller-owned executor.
	 * It includes estimated queued/active counts, effective limits, cumulative rejection reasons and shutdown state.
	 * Connection pools and the standalone BatchTransportExecutor are separate resources; their activity is not included.
	 *
	 * @return Diagnostic activity snapshot; never use its estimates as a check-then-send admission guard.
	 * @see MailerGenericBuilder#withAsyncQueueCapacity(int)
	 */
	@NotNull
	Optional<AsyncQueueSnapshot> getAsyncQueueSnapshot();

	/**
	 * Returns the Jakarta Mail {@link Session} used by this mailer.
	 * <p>
	 * This provides direct access for integrations that need provider APIs or Session features not surfaced by the builder. The Session is shared by the
	 * mailer, so apply changes before starting concurrent mail operations.
	 */
	Session getSession();
	
	/**
	 * Returns this Mailer's cached calling-thread execution view. Obtaining the view does not start work or allocate execution resources.
	 * The view shares this Mailer's configuration, connections and lifecycle; close the Mailer, not the view.
	 *
	 * @return The same synchronous view on every call, safe to retain and use alongside {@link #async()}.
	 */
	@NotNull Sync sync();

	/**
	 * Returns this Mailer's cached executor-backed execution view. Obtaining the view does not start workers or register another connection pool.
	 * Preparation and admission can still run on the caller thread, as documented by each operation.
	 *
	 * @return The same asynchronous view on every call, sharing this Mailer's resources and lifecycle with {@link #sync()}.
	 */
	@NotNull Async async();

	/**
	 * Calling-thread operations on the owning Mailer. Failures are thrown directly, not wrapped in completion exceptions.
	 * This view does not own separate connections or lifecycle state and can be used concurrently with the asynchronous view.
	 */
	interface Sync {
		/**
		 * Prepares and sends one email on the calling thread, returning after sending and cleanup.
		 * Composed emails receive defaults and overrides, validation, MIME conversion and configured message-security processing.
		 * Exact emails validate their explicit envelope and reuse their authoritative EML without content governance or rebuilding.
		 * The configured transport adapter owns provider-specific submission and exact-byte preservation.
		 * <p>
		 * The returned receipt describes SMTP submission, not final delivery to the recipient's mailbox. It is fine to ignore it when only completion
		 * matters; failures still throw. Logging-only and CustomMailer sends have {@link MailSubmissionStatus#UNKNOWN} receipts because no SMTP
		 * acceptance is observable. A failed or partial submission throws {@link MailSubmissionException} with that attempt's receipt and original cause.
		 * An unknown result may already have reached the server; do not blindly retry it.
		 * <p>
		 * Ordinary pooled sends release or invalidate their lease before reporting the outcome. An inline observer runs before this call returns or
		 * throws; an application-executor observer is handed off first, but its execution is not awaited. Total send deadlines exclude observer work.
		 *
		 * @param email The email to prepare and send.
		 * @return The exact successful receipt supplied to the configured observer.
		 * @throws IllegalArgumentException If {@code email} is {@code null}.
		 * @throws MailException If preparation, connection, sending, cancellation or the total send deadline fails.
		 * @throws MailSubmissionException If submission fails or only partially succeeds; inspect its receipt for known recipient facts.
		 * @see Async#sendMail(Email)
		 */
		@NotNull MailSubmissionReceipt sendMail(Email email);

		/**
		 * Sends emails sequentially over one shared SMTP connection, consuming the iterable lazily on the calling thread.
		 * Each email follows {@link #sendMail(Email)} preparation and transport-mode rules. The first failure stops iteration;
		 * connection/proxy cleanup finishes before the failure is thrown. An empty iterable opens no connection.
		 * <p>
		 * This is a streaming, completion-only operation: receipts are not collected or retained as batch history.
		 * A configured {@link MailSendObserver} receives each attempted email's outcome while the shared connection is still open.
		 * Untouched emails have no outcomes. The total timeout covers the whole batch, excluding inline observer work between emails.
		 * <p>
		 * For concurrent pooled sending, submit separate emails through {@link Async#sendMail(Email)} with the optional batch-module.
		 * This simple batch intentionally sends one email at a time and does not borrow a pool lease.
		 *
		 * @param emails The lazy source of emails, in send order.
		 * @throws IllegalArgumentException If {@code emails} is {@code null}.
		 * @throws MailException If an email cannot be prepared/sent or the total batch deadline expires.
		 * @see Async#sendMailsInSimpleBatch(Iterable)
		 */
		void sendMailsInSimpleBatch(Iterable<Email> emails);

		/**
		 * Connects to the configured SMTP server, including authentication and configured proxy routing, on the calling thread.
		 * This health check also connects in logging-only mode. It sends no email and does not notify the mail-send observer.
		 * Connection/proxy cleanup completes before returning or throwing. Provider connection/read timeouts apply, not total send deadlines.
		 * Coordination uses the owning Mailer, shared with its asynchronous view and shutdown.
		 *
		 * @throws MailException If connecting or authenticating fails.
		 * @see Async#testConnection()
		 */
		void testConnection();

		/**
		 * Inspects a fresh SMTP connection on the calling thread without SMTP authentication or sending an email.
		 * Does not call a Jakarta Mail authenticator/token provider or use cached SMTP credentials. Configured proxy and TLS client authentication
		 * still apply. This reports a new negotiation with the configured endpoint, not every endpoint in its cluster.
		 *
		 * @return Immutable connection facts, or a safe partial/unsupported report. Connection failures are in the report.
		 * @see #probeConnection(boolean)
		 */
		@NotNull SmtpConnectionReport probeConnection();

		/**
		 * Inspects a dedicated SMTP connection on the calling thread, optionally testing configured credentials.
		 * Authentication is an explicit choice for this call, never a change to ordinary send settings. Successful connection setup is not proof
		 * that authentication occurred; the report records these separately.
		 * <p>
		 * Like {@link #testConnection()}, this connects even in logging-only mode. It never sends an email, borrows a pool lease or notifies
		 * the mail-send observer. CustomMailer and unsupported providers return an unsupported report without connecting.
		 * The original Session's properties/provider are left unchanged.
		 * <p>
		 * Uses provider connection/read timeouts, not total mail-send deadlines. TLS metadata may be unavailable with custom provider/security hooks.
		 * Advertised capabilities and successful connection setup prove neither future message acceptance nor delivery.
		 * AUTH exchanges and raw exceptions are not exposed. Coordination uses the owning Mailer's connection-test/shutdown monitor.
		 *
		 * @param authenticate Whether to use the configured password/authenticator or OAuth2 token provider.
		 * @return Immutable facts after dedicated transport/proxy cleanup is attempted, including any connection/cleanup failure and earlier facts.
		 * @throws IllegalStateException If this Mailer has started shutting down.
		 * @see Async#probeConnection(boolean)
		 */
		@NotNull SmtpConnectionReport probeConnection(boolean authenticate);
	}

	/**
	 * Executor-backed operations on the owning Mailer. Accessing this view does not start work.
	 * It shares configuration, admission policy, resources and lifecycle with the synchronous view.
	 */
	interface Async {
		/**
		 * Prepares one email on the caller thread and schedules its transport work on this Mailer's executor.
		 * Preparation and validation stay on the caller thread; admission may wait according to the queue policy.
		 * The completion covers preparation, admission, connection, submission and cleanup. Operational failures complete it exceptionally;
		 * a null email is an immediate argument error. This method does not promise an immediate return.
		 * <p>
		 * Uses the same composed/exact-email processing and receipt semantics as {@link Sync#sendMail(Email)}.
		 * The successful completion contains the exact receipt reported to the observer. Failed/partial submissions retain
		 * {@link MailSubmissionException} and its receipt. Receipts describe SMTP submission, not mailbox delivery, and are optional to inspect.
		 * <p>
		 * An inline observer runs before completion; with an application executor only the observer handoff is attempted first.
		 * Requesting cancellation through {@link MailSend#requestCancellation()} does not immediately complete the send:
		 * cleanup and outcome reporting still run. Cancelling a detached completion future does not cancel sending.
		 * With inline dispatch, preparation/admission failures report on the caller thread and executed tasks on their execution thread.
		 * Cancelled or expired queued tasks report on this Mailer's completion worker; offloaded callbacks use the configured observer executor.
		 *
		 * @param email The email to prepare and send.
		 * @return The send handle; its completion carries the exact receipt or caller-facing failure.
		 * @throws IllegalArgumentException If {@code email} is {@code null}.
		 * @see Sync#sendMail(Email)
		 */
		@NotNull MailSend<MailSubmissionReceipt> sendMail(Email email);

		/**
		 * Schedules a whole sequential simple batch as one task, returning a completion-only send handle.
		 * Admission may wait according to the queue policy. Preparation and lazy iteration happen on the worker, not the caller.
		 * Rejection or cancellation before execution does not open the iterable and produces no per-email outcomes.
		 * <p>
		 * Uses the shared-connection, first-failure and observer rules of {@link Sync#sendMailsInSimpleBatch(Iterable)}.
		 * It does not make the batch itself concurrent and does not collect receipt history. Completion succeeds after all sends and cleanup,
		 * or fails with the first failure after cleanup. Null is an immediate argument error; operational and scheduling failures are on completion.
		 * <p>
		 * Cancellation and the total timeout cover the whole batch, including admission; inline observer work is excluded from its deadline.
		 * Untouched emails have no observer outcomes.
		 *
		 * @param emails The lazy source of emails, in send order.
		 * @return One handle for the whole batch, without a receipt collection.
		 * @throws IllegalArgumentException If {@code emails} is {@code null}.
		 * @see Sync#sendMailsInSimpleBatch(Iterable)
		 */
		@NotNull MailSend<Void> sendMailsInSimpleBatch(Iterable<Email> emails);

		/**
		 * Schedules the SMTP health check described by {@link Sync#testConnection()} on this Mailer's executor.
		 * Scheduling, connection and authentication failures complete the returned future exceptionally.
		 * Completion follows connection/proxy cleanup; this is not a send operation and does not use observers or total send deadlines.
		 * Cancelling the future does not abort socket I/O; configure provider connection/read timeouts.
		 *
		 * @return Completion of the full connection test, or its failure.
		 */
		@NotNull CompletableFuture<Void> testConnection();

		/**
		 * Schedules the unauthenticated equivalent of {@link Sync#probeConnection()} on this Mailer's executor.
		 * @return A report after cleanup; scheduling or shutdown failures complete the future exceptionally.
		 * @see #probeConnection(boolean)
		 */
		@NotNull CompletableFuture<SmtpConnectionReport> probeConnection();

		/**
		 * Schedules the dedicated connection inspection described by {@link Sync#probeConnection(boolean)}.
		 * Connection failures are returned as safe reports; scheduling or Mailer-shutdown failures complete the future exceptionally.
		 * Timing in the report starts when execution begins, excluding executor queue time. Cancelling this future does not abort network I/O;
		 * configure the provider's connection/read timeouts. No send observers or total send deadlines are involved.
		 *
		 * @param authenticate Whether this probe should test configured credentials.
		 * @return The report after dedicated transport/proxy cleanup is attempted.
		 */
		@NotNull CompletableFuture<SmtpConnectionReport> probeConnection(boolean authenticate);
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
	 * applies the same defaults, validation, MIME conversion, and transport mode behavior as {@link Sync#sendMail(Email)}.
	 * Use {@link MailSender#sendMailAndGetReceipt(Email)} inside the callback when caller code needs the SMTP submission receipt before checkpointing.
	 * A custom mailer cannot be used with this API because Simple Java Mail does not own the underlying connection in that configuration.
	 * A configured total timeout applies separately to opening the connection and to each send, not to application work between sends or final scope cleanup.
	 *
	 * @param openConnectionCallback The caller-managed send logic to run while the SMTP connection is open.
	 * @param <E>                    The checked exception type the callback may throw.
	 * @throws E             Any checked exception thrown by caller code.
	 * @throws MailException Can be thrown if opening the SMTP connection fails, an email isn't valid, or sending fails.
	 */
	<E extends Exception> void withOpenConnection(@NotNull OpenConnectionCallback<E> openConnectionCallback) throws E;

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
	 * New sends are rejected, accepted sends are drained, and connection pools close afterwards. This includes blocking sends and this Mailer's sends on
	 * a caller-owned executor, but not arbitrary work on that executor. Inspect individual completions for their results; a successful close does not
	 * mean every email succeeded. Inline observers and observer handoffs are drained; offloaded callbacks are not. Graceful close is not cancellation.
	 * Do not call this blocking method from a send operation or inline observer: it would wait for itself and is rejected.
	 * <p>
	 * An executor service provided through {@link MailerGenericBuilder#withExecutorService(java.util.concurrent.ExecutorService)} remains caller-owned
	 * and is not shut down.
	 *
	 * @throws Exception If resource cleanup is interrupted or fails.
	 * @see <a href="https://www.simplejavamail.org/sending-and-execution.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
	 */
	@Override
	void close() throws Exception;

	/**
	 * Starts cleanup of the resources associated with this {@link Mailer}. Despite the historical method name, this always initiates an orderly shutdown
	 * of an internally created executor service, including when the {@value org.simplejavamail.internal.modules.BatchModule#NAME} is absent.
	 * <p>
	 * The returned future represents draining this Mailer's accepted sends (including those on a caller-owned executor), observer handoffs, and
	 * connection-pool cleanup. It also waits for termination of the built-in executor. New sends are rejected as soon as shutdown begins; running
	 * work is not cancelled. A supplied executor is never shut down. Offloaded observer callbacks and their downstream processing are not awaited.
	 * It is safe to initiate cleanup from a Mailer worker, but do not wait for the returned future there: that would wait for the current task itself.
	 * <p>
	 * In a cluster, call this method or {@link #close()} on every Mailer so every pool registration is removed. An executor service provided through
	 * {@link MailerGenericBuilder#withExecutorService(java.util.concurrent.ExecutorService)} remains caller-owned and is not shut down.
	 * <p>
	 * Prefer {@link #close()} for normal application lifecycle management.
	 *
	 * @return A future representing this Mailer's graceful resource cleanup; repeated calls return the same cleanup operation.
	 * @see <a href="https://www.simplejavamail.org/sending-and-execution.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
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
