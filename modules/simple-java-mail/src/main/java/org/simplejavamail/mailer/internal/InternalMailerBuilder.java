package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

/**
 * This interface is only there to improve readability there where internal builder API is used.
 */
@ApiStatus.Internal
@SuppressWarnings("UnusedReturnValue")
public interface InternalMailerBuilder<T extends MailerGenericBuilder<?>> extends MailerGenericBuilder<T> {
	boolean isExecutorServiceUserProvided();
	/**
	 * Delegates to {@link #withProxyHost(String)} and {@link #withProxyPort(Integer)}.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more detailed API")
	T withProxy(@Nullable @Cli.Optional String proxyHost, @Nullable @Cli.Optional Integer proxyPort);
	/**
	 * Sets proxy server settings, by delegating to:
	 * <ol>
	 * <li>{@link #withProxyHost(String)}</li>
	 * <li>{@link #withProxyPort(Integer)}</li>
	 * <li>{@link #withProxyUsername(String)}</li>
	 * <li>{@link #withProxyPassword(String)}</li>
	 * </ol>
	 *
	 * @param proxyHost See linked documentation above.
	 * @param proxyPort See linked documentation above.
	 * @param proxyUsername See linked documentation above.
	 * @param proxyPassword See linked documentation above.
	 */
	T withProxy(@Nullable @Cli.Optional String proxyHost, @Nullable @Cli.Optional Integer proxyPort, @Nullable @Cli.Optional String proxyUsername, @Nullable @Cli.Optional String proxyPassword);
	/**
	 * Sets the optional proxy host, which will override any default that might have been set (through properties file or programmatically).
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyHost(@Nullable @Cli.Optional String proxyHost);
	/**
	 * Sets the proxy port, which will override any default that might have been set (through properties file or programmatically).
	 * <p>
	 * Defaults to {@value DEFAULT_PROXY_PORT} if no custom default property was configured.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyPort(@Nullable @Cli.Optional Integer proxyPort);
	/**
	 * Sets the optional username to authenticate with the proxy. If set, Simple Java Mail will use its built in proxy bridge to
	 * perform the SOCKS authentication, as the underlying JavaMail framework doesn't support this directly. The execution path
	 * then will be:
	 * <p>
	 * {@code Simple Java Mail client -> JavaMail -> anonymous authentication with local proxy bridge -> full authentication with remote SOCKS proxy -> SMTP server}.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyUsername(@Nullable @Cli.Optional String proxyUsername);
	/**
	 * Sets the optional password to authenticate with the proxy.
	 * <p>
	 * <strong>Note:</strong> this is only works in combination with the {@value org.simplejavamail.internal.modules.AuthenticatedSocksModule#NAME}.
	 *
	 * @see #withProxyUsername(String)
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyPassword(@Nullable @Cli.Optional String proxyPassword);
	/**
	 * Relevant only when using username authentication with a proxy.
	 * <p>
	 * Overrides the port used by the temporary SOCKS5 relay between Jakarta Mail and the authenticated remote proxy. The relay binds to the JVM's
	 * loopback address and owns this port while this Mailer has active proxy operations.
	 * <p>
	 * Port {@code 0} lets the operating system choose an available port every time the bridge starts. This avoids collisions between separate
	 * authenticated-proxy Mailers. Use a positive port when an application needs the bridge to bind to a fixed port. Anonymous proxy connections do
	 * not use a bridge port.
	 * <p>
	 * Defaults to automatic allocation ({@value DEFAULT_PROXY_BRIDGE_PORT}) if no custom default property was configured.
	 * <p>
	 * <strong>Note:</strong> this only works in combination with the {@value org.simplejavamail.internal.modules.AuthenticatedSocksModule#NAME}.
	 *
	 * @param proxyBridgePort The loopback port to use, or {@code 0} to allocate an available port automatically.
	 *
	 * @see #withProxyUsername(String)
	 */
	T withProxyBridgePort(@NotNull Integer proxyBridgePort);
	/**
	 * Controls whether client-side validation findings block sending or are reported as warnings.
	 * <p>
	 * When set to {@code true}, sender and recipient completeness checks, the configured email address validator and CRLF injection scans still run,
	 * but their findings are logged and sending continues. The default {@code false} keeps these validations blocking.
	 *
	 * @param disableAllClientValidation Whether client-side validation findings should be non-blocking. Defaults to
	 *                                   {@value DEFAULT_DISABLE_ALL_CLIENTVALIDATION}.
	 */
	T disablingAllClientValidation(@NotNull Boolean disableAllClientValidation);
	/**
	 * Sets the same socket timeout for connecting, reading and writing. This is not a deadline for the whole send.
	 * <p>
	 * When you supply your own Session, its existing timeouts remain unchanged until you call this method or a factory lock requires a value.
	 * For example, a Session with a five-second read timeout keeps that value; calling {@code withSessionTimeout(Duration.ofSeconds(1))} deliberately replaces
	 * all three socket timeouts with one second. Ordinary SJM connection defaults do not replace caller-supplied settings, even when the Session
	 * previously belonged to another Mailer. Additional Jakarta Mail properties retain their configured overlay behavior.
	 * <p>
	 * The property names follow the selected {@code smtp} or {@code smtps} protocol:
	 * <ul>
	 *     <li>{@link TransportStrategy#propertyNameConnectionTimeout()}</li>
	 *     <li>{@link TransportStrategy#propertyNameTimeout()}</li>
	 *     <li>{@link TransportStrategy#propertyNameWriteTimeout()}</li>
	 * </ul>
	 * On owned managed Angus Sessions, enabled write timeouts share one lazy daemon scheduler per Session/protocol. Its worker stops after the
	 * last physical connection is disposed; returning a pooled lease or aborting a raw socket is not disposal. A supplied
	 * {@code mail.smtp.executor.writetimeout} (or {@code mail.smtps} equivalent) remains application-owned and is not shut down by the Mailer.
	 * Caller-owned Sessions and other providers keep their existing timeout-resource ownership. This is not a total-send deadline.
	 * <p>
	 * The builder getter reports its configured choice. After construction, {@link OperationalConfig#getSessionTimeout()} reports the common
	 * configured timeout, or {@code null} if a caller-supplied Session or locked raw properties leave different, missing or unusable values.
	 * A custom protocol cannot use these SMTP convenience mappings; configure its provider-specific settings through {@link #withProperty(String, Object)}.
	 *
	 * @param sessionTimeout Socket timeout, exactly representable as an integer number of milliseconds.
	 * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withSessionTimeout(@NotNull Duration sessionTimeout);
	/**
	 * Limits local submission attempts to the given count in any rolling period. For example, use {@code (30, Duration.ofMinutes(1))}
	 * for a service that allows thirty messages per minute. There is no limit by default. Calling this again replaces the previous rule.
	 * <p>
	 * Sends wait on their existing caller or worker thread before borrowing a connection. Waiting counts against the total send timeout
	 * and can be cancelled. Simple batches and open-connection scopes retain their shared connection while waiting between emails;
	 * long waits can encounter the SMTP server's idle timeout. The connection is not silently replaced.
	 * <p>
	 * One provider invocation counts as an attempt, even if it rejects the email or fails. Connection and MIME-preparation failures before
	 * invocation consume nothing. Validation, rehearsal, probing and logging-only mode do not count. CustomMailer callbacks count once;
	 * their internal retries and additional sends are outside this limit. This is not a persistent or account-wide provider quota.
	 * <p>
	 * The selected SMTP configuration supplies the limit, including when a pool cluster chooses another Mailer's connection.
	 * By default each registration has its own allowance; use {@link #withRateLimitGroup(String)} to share it within a factory.
	 * Properties: {@code simplejavamail.smtp.ratelimit.messages.limit} and {@code simplejavamail.smtp.ratelimit.messages.period}.
	 *
	 * @param count Positive maximum number of attempted submissions.
	 * @param period Positive rolling period representable in nanoseconds; CLI/property text uses ISO-8601, for example {@code PT1M}.
	 * @see #withRecipientRateLimit(int, Duration)
	 * @see #withRateLimitBurstsAllowed(boolean)
	 */
	T withMessageRateLimit(int count, @NotNull Duration period);
	/**
	 * Limits the number of intended envelope recipients in any rolling period, including BCC and delivery-list overrides. Duplicate
	 * recipient occurrences count separately. For example, {@code (100, Duration.ofMinutes(1))} allows one hundred recipients per minute.
	 * An email with more recipients than the whole allowance fails instead of waiting forever or being split into multiple messages.
	 * <p>
	 * Disabled by default; calling this again replaces the rule. When a message limit also exists, both must permit the submission.
	 * Accounting, waiting and sharing follow {@link #withMessageRateLimit(int, Duration)}; rejected recipients are not refunded.
	 * Properties: {@code simplejavamail.smtp.ratelimit.recipients.limit} and {@code simplejavamail.smtp.ratelimit.recipients.period}.
	 *
	 * @param count Positive maximum number of intended envelope-recipient occurrences.
	 * @param period Positive rolling period representable in nanoseconds; CLI/property text uses ISO-8601, for example {@code PT1M}.
	 */
	T withRecipientRateLimit(int count, @NotNull Duration period);
	/**
	 * Shares sending allowance between configurations using this name within the same SimpleJavaMail factory. For example, two Mailers
	 * using {@code company-account} and thirty messages per minute share thirty, not sixty. Their message/recipient rules and burst setting
	 * must agree, or construction fails. Closing a participant does not reset the group's recent history.
	 * <p>
	 * A separate factory or process has independent allowance, even with the same group name. This name does not identify a connection-pool
	 * cluster or discover an SMTP account. Without a name, each registered configuration has a private allowance. Naming a group alone
	 * does not enable a rate limit. Property: {@code simplejavamail.smtp.ratelimit.group}.
	 *
	 * @param group Nonblank sharing name. A later call replaces it; reset to use private allowance again.
	 */
	T withRateLimitGroup(@NotNull String group);
	/**
	 * Allows available rolling-window allowance to be used immediately ({@code true}, the default), or additionally spreads submissions
	 * ({@code false}). Thirty messages per minute then gives two seconds between local submission starts; this is an example, not a default.
	 * A ten-recipient email at one hundred recipients per minute gives six seconds before the next start. Both configured rules still apply.
	 * <p>
	 * The first send can start immediately. Slow sends can overlap; there is no extra delay after completion or catch-up credit after idle
	 * time. Waiting sends are served in arrival order at the gate, so a larger email can delay smaller ones behind it. This controls local
	 * provider invocations, not network bandwidth, server-observed timing or final delivery. Setting only this preference enables no limit.
	 * Property: {@code simplejavamail.smtp.ratelimit.allowbursts}.
	 *
	 * @param allowed Whether immediate bursts within the rolling ceiling are allowed. A later call replaces the setting.
	 */
	T withRateLimitBurstsAllowed(boolean allowed);
	/** Removes the message rule, including a property-supplied rule, without changing the recipient rule. */
	T resetMessageRateLimit();
	/** Removes the recipient rule, including a property-supplied rule, without changing the message rule. */
	T resetRecipientRateLimit();
	/** Removes the group name, restoring private allowance without changing either rule. */
	T resetRateLimitGroup();
	/** Restores the default of allowing bursts; does not create a rate limit. */
	T resetRateLimitBurstsAllowed();
	/**
	 * @return Configured message rule, or {@code null} when disabled.
	 * @see #withMessageRateLimit(int, Duration)
	 */
	@Nullable SendingRateLimit getMessageRateLimit();
	/**
	 * @return Configured recipient rule, or {@code null} when disabled.
	 * @see #withRecipientRateLimit(int, Duration)
	 */
	@Nullable SendingRateLimit getRecipientRateLimit();
	/**
	 * @return Sharing name, or {@code null} for private allowance.
	 * @see #withRateLimitGroup(String)
	 */
	@Nullable String getRateLimitGroup();
	/** @see #withRateLimitBurstsAllowed(boolean) */
	boolean isRateLimitBurstsAllowed();
	/**
	 * Sets the local/source address without changing the already configured local/source port.
	 *
	 * @param localBindAddress Local/source address or host name to bind the SMTP socket to.
	 *
	 * @see #withLocalBindAddress(String, Integer)
	 * @see #clearLocalBindAddress()
	 * @see TransportStrategy#propertyNameLocalAddress()
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more detailed API")
	T withLocalBindAddress(@Nullable @Cli.Optional String localBindAddress);
	/**
	 * Configures the local/source address and optional local/source port that Jakarta Mail should bind the outgoing
	 * SMTP socket to.
	 * <p>
	 * This is useful on machines with multiple local IP addresses when the remote SMTP server expects the connection
	 * to originate from a specific address. Simple Java Mail applies this to the correct Jakarta Mail Session property
	 * for the configured {@link TransportStrategy}.
	 * <p>
	 * Leave {@code localBindPort} empty unless you specifically need to bind a fixed local port. Binding the local port is
	 * an advanced setting and can cause conflicts if another connection already uses that port.
	 * <p>
	 * If a remote SOCKS proxy is used, the SMTP server usually sees the proxy's outgoing IP address. With Simple Java
	 * Mail's authenticated SOCKS bridge, this setting binds Jakarta Mail's socket to the local bridge/proxy path, not
	 * the proxy server's own outbound socket.
	 * <p>
	 * A caller-supplied Session keeps its local binding unless you use this setter. A null argument removes that direct Session property;
	 * an inherited Properties default cannot be removed this way. See {@link MailerFromSessionBuilder#usingSession(jakarta.mail.Session)}.
	 *
	 * @param localBindAddress Local/source address or host name to bind the SMTP socket to.
	 * @param localBindPort Optional local/source port to bind the SMTP socket to.
	 *
	 * @see #clearLocalBindAddress()
	 * @see TransportStrategy#propertyNameLocalAddress()
	 * @see TransportStrategy#propertyNameLocalPort()
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.localaddress">mail.smtp.localaddress</a>
	 */
	T withLocalBindAddress(@Nullable @Cli.Optional String localBindAddress, @Nullable @Cli.Optional Integer localBindPort);
	/**
	 * Configures the hostname that Jakarta Mail should send as the SMTP client identity in the {@code EHLO} or
	 * {@code HELO} command.
	 * <p>
	 * This is useful when corporate SMTP relays expect a stable recognizable client hostname, or when mail-server logs,
	 * tracing, audit trails and SMTP diagnostics should identify application traffic consistently. Simple Java Mail applies
	 * this to the correct Jakarta Mail Session property for the configured {@link TransportStrategy}.
	 * <p>
	 * This setting does not bind the outgoing socket to a local IP address; use {@link #withLocalBindAddress(String, Integer)}
	 * for that. It also does not change the SMTP envelope sender address.
	 * <p>
	 * Provide the hostname shape expected by your SMTP relay, typically a DNS host name or FQDN. Simple Java Mail deliberately
	 * does not over-validate this value because corporate relay identifiers can be environment-specific.
	 *
	 * @param smtpClientHostname Hostname to send in the SMTP {@code EHLO} or {@code HELO} command.
	 *
	 * @see #clearSmtpClientHostname()
	 * @see TransportStrategy#propertyNameLocalHost()
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.localhost">mail.smtp.localhost</a>
	 */
	T withSmtpClientHostname(@Nullable @Cli.Optional String smtpClientHostname);
	/**
	 * Allows an unchanged send attempt through a known legacy SMTP server that does not announce support for the email's addresses or content.
	 * <p>
	 * For example, an accented display name is different from an accented mailbox address:
	 * <ul>
	 * <li>{@code José <jose@example.org>}: only the display name contains an accent. Simple Java Mail MIME-encodes the name, so it does not require
	 * special support for internationalized addresses. Ordinary Unicode subjects and message text also use MIME encoding and do not need this setting.</li>
	 * <li>{@code josé@example.org}: the accent is part of the actual mailbox address. MIME encoding cannot make that address ASCII; sending it normally
	 * requires the server to announce {@code SMTPUTF8} support.</li>
	 * </ul>
	 * Simple Java Mail detects and uses advertised SMTPUTF8 support automatically; leave this setting disabled for those servers. If the server does
	 * not announce that support, Simple Java Mail rejects a send requiring it before submitting the email. It does not assume the server can handle it.
	 * <p>
	 * Enable this only when you have verified that your server and its onward delivery route can handle the original address and content despite
	 * not announcing support. For example, an older relay you control might correctly accept {@code josé@example.org} without announcing SMTPUTF8.
	 * This option lets Simple Java Mail try that address unchanged; it does not turn {@code josé@example.org} into {@code jose@example.org}, or make
	 * an incompatible server support internationalized addresses. Server acceptance still does not prove onward preservation or final delivery.
	 * <p>
	 * The same permission covers raw UTF-8 headers and raw 8-bit bodies, such as those already present in an exact EML file. Those are different from
	 * the normally MIME-encoded subjects and bodies described above. Defaults to {@value #DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT}. The bundled Angus adapter
	 * still uses advertised SMTPUTF8/8BITMIME extensions when available; otherwise it attempts the unchanged submission without automatically adding
	 * unadvertised extension parameters. This operates outside negotiated SMTPUTF8/8BITMIME guarantees. Rejection is reported normally, without a retry
	 * that changes the address or content.
	 * <p>
	 * This does not rewrite addresses or exact/signed content, bypass malformed/binary-content checks, weaken TLS or REQUIRETLS, or relax explicit DSN
	 * requirements. It does not override explicitly disabled UTF-8 encoding. Repeated calls replace the choice; {@code false} disables the opt-in.
	 * {@link #resetLegacySmtpContentSupport()} restores the built-in default, not a captured property value. Configuration locks still apply.
	 * In a shared connection pool, the selected server's Mailer configuration applies. CustomMailer and third-party providers retain their own behavior.
	 * Caller-owned ordinary Angus Sessions retain their Session-wide SMTPUTF8 declaration behavior. Library-owned stock Angus Sessions negotiate
	 * per message, also with custom socket factories; the factories and their settings are preserved without adding physical-abort support.
	 *
	 * @param enabled Whether to try the original address and content even when this verified legacy server does not announce the required support.
	 * @see OperationalConfig#isLegacySmtpContentSupportEnabled()
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc6531.html#section-3.2">SMTPUTF8 negotiation</a>
	 * @see <a href="https://www.rfc-editor.org/rfc/rfc6152.html#section-3">8BITMIME negotiation</a>
	 */
	T withLegacySmtpContentSupport(boolean enabled);
	/**
	 * Restores the built-in legacy-content setting rather than the captured property.
	 * @see #withLegacySmtpContentSupport(boolean)
	 */
	T resetLegacySmtpContentSupport();
	/**
	 * Sets the email address validator used when validating and sending emails using the current <code>Mailer</code> instance.
	 * <p>
	 * Defaults to {@link JMail#strictValidator()}.
	 *
	 * @see EmailValidator
	 * @see #clearEmailValidator()
	 * @see #resetEmailValidator()
	 */
	T withEmailValidator(@NotNull EmailValidator emailValidator);
	/**
	 * Sets a maximum size for emails (as MimeMessage) in bytes. If an email exceeds this size, exception @{@link EmailTooBigException} will be thrown (as the cause).
	 *
	 * @param maximumEmailSize Maximum size of an email (as MimeMessage) in bytes.
	 * @see #clearMaximumEmailSize()
	 */
	T withMaximumEmailSize(int maximumEmailSize);
	/**
	 * <strong>For advanced use cases.</strong>
	 * <p>
	 * Sets the executor service used for asynchronous operations, including individual sends and simple batches. This lets the caller manage the thread
	 * pool, threads and concurrency characteristics directly.
	 * <p>
	 * Without a caller-provided executor, the built-in executor honors explicit worker count and keep-alive choices, whether supplied through
	 * Java or properties, with or without the batch module. Unspecified worker count defaults to one without the batch module or
	 * {@value #DEFAULT_POOL_SIZE} with it. Unspecified keep-alive defaults to zero without the batch module or
	 * {@link #DEFAULT_POOL_KEEP_ALIVE_TIME} with it. A positive keep-alive lets core workers stop once idle.
	 * The executor uses named non-daemon workers and an unbounded queue unless configured through {@link #withAsyncQueueCapacity(int)}.
	 * <p>
	 * Simple Java Mail will <strong>not</strong> shut down the provided executor service when the Mailer or its connection pool is closed. The caller remains
	 * responsible for the executor's lifecycle. Non-default built-in queue settings cannot be combined with a caller-owned executor and are rejected
	 * when building the Mailer. Call {@link #resetAsyncQueue()} to discard property-backed queue settings when taking over executor configuration.
	 *
	 * @param executorService A caller-owned executor service replacing Simple Java Mail's default executor.
	 * @see <a href="https://www.simplejavamail.org/configuration.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
	 */
	T withExecutorService(@NotNull ExecutorService executorService);
	/**
	 * Bounds pending operations in this Mailer's built-in executor, separately from running workers and SMTP connections.
	 * The default, {@code -1}, keeps the queue unbounded; {@code 0} allows work only when a worker can accept it immediately.
	 * A positive value allows that many waiting operations. Queued operations are FIFO, but concurrent completion order is not guaranteed.
	 * <p>
	 * One ordinary send, receipt-returning send, simple batch, or asynchronous connection test occupies one place. A simple batch remains one lazy,
	 * sequential operation, regardless of its email count. Blocking sends and the standalone BatchTransportExecutor do not use this queue.
	 * Destination selection and ordinary email preparation run after admission, on executor execution. This bounds waiting sends, not message size
	 * or application memory. A direct/caller-runs executor can still perform the work on the calling thread.
	 * <p>
	 * Saturation uses {@link #withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)}. Rejected operations acquire no SMTP or proxy resources.
	 * Queue settings cannot be combined with {@link #withExecutorService(ExecutorService)}: configure that caller-owned executor itself instead.
	 *
	 * @param capacity Maximum queued operations, or -1 to retain unbounded queuing.
	 * @see #resetAsyncQueue()
	 * @see Mailer#getAsyncQueueSnapshot()
	 */
	T withAsyncQueueCapacity(int capacity);
	/**
	 * Chooses immediate rejection (the default) or bounded caller-side waiting when the built-in async queue is full.
	 * Neither policy runs SMTP work on the submitting thread. Rejection completes an async send exceptionally with {@link MailSendRejectedException};
	 * its observer runs on the submitting thread, before the method returns. A rejected simple batch does not open its iterable or notify per email.
	 * <p>
	 * WAIT_FOR_CAPACITY can block the submitting thread for {@link #withAsyncQueueWaitTimeout(Duration)}. Avoid it on event-loop threads.
	 * Waiting from a Mailer worker (for example inside an observer) also occupies that worker until admission succeeds or times out.
	 *
	 * @param overflowPolicy REJECT or WAIT_FOR_CAPACITY; only meaningful with a finite queue capacity.
	 * @see #withAsyncQueueCapacity(int)
	 */
	T withAsyncQueueOverflowPolicy(@NotNull AsyncQueueOverflowPolicy overflowPolicy);
	/**
	 * Sets the maximum caller-side wait for queue capacity under WAIT_FOR_CAPACITY. Defaults to 1000 milliseconds.
	 * This is an admission timeout, not an SMTP/session timeout or a total-send deadline. Interruption rejects admission and preserves the interrupt flag.
	 *
	 * @param waitTimeout Positive maximum admission wait, exactly representable as integer milliseconds; ignored by REJECT.
	 * @throws IllegalArgumentException If the duration is not positive, has fractional milliseconds or exceeds the integer-millisecond range.
	 * @see #withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withAsyncQueueWaitTimeout(@NotNull Duration waitTimeout);
	/** Restores unbounded queuing, immediate rejection policy, and the default 1000 millisecond admission wait. */
	T resetAsyncQueue();
	/** @return The current immutable built-in queue configuration, including builder overrides. */
	@NotNull
	AsyncQueueConfig getAsyncQueueConfig();
	/**
	 * Sets the built-in executor's worker count, independently of the batch module. Explicit Java and property values are honored.
	 * When unspecified, the count is one without the batch module or {@value #DEFAULT_POOL_SIZE} with it.
	 *
	 * @param threadPoolSize See main description.
	 *
	 * @see #resetThreadPoolSize()
	 * @see #withThreadPoolSize(Integer)
	 */
	T withThreadPoolSize(@NotNull Integer threadPoolSize);
	/**
	 * Sets how long idle built-in executor workers remain alive. A positive duration permits core workers to time out,
	 * allowing the JVM to exit after their work finishes. Zero keeps core workers alive until the Mailer is closed.
	 * Explicit Java and property values are honored independently of the batch module and of the worker-count choice.
	 * When unspecified, keep-alive is zero without the batch module or {@link #DEFAULT_POOL_KEEP_ALIVE_TIME} with it.
	 *
	 * @param threadPoolKeepAliveTime Nonnegative duration, exactly representable as integer milliseconds.
	 * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
	 *
	 * @see #resetThreadPoolKeepAliveTime()
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withThreadPoolKeepAliveTime(@NotNull Duration threadPoolKeepAliveTime);
	/**
	 * By defining a clusterKey, you can form clusters where other {@link Mailer} instances represent
	 * individual connection pools within the same cluster. Having multiple mailers using the same clusterKey
	 * means those mailes form a cluster where mail-send action are rotated over connection pools stemming from these
	 * mailer instances (this has implications for mailers defining connections differently from eachother, see documentation).
	 * <p>
	 * By default, a cluster key is uniquely generated, so for a single new mailer a new cluster is always generated,
	 * thus effectively nothing is clustered.
	 *
	 * @see <a href="https://www.simplejavamail.org/configuration.html#section-batch-and-clustering">Clustering with Simple Java Mail</a>
	 *
	 * @param clusterKey See main description.
	 */
	T withClusterKey(@NotNull UUID clusterKey);
	/**
	 * Configures the connection pool's core size (default {@value DEFAULT_CONNECTIONPOOL_CORE_SIZE}), which means the SMTP connection pool will keep X connections open at all times until shut down.
	 * Use zero to disable core replenishment; negative sizes are invalid. The effective core size cannot exceed the maximum after cluster overrides.
	 * The batch module checks the effective pool settings when the Mailer registers with it, before creating pool resources.
	 * Without that module, these settings are unused and do not prevent direct sending.
	 * Note that this also means that if you configure an auto-expiry timeout, these connections die off and new ones are created immediately to maintain core size.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolCoreSize See main description.
	 */
	T withConnectionPoolCoreSize(@NotNull Integer connectionPoolCoreSize);
	/**
	 * Sets the maximum number of connections to one server (default {@value DEFAULT_CONNECTIONPOOL_MAX_SIZE}). It must be at least one and no
	 * smaller than the effective core size after cluster overrides. Make sure your server can handle the load: a hundred concurrent connections
	 * are not useful if they degrade its performance through CPU throttling or network congestion.
	 * <p>
	 * In addition, if your server makes connections wait, it means threads will be waiting on the {@link jakarta.mail.Transport} instance to start their work load, instead of threads being blocked
	 * on a <em>claim</em> for an available {@code Transport} instance. In other words: by having an oversized connection pool, you inadvertently bypass the blocking claim mechanism of the
	 * connection pool and wait on the Transport directly instead.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolMaxSize See main description.
	 */
	T withConnectionPoolMaxSize(@NotNull Integer connectionPoolMaxSize);
	/**
	 * If {@code >0}, configures the connection pool to wait for a limited time after which the attempt to claim a Transport connection errors out.
	 * Use zero for a non-waiting claim; negative timeouts are invalid.
	 * The default {@link #DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT} is approximately 24.9 days.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolClaimTimeout Nonnegative duration, exactly representable as integer milliseconds.
	 * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withConnectionPoolClaimTimeout(@NotNull Duration connectionPoolClaimTimeout);
	/**
	 * If {@code >0}, configures the connection pool to automatically close connections after the configured idle duration (default {@link #DEFAULT_CONNECTIONPOOL_EXPIREAFTER}).
	 * Use zero to disable idle expiration; negative durations are invalid.
	 * <p>
	 * Note that if you combine this with {@link #withConnectionPoolCoreSize(Integer)} also {@code >0} (default is {@value DEFAULT_CONNECTIONPOOL_CORE_SIZE}), connections will keep
	 * closing and opening to keep core pool populated until shut down.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolExpireAfter Nonnegative duration, exactly representable as integer milliseconds.
	 * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withConnectionPoolExpireAfter(@NotNull Duration connectionPoolExpireAfter);
	/**
	 * Configures the age after which an available pooled connection becomes eligible for retirement, measured from the
	 * connection's creation time. Creation-age expiration is disabled when no value is configured.
	 * <p>
	 * This threshold is independent of {@link #withConnectionPoolExpireAfter(Duration) idle expiration}. When both
	 * thresholds are enabled, either can retire an available connection. Claiming or releasing a connection does not reset
	 * its creation age.
	 * <p>
	 * Expiration is checked asynchronously while a connection is available. Active connections are not interrupted and
	 * rapid reuse can postpone retirement, so this setting is not a strict maximum connection lifetime or a guarantee that
	 * a connection will be retired before a server-side timeout.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that
	 * cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the
	 * {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolExpireAfterCreation Positive duration, exactly representable as integer milliseconds.
	 * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
	 * @see #clearConnectionPoolExpireAfterCreation()
	 */
	@Cli.ExcludeApi(reason = "The CLI retains numeric millisecond arguments through its internal adapter")
	T withConnectionPoolExpireAfterCreation(@NotNull Duration connectionPoolExpireAfterCreation);
	/**
	 * Defines the various types of load balancing modes supported by the connection pool ion the
	 * <a href="https://www.simplejavamail.org/configuration.html#section-batch-and-clustering">batch-module</a>.
	 * <p>
	 * This is only relevant if you have multiple mail servers in one or more clusters. When using clustered batch sending,
	 * the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param loadBalancingStrategy See main description.
	 */
	T withConnectionPoolLoadBalancingStrategy(@NotNull LoadBalancingStrategy loadBalancingStrategy);
	/**
	 * Configures Angus Mail to trust certificates from the provided SMTP hosts without requiring their issuer to be present in the JVM trust store.
	 * Server identity verification is a separate check and can be controlled with {@link #verifyingServerIdentity(boolean)}.
	 * <p>
	 * Passing an empty list removes the host-specific exception. With {@link #trustingAllHosts(boolean)} set to {@code false}, which is the default,
	 * normal JVM trust-store validation then applies.
	 * <p>
	 * <strong>Security warning:</strong> This bypasses normal certificate-authority validation for the named hosts. Keep server identity verification
	 * enabled, and prefer adding a private certificate authority to the JVM trust store when possible.
	 * <p>
	 * This method sets the transport-specific {@code mail.*.ssl.trust} property to a space-separated list of the provided {@code hosts}. If the
	 * provided list is empty, the property is unset.
	 *
	 * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.trust"><code>mail.smtp.ssl.trust</code></a>
	 * @see #trustingAllHosts(boolean)
	 * @see #verifyingServerIdentity(boolean)
	 *
	 * @param sslHostsToTrust See main description.
	 */
	T trustingSSLHosts(String... sslHostsToTrust);
	/**
	 * Controls whether Angus Mail trusts certificates from every SMTP host without requiring their issuer to be present in the JVM trust store.
	 * Defaults to {@value #DEFAULT_TRUST_ALL_HOSTS}, so normal JVM trust-store validation applies unless this method or
	 * {@link #trustingSSLHosts(String...)} enables an exception.
	 * <p>
	 * <strong>Security warning:</strong> Setting this to {@code true} disables certificate-authority trust validation for all SMTP hosts. Server identity
	 * verification remains a separate check and should stay enabled, but it does not make trusting every certificate safe against an active attacker.
	 * Prefer configuring the JVM trust store or, when that is not possible, a narrow exception through {@link #trustingSSLHosts(String...)}.
	 *
	 * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.trust">mail.smtp.ssl.trust</a>
	 * @see #trustingSSLHosts(String...)
	 * @see #verifyingServerIdentity(boolean)
	 *
	 * @param trustAllHosts See main description.
	 */
	T trustingAllHosts(boolean trustAllHosts);
	/**
	 * Controls whether Angus Mail verifies that the SMTP server certificate matches the host used for the connection. Defaults to
	 * {@value #DEFAULT_VERIFY_SERVER_IDENTITY}, including for opportunistic TLS with {@link TransportStrategy#SMTP}.
	 * <p>
	 * Hostname verification and certificate-authority trust are independent checks. Normal secure TLS requires both a trusted certificate chain and a
	 * matching server identity. Disabling this check is intended only for controlled compatibility or testing scenarios.
	 *
	 * @see <a href="https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html#mail.smtp.ssl.checkserveridentity">mail.smtp.ssl.checkserveridentity</a>
	 * @see #trustingAllHosts(boolean)
	 * @see #trustingSSLHosts(String...)
	 *
	 * @param verifyingServerIdentity See main description.
	 */
	T verifyingServerIdentity(boolean verifyingServerIdentity);
	/**
	 * Restores blocking client-side validation by resetting this option to its default ({@value #DEFAULT_DISABLE_ALL_CLIENTVALIDATION}).
	 *
	 * @see #disablingAllClientValidation(Boolean)
	 */
	T resetDisableAllClientValidations();
	/**
	 * Resets session time to its default ({@link DEFAULT_SESSION_TIMEOUT}).
	 *
	 * @see #withSessionTimeout(Duration)
	 */
	T resetSessionTimeout();
	/**
	 * Resets the email validator to {@link JMail#strictValidator()}.
	 *
	 * @see #withEmailValidator(EmailValidator)
	 * @see #clearEmailValidator()
	 */
	T resetEmailValidator();
	/**
	 * Restores a Mailer-owned executor using the current worker and queue settings. Explicit worker count and keep-alive choices
	 * are honored with or without the batch module. This does not reset queue settings.
	 *
	 * @see #withExecutorService(ExecutorService)
	 * @see #resetAsyncQueue()
	 */
	T resetExecutorService();
	/**
	 * Selects the library default worker count of {@value #DEFAULT_POOL_SIZE}, including without the batch module.
	 *
	 * @see #withThreadPoolSize(Integer)
	 */
	T resetThreadPoolSize();
	/**
	 * Selects the library default keep-alive of {@link #DEFAULT_POOL_KEEP_ALIVE_TIME}, including without the batch module.
	 *
	 * @see #withThreadPoolKeepAliveTime(Duration)
	 */
	T resetThreadPoolKeepAliveTime();
	/**
	 * Reset trusting any host; trust all hosts is set to {@value #DEFAULT_TRUST_ALL_HOSTS}.
	 *
	 * @see #trustingAllHosts(boolean)
	 * @see #trustingSSLHosts(String...)
	 * @see #verifyingServerIdentity(boolean)
	 */
	T resetTrustingAllHosts();
	/**
	 * Reset verifying the server's identity to {@value #DEFAULT_VERIFY_SERVER_IDENTITY}.
	 *
	 * @see #verifyingServerIdentity(boolean)
	 * @see #trustingSSLHosts(String...)
	 * @see #trustingAllHosts(boolean)
	 */
	T resetVerifyingServerIdentity();
	/**
	 * Reset the cluster key to empty, so it will be generated uniquely, avoiding clustering with any other {@link Mailer}.
	 *
	 * @see #withClusterKey(UUID)
	 */
	T resetClusterKey();
	/**
	 * Resets connection pool core size to its default ({@value #DEFAULT_CONNECTIONPOOL_CORE_SIZE}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolCoreSize(Integer)
	 */
	T resetConnectionPoolCoreSize();
	/**
	 * Resets connection pool max size to its default ({@value #DEFAULT_CONNECTIONPOOL_MAX_SIZE}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolMaxSize(Integer)
	 */
	T resetConnectionPoolMaxSize();
	/**
	 * Resets the connection pool claim timeout to its default ({@link #DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT}, approximately 24.9 days).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolClaimTimeout(Duration)
	 */
	@Cli.ExcludeApi(reason = "The CLI retains its existing millisecond option name through an internal adapter")
	T resetConnectionPoolClaimTimeout();
	/**
	 * Resets connection pool idle expiration to its default ({@link #DEFAULT_CONNECTIONPOOL_EXPIREAFTER}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolExpireAfter(Duration)
	 */
	@Cli.ExcludeApi(reason = "The CLI retains its existing millisecond option name through an internal adapter")
	T resetConnectionPoolExpireAfter();
	/**
	 * Disables connection pool creation-age expiration by removing the configured duration.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the
	 * {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Cli.ExcludeApi(reason = "The CLI retains its existing millisecond option name through an internal adapter")
	T clearConnectionPoolExpireAfterCreation();
	/**
	 * Resets connection pool load balancing strategy to its default ({@value #DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	T resetConnectionPoolLoadBalancingStrategy();
	/**
	 * Empties all proxy configuration.
	 */
	T clearProxy();
	/**
	 * Clears the configurable JMail email address validator. Required sender and recipient checks, encoded-word address protection and CRLF injection
	 * scanning remain active.
	 *
	 * @see #withEmailValidator(EmailValidator)
	 * @see #resetEmailValidator()
	 * @see #disablingAllClientValidation(Boolean)
	 */
	T clearEmailValidator();
	/**
	 * Makes the maximum email size <code>null</code>, meaning no size check will be performed.
	 *
	 * @see #withMaximumEmailSize(int)
	 */
	T clearMaximumEmailSize();
	/**
	 * Removes all trusted hosts from the list.
	 *
	 * @see #trustingSSLHosts(String...)
	 * @see #trustingAllHosts(boolean)
	 * @see #verifyingServerIdentity(boolean)
	 */
	T clearTrustedSSLHosts();
	/**
	 * Clears the configured local/source address and port.
	 *
	 * @see #withLocalBindAddress(String, Integer)
	 */
	T clearLocalBindAddress();
	/**
	 * Clears the configured SMTP {@code EHLO}/{@code HELO} client hostname.
	 *
	 * @see #withSmtpClientHostname(String)
	 */
	T clearSmtpClientHostname();
	/**
	 * @see #withProxyHost(String)
	 */
	@Nullable
	String getProxyHost();
	/**
	 * @see #withProxyPort(Integer)
	 */
	@Nullable
	Integer getProxyPort();
	/**
	 * @see #withProxyUsername(String)
	 */
	@Nullable
	String getProxyUsername();
	/**
	 * @see #withProxyPassword(String)
	 */
	@Nullable
	String getProxyPassword();
	/**
	 * @see #withProxyBridgePort(Integer)
	 */
	@Nullable
	Integer getProxyBridgePort();
	/**
	 * @see #disablingAllClientValidation(Boolean)
	 */
	boolean isDisableAllClientValidation();
	/**
	 * @see #withSessionTimeout(Duration)
	 */
	@Nullable
	Duration getSessionTimeout();
	/**
	 * @see #withLocalBindAddress(String)
	 * @see #withLocalBindAddress(String, Integer)
	 */
	@Nullable
	String getLocalBindAddress();
	/**
	 * @see #withLocalBindAddress(String, Integer)
	 */
	@Nullable
	Integer getLocalBindPort();
	/**
	 * @see #withSmtpClientHostname(String)
	 */
	@Nullable
	String getSmtpClientHostname();
	/** @see #withLegacySmtpContentSupport(boolean) */
	boolean isLegacySmtpContentSupportEnabled();
	/**
	 * @see #withEmailValidator(EmailValidator)
	 */
	@Nullable
	EmailValidator getEmailValidator();
	/**
	 * @see #withMaximumEmailSize(int)
	 */
	@Nullable
	Integer getMaximumEmailSize();
	/**
	 * Returns the user set ExecutorService or else null as the default ExecutorService is not created until the {@link org.simplejavamail.api.mailer.config.OperationalConfig} is created for the
	 * new {@link Mailer} instance.
	 *
	 * @see #withExecutorService(ExecutorService)
	 */
	@Nullable
	ExecutorService getExecutorService();
	/**
	 * @see #withThreadPoolSize(Integer)
	 */
	@NotNull
	Integer getThreadPoolSize();
	/**
	 * @see #withThreadPoolKeepAliveTime(Duration)
	 */
	@NotNull
	Duration getThreadPoolKeepAliveTime();
	/**
	 * @see #withClusterKey(UUID)
	 */
	@Nullable
	UUID getClusterKey();
	/**
	 * @see #withConnectionPoolCoreSize(Integer)
	 */
	@NotNull
	Integer getConnectionPoolCoreSize();
	/**
	 * @see #withConnectionPoolMaxSize(Integer)
	 */
	@NotNull
	Integer getConnectionPoolMaxSize();
	/**
	 * @see #withConnectionPoolClaimTimeout(Duration)
	 */
	@NotNull
	Duration getConnectionPoolClaimTimeout();
	/**
	 * @see #withConnectionPoolExpireAfter(Duration)
	 */
	@NotNull
	Duration getConnectionPoolExpireAfter();
	/**
	 * @see #withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Nullable
	Duration getConnectionPoolExpireAfterCreation();
	/**
	 * @see #withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy();
	/**
	 * Returns an unmodifiable copy of the configured host names.
	 * @see #trustingSSLHosts(String...)
	 */
	@NotNull
	List<String> getSslHostsToTrust();
	/**
	 * @see #trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();
	/**
	 * @see #verifyingServerIdentity(boolean)
	 */
	boolean isVerifyingServerIdentity();

}