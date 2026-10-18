package org.simplejavamail.api.mailer;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Builder superclass which contains API to take care of all generic Mailer properties unrelated to the SMTP server
 * (host, port, username, password and transport strategy).
 * <p>
 * Obtain a regular or Session-based builder from a configured {@code SimpleJavaMail} factory. Each requested builder is fresh and retains the factory's
 * immutable configuration snapshot.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerGenericBuilder<T extends MailerGenericBuilder<?>> {
	/**
	 * Total connection setup attempts per destination visit, including the first.
	 * @see FailoverPolicy.Builder#withPrimaryConnectionAttempts(int)
	 */
	int DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS = 1;
	/**
	 * No delay between additional connection setup attempts unless configured.
	 * @see FailoverPolicy.Builder#withPrimaryConnectionRetryDelay(Duration)
	 */
	Duration DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY = Duration.ZERO;
	/**
	 * Reconsider a failed primary after thirty seconds; this does not delay use of a working failover destination.
	 * @see FailoverPolicy.Builder#withPrimaryRecheckInterval(Duration)
	 */
	Duration DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL = Duration.ofSeconds(30);
	/**
	 * Fixed recheck intervals unless increasing backoff is configured. The default maximum follows the chosen initial interval.
	 * @see FailoverPolicy.Builder#withPrimaryRecheckBackoff(int, Duration)
	 */
	int DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER = 1;
	/**
	 * Stop on authentication rejection unless failover for that failure is explicitly enabled.
	 * @see FailoverPolicy.Builder#withFailoverOnAuthenticationFailure(boolean)
	 */
	boolean DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE = false;
	/**
	 * Stop on certificate or server-identity verification failure unless failover for that failure is explicitly enabled.
	 * @see FailoverPolicy.Builder#withFailoverOnCertificateVerificationFailure(boolean)
	 */
	boolean DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE = false;

	/**
	 * Fixed submission retry delays unless increasing backoff is configured. Attempts and initial delay have no built-in default.
	 * @see MailSendRetryPolicy.Builder#withRetryBackoff(int, Duration)
	 */
	int DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER = 1;
	/**
	 * No variation in submission retry delays unless jitter is configured.
	 * @see MailSendRetryPolicy.Builder#withRetryJitter(double)
	 */
	double DEFAULT_MAIL_SEND_RETRY_JITTER = 0.0;

	/**
	 * Default suspended asynchronous retries; independent of the executor's initial admission queue.
	 * @see MailSendRetryPolicy.Builder#withWaitingCapacity(int)
	 */
	int DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY = 100;
	/**
	 * Default maximum serialized replay message: 32 MiB, including MIME headers and encoded attachments.
	 * @see MailSendRetryPolicy.Builder#withMaximumMessageSize(long)
	 */
	long DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE = 32L * 1024 * 1024;
	/**
	 * Default aggregate replay-content budget per initiating Mailer: 128 MiB; not a total JVM heap limit.
	 * @see MailSendRetryPolicy.Builder#withMemoryLimit(long)
	 */
	long DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT = 128L * 1024 * 1024;

	/**
	 * Tries an unchanged email again after this Mailer's SMTP service temporarily refuses it, but only when the submission
	 * receipt proves the entire envelope can safely be replayed. Partial acceptance, a lost final reply, permanent rejection,
	 * cancellation and cleanup failure never qualify. Disabled unless the policy supplies an attempt count and initial delay.
	 * <p>
	 * The actual selected Mailer supplies the schedule: when clustering or failover selects another Mailer, its schedule is used,
	 * or no automatic retry when it has none. Retries remain with that selected destination. The initiating Mailer supplies the
	 * waiting and replay limits from its own policy and owns completion, observer and deadline. Resource limits alone do not enable
	 * retries; they can bound a send that selects another retry-enabled Mailer. One logical send produces one final observer outcome.
	 * <p>
	 * Retry-enabled SMTP sends retain a bounded, unchanged MIME snapshot; adapters must support repeatable preparation.
	 * Ordinary async backoff releases its send worker and connection. Synchronous sends and retained batch/open-connection scopes
	 * wait on their existing thread; retained connections can encounter idle timeouts. CustomMailer and logging-only mode do not retry.
	 * @param policy Non-null schedule and resource limits; a repeated call replaces the entire policy, including property-loaded values.
	 */
	@Cli.ExcludeApi(reason = "The retry policy is configured through typed CLI fields instead of a Java object.")
	@NotNull T withMailSendRetryPolicy(@NotNull MailSendRetryPolicy policy);

	/** Disables this Mailer's retry schedule and restores built-in resource limits, including property-loaded values, unless locks conflict. */
	@NotNull T clearMailSendRetryPolicy();

	/**
	 * Returns the resolved settings, including resource limits even when retries are disabled.
	 * @see #withMailSendRetryPolicy(MailSendRetryPolicy)
	 */
	@NotNull MailSendRetryPolicy getMailSendRetryPolicy();
	/** No total send deadline unless explicitly configured. */
	@Nullable Duration DEFAULT_MAIL_SEND_TIMEOUT = null;
	/** Available sending allowance may be used immediately unless spreading is explicitly requested. */
	boolean DEFAULT_RATE_LIMIT_BURSTS_ALLOWED = true;
	/**
	 * {@value}
	 *
	 * @see #trustingAllHosts(boolean)
	 */
	boolean DEFAULT_TRUST_ALL_HOSTS = false;
	/**
	 * {@value}
	 *
	 * @see #verifyingServerIdentity(boolean)
	 */
	boolean DEFAULT_VERIFY_SERVER_IDENTITY = true;
	/**
	 * Plain SMTP attempts STARTTLS when the server offers it unless explicitly disabled.
	 * @see MailerRegularBuilder#withOpportunisticTLS(boolean)
	 */
	boolean DEFAULT_OPPORTUNISTIC_TLS = true;
	/**
	 * The default maximum timeout value for the transport socket is <code>{@value}</code> milliseconds (affects socket connect-,
	 * read- and write timeouts). Can be overridden from a config file or through System variable.
	 */
	int DEFAULT_SESSION_TIMEOUT_MILLIS = 60_000;
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolSize(Integer)
	 */
	int DEFAULT_POOL_SIZE = 10;
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolKeepAliveTime(Integer)
	 */
	int DEFAULT_POOL_KEEP_ALIVE_TIME = 1;
	/**
	 * {@value} (unbounded).
	 *
	 * @see #withAsyncQueueCapacity(int)
	 */
	int DEFAULT_ASYNC_QUEUE_CAPACITY = -1;
	/**
	 * {@link AsyncQueueOverflowPolicy#REJECT}.
	 *
	 * @see #withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
	 */
	AsyncQueueOverflowPolicy DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY = AsyncQueueOverflowPolicy.REJECT;
	/**
	 * {@value} milliseconds.
	 *
	 * @see #withAsyncQueueWaitTimeoutMillis(int)
	 */
	int DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS = 1000;
	/**
	 * {@value}
	 *
	 * @see #withConnectionPoolCoreSize(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_CORE_SIZE = 0;
	/**
	 * {@value}
	 *
	 * @see #withConnectionPoolMaxSize(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_MAX_SIZE = 4;
	/**
	 * {@value} ({@code Integer.MAX_VALUE}), approximately 24.9 days.
	 *
	 * @see #withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS = Integer.MAX_VALUE;
	/**
	 * {@value}
	 *
	 * @see #withConnectionPoolExpireAfterMillis(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS = 5000;
	/**
	 * {@value}
	 *
	 * @see #withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	String DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY = LoadBalancingStrategy.ROUND_ROBIN_REF;
	/**
	 * Default port is <code>{@value}</code>.
	 */
	int DEFAULT_PROXY_PORT = 1080;
	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy.
	 * A value of {@value} asks the operating system to allocate an available loopback port when the bridge starts.
	 */
	int DEFAULT_PROXY_BRIDGE_PORT = 0;
	/**
	 * Defaults to <code>{@value}</code>, sending mails rather than just only logging the mails.
	 */
	boolean DEFAULT_TRANSPORT_MODE_LOGGING_ONLY = false;
	/**
	 * Defaults to <code>{@value}</code>, sending mails rather than just only logging the mails.
	 */
	boolean DEFAULT_JAVAXMAIL_DEBUG = false;
	/** Defaults to {@value}: require advertised support for internationalized addresses and raw 8-bit content. */
	boolean DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT = false;
	/** Defaults to {@value}: mail-send telemetry is opt-in through Java configuration or properties. */
	boolean DEFAULT_MAIL_SEND_TELEMETRY_ENABLED = false;
	/** Defaults to {@value}: do not include SMTP host/port in mail-send telemetry unless permitted explicitly or by properties. */
	boolean DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES = false;
	/**
	 * Defaults to <code>{@value}</code>, validating emailaddresses (can be configured seperately, but this does override it) and CRLF injection detection (will arn instead).
	 */
	boolean DEFAULT_DISABLE_ALL_CLIENTVALIDATION = false;

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
	 * This flag is set on the Session instance through {@link Session#setDebug(boolean)} so that it generates debug information. To get more
	 * information out of the underlying JavaMail framework or out of Simple Java Mail, increase logging config of your chosen logging-framework.
	 *
	 * @param debugLogging Enables or disables debug logging with {@code true} or {@code false}.
	 */
	T withDebugLogging(@NotNull Boolean debugLogging);

	/**
	 * Sets a custom printer for Jakarta Mail debug output.
	 * <p>
	 * This is useful when {@link #withDebugLogging(Boolean)} is enabled and Jakarta Mail's default {@link System#out} output should be redirected,
	 * for example to an SLF4J-backed {@link PrintStream}.
	 * <p>
	 * Simple Java Mail does not close the supplied stream. Keep it open for as long as the Mailer may write debug output and close it after the Mailer
	 * is no longer using it.
	 * <p>
	 * For property files and CLI usage, use {@link #withDebugOutput(SessionDebugOutput)} or configure {@code simplejavamail.javaxmail.debug.out}.
	 *
	 * @param debugPrinter The printer that should receive Jakarta Mail debug output.
	 * @see Session#setDebugOut(PrintStream)
	 * @see #withDebugOutput(SessionDebugOutput)
	 */
	@Cli.ExcludeApi(reason = "PrintStream instances are Java-only; use withDebugOutput(SessionDebugOutput) for CLI-compatible built-in targets")
	T withDebugPrinter(@NotNull PrintStream debugPrinter);

	/**
	 * Sets one of the built-in targets for Jakarta Mail debug output.
	 * <p>
	 * This is useful when {@link #withDebugLogging(Boolean)} is enabled and Jakarta Mail's default {@link System#out} output should be redirected
	 * without providing a custom {@link PrintStream}.
	 *
	 * @param debugOutput The built-in output target to use for Jakarta Mail debug output.
	 *                    Supported values: STDOUT, STDERR, SLF4J.
	 * @see Session#setDebugOut(PrintStream)
	 * @see #withDebugPrinter(PrintStream)
	 */
	T withDebugOutput(@NotNull SessionDebugOutput debugOutput);

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
	 * Controls the timeout to use when sending emails (affects socket connect-, read- and write timeouts).
	 * <p>
	 * Will configure a set of properties on the Session instance with the given value, of which the names
	 * depend on the transport strategy:
	 * <ul>
	 *     <li>{@link TransportStrategy#propertyNameConnectionTimeout()}</li>
	 *     <li>{@link TransportStrategy#propertyNameTimeout()}</li>
	 *     <li>{@link TransportStrategy#propertyNameWriteTimeout()}</li>
	 * </ul>
	 * On owned managed Angus Sessions, enabled write timeouts share one lazy daemon scheduler per Session/protocol. Its worker stops after the
	 * last physical connection is disposed; returning a pooled lease or aborting a raw socket is not disposal. A supplied
	 * {@code mail.smtp.executor.writetimeout} (or {@code mail.smtps} equivalent) remains application-owned and is not shut down by the Mailer.
	 * Caller-owned Sessions and other providers keep their existing timeout-resource ownership. This is not a total-send deadline.
	 *
	 * @param sessionTimeout Socket timeout in milliseconds.
	 */
	T withSessionTimeout(@NotNull Integer sessionTimeout);

	/**
	 * Sets a positive total budget for each send, or for the whole operation when using a simple batch. Disabled by default.
	 * Includes library preparation, queue admission, pool acquisition, connection, TLS/authentication, SMTP and required cleanup.
	 * Observer callbacks/handoff are excluded. Open-connection establishment and each sender invocation receive separate budgets;
	 * arbitrary application work between those invocations is excluded.
	 * <p>
	 * The supported managed Angus transport aborts socket I/O. A configured timeout fails before connecting when the selected
	 * provider/custom socket configuration cannot support physical abort. Arbitrary user callbacks, DNS and custom data sources
	 * cannot be forcibly terminated; this is not an unconditional wall-clock limit on returning to the caller.
	 * Existing socket and acquisition timeouts remain applicable. A timeout does not prove SMTP non-acceptance: inspect any receipt.
	 *
	 * @param timeout Positive total send budget. Property and CLI values use ISO-8601 duration text, for example {@code PT30S}.
	 * @see #resetMailSendTimeout()
	 * @see MailSendTimeoutException
	 */
	T withMailSendTimeout(@NotNull Duration timeout);

	/** Restores the default of no total send deadline, including when a configuration property supplied one. */
	T resetMailSendTimeout();

	/**
	 * @return Configured total send budget, or {@code null} when disabled.
	 * @see #withMailSendTimeout(Duration)
	 */
	@Nullable Duration getMailSendTimeout();

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
	 * Sets a reference {@link Email} to be used for default values on all emails coming through this <code>Mailer</code> instance.
	 * <p>
	 * The template may be incomplete, for example containing only a signing configuration. Single values fill missing fields; collections such as
	 * recipients and attachments are added to the submitted email. The submitted email can suppress defaults through
	 * {@link EmailPopulatingBuilder#ignoringDefaults()} or {@link EmailPopulatingBuilder#dontApplyDefaultValueFor}.
	 * <p>
	 * This replaces the previous template, including the automatically derived property defaults; it does not merge templates. To retain configured
	 * property defaults, first materialize them with {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()}, then copy and edit
	 * that Email before supplying it here. Clearing this reference restores the Mailer's snapshot-derived defaults.
	 * Factory-scoped {@code simplejavamail.locked.} settings are separate restrictions: replacing or suppressing this template cannot remove them.
	 *
	 * @param emailDefaults The email to use as defaults.
	 * @see #clearEmailDefaults()
	 */
	T withEmailDefaults(@NotNull Email emailDefaults);

	/**
	 * Sets a reference {@link Email} whose values override Email-level configuration on messages sent through this Mailer.
	 * <p>
	 * Non-null single values replace the submitted values; collections are added rather than replaced. For a matching header key, the override's
	 * entire value collection wins. Explicit recipient fields, such as S/MIME certificates, still take precedence over the resolved Email fallback.
	 * The submitted email can suppress overrides through {@link EmailPopulatingBuilder#ignoringOverrides()} or
	 * {@link EmailPopulatingBuilder#dontApplyOverrideValueFor}. This replaces the previous override template.
	 * A template cannot displace factory-scoped {@code simplejavamail.locked.} settings; conflicting values fail during email preparation.
	 *
	 * @param emailOverrides The email to use as overrides.
	 * @see #clearEmailOverrides()
	 */
	T withEmailOverrides(@NotNull Email emailOverrides);

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
	 * Without a caller-provided executor, the built-in executor uses one worker when the
	 * {@value org.simplejavamail.internal.modules.BatchModule#NAME} is absent. With the batch module present, it uses:
	 * <ul>
	 *     <li>with max threads fixed to the given pool size (default is {@value #DEFAULT_POOL_SIZE})</li>
	 *     <li>with keepAliveTime as specified (if greater than zero, core threads will also time out and die off), default is {@value #DEFAULT_POOL_KEEP_ALIVE_TIME}</li>
	 *     <li>An unbounded queue by default, or the capacity and policy configured through {@link #withAsyncQueueCapacity(int)}</li>
	 *     <li>Named non-daemon worker threads</li>
	 * </ul>
	 * <p>
	 * <strong>Note:</strong> With the batch module present, the default keepAliveTime is set to the lowest non-zero value (so 1), so that
	 * any threads will die off as soon as possible, as not to block the JVM from shutting down.
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
	 * WAIT_FOR_CAPACITY can block the submitting thread for {@link #withAsyncQueueWaitTimeoutMillis(int)}. Avoid it on event-loop threads.
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
	 * @param waitTimeoutMillis Positive maximum admission wait in milliseconds; ignored by REJECT.
	 * @see #withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
	 */
	T withAsyncQueueWaitTimeoutMillis(int waitTimeoutMillis);

	/** Restores unbounded queuing, immediate rejection policy, and the default 1000 millisecond admission wait. */
	T resetAsyncQueue();

	/** @return The current immutable built-in queue configuration, including builder overrides. */
	@NotNull
	AsyncQueueConfig getAsyncQueueConfig();

	/**
	 * Sets max thread pool size to the given size (default is {@value #DEFAULT_POOL_SIZE}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param threadPoolSize See main description.
	 *
	 * @see #resetThreadPoolSize()
	 * @see #withThreadPoolSize(Integer)
	 */
	T withThreadPoolSize(@NotNull Integer threadPoolSize);

	/**
	 * When set to a non-zero value (milliseconds), this keepAlivetime is applied to <em>both</em> core and extra threads. This is so that
	 * these threads can never block the JVM from exiting once they finish their task. This is different from daemon threads,
	 * which are abandonded without waiting for them to finish the tasks.
	 * <p>
	 * When set to zero, this keepAliveTime is applied only to extra threads, not core threads. This is the classic executor
	 * behavior, but this blocks the JVM from exiting.
	 * <p>
	 * Defaults to {@value #DEFAULT_POOL_KEEP_ALIVE_TIME}ms.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param threadPoolKeepAliveTime Value in milliseconds. See main description for details.
	 *
	 * @see #resetThreadPoolKeepAliveTime()
	 */
	T withThreadPoolKeepAliveTime(@NotNull Integer threadPoolKeepAliveTime);

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
	 * Configures the connection pool's max size (default {@value DEFAULT_CONNECTIONPOOL_MAX_SIZE}) in case of high thread contention. Note that this
	 * determines how many connections can be open at
	 * any one time to a single server. Make sure your server can handle the load coming from all connections. There's no point having a hundred concurrent connections if it degrades your
	 * server's performance because of CPU throttling and network congestion.
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
	 * The default is {@value #DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS} milliseconds, approximately 24.9 days.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolClaimTimeoutMillis See main description.
	 */
	T withConnectionPoolClaimTimeoutMillis(@NotNull Integer connectionPoolClaimTimeoutMillis);

	/**
	 * If {@code >0}, configures the connection pool to automatically close connections after some milliseconds (default {@value DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS}) since last usage.
	 * <p>
	 * Note that if you combine this with {@link #withConnectionPoolCoreSize(Integer)} also {@code >0} (default is {@value DEFAULT_CONNECTIONPOOL_CORE_SIZE}), connections will keep
	 * closing and opening to keep core pool populated until shut down.
	 * <p>
	 * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @param connectionPoolExpireAfterMillis See main description.
	 */
	T withConnectionPoolExpireAfterMillis(@NotNull Integer connectionPoolExpireAfterMillis);

	/**
	 * Configures the age after which an available pooled connection becomes eligible for retirement, measured from the
	 * connection's creation time. Creation-age expiration is disabled when no value is configured.
	 * <p>
	 * This threshold is independent of {@link #withConnectionPoolExpireAfterMillis(Integer) idle expiration}. When both
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
	 * @param connectionPoolExpireAfterCreationMillis A positive duration in milliseconds.
	 * @see #clearConnectionPoolExpireAfterCreationMillis()
	 */
	T withConnectionPoolExpireAfterCreationMillis(@NotNull Integer connectionPoolExpireAfterCreationMillis);

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
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 * Configured SMTP settings are retained for explicit connection probes, which still connect even in logging-only mode.
	 * Logging-only sending does not require an SMTP host.
	 *
	 * @param transportModeLoggingOnly Flag {@code true} or {@code false} that enables or disables logging only mode when sending emails.
	 *
	 * @see #resetTransportModeLoggingOnly()
	 * @see Mailer.Sync#probeConnection()
	 */
	T withTransportModeLoggingOnly(@NotNull Boolean transportModeLoggingOnly);

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
	 * Adds the given properties to the total list applied to the {@link Session} when building a mailer.
	 *
	 * @see #withProperties(Map)
	 * @see #withProperty(String, Object)
	 * @see #clearProperties()
	 */
	T withProperties(@NotNull Properties properties);

	/**
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperties(@NotNull Map<String, String> properties);

	/**
	 * Sets property or removes it if the provided value is <code>null</code>. If provided, the value is always converted <code>toString()</code>.
	 *
	 * @param propertyName  The name of the property that wil be set on the internal Session object.
	 * @param propertyValue The text value of the property that wil be set on the internal Session object.
	 *
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperty(@NotNull String propertyName, @Nullable @Cli.Optional Object propertyValue);

	/**
	 * Configures a runtime provider for current OAuth2 access tokens. The provider is resolved whenever a physical SMTP connection
	 * is opened or reconnected and must be used with {@link TransportStrategy#SMTP_OAUTH2}.
	 * <p>
	 * The provider can be called concurrently and should normally cache a valid token, refreshing it only when necessary. For a
	 * short-lived fixed token, the existing SMTP password configuration remains available.
	 * A caller-supplied {@link jakarta.mail.Session} must declare {@code mail.smtp.auth.mechanisms=XOAUTH2}.
	 * <p>
	 * Calling this method again replaces the provider. {@link #clearOAuth2AccessTokenProvider()} removes it without closing an application object,
	 * changing the transport strategy, or removing a separately configured SMTP password or token. Previously built Mailers remain unchanged.
	 *
	 * @param accessTokenProvider Thread-safe provider of current, nonblank OAuth2 access tokens.
	 */
	@Cli.ExcludeApi(reason = "OAuth2 access-token providers are runtime Java integrations and cannot be represented as CLI values")
	T withOAuth2AccessTokenProvider(@NotNull OAuth2AccessTokenProvider accessTokenProvider);

	/** @see #withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider) */
	@Cli.ExcludeApi(reason = "Runtime token providers are Java-only registrations; fresh CLI builders have none to clear")
	T clearOAuth2AccessTokenProvider();

	/**
	 * Lets you trace email sends and collect sending metrics with the OpenTelemetry instance your application already uses.
	 * Supply the handle returned by {@code OtelAdapter.wrap(openTelemetry)} from the optional telemetry module. This also enables telemetry;
	 * calling the method again replaces the previous handle without changing the supplied instance.
	 * <p>
	 * The integration follows each email from admission through retries, failover and ordinary cleanup, with separate observations of
	 * each provider invocation. The send span includes the same phase timings as {@link MailSendDiagnostics} and identifies the primary
	 * failure's phase when known. It records completion independently of result and progress callbacks, so delayed or coalesced callbacks
	 * do not affect span completion or send counts. As with submission receipts, SMTP acceptance does not establish final delivery.
	 * <p>
	 * To keep infrastructure names private, SMTP host and port attributes default to {@value #DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES}.
	 * Use {@link #withMailSendTelemetrySmtpEndpointAttributes(boolean)} or {@code simplejavamail.telemetry.smtp.endpointattributes} to allow them.
	 * Each Mailer resolves its own permission without modifying the handle you supplied.
	 * <p>
	 * Your application remains responsible for SDK processing, sampling, buffering and shutdown; closing the Mailer never closes the SDK
	 * or exporters. Prefer batched export for normal operation, because synchronous SDK processors can delay the sending thread.
	 * If the integration throws a {@link RuntimeException}, Simple Java Mail logs a warning and preserves the send result.
	 *
	 * @param telemetry A handle returned by the optional telemetry module's typed adapter.
	 * @see #getMailSendTelemetry()
	 * @see #clearMailSendTelemetry()
	 */
	@Cli.ExcludeApi(reason = "Telemetry integrations contain application-owned runtime SDK instances, not CLI values")
	T withMailSendTelemetry(@NotNull MailSendTelemetry telemetry);

	/**
	 * Lets you turn mail-send tracing and metrics on or off without configuring each Mailer separately.
	 * Set {@code simplejavamail.telemetry.enabled=true} in a factory's captured configuration to enable it for Mailers created by that factory,
	 * or use this method to replace the captured choice. The built-in default is {@value #DEFAULT_MAIL_SEND_TELEMETRY_ENABLED}.
	 * <p>
	 * Enabling requires {@code open-telemetry-module}. Unless {@link #withMailSendTelemetry(MailSendTelemetry)} supplies a specific instance,
	 * the module uses the application's global OpenTelemetry instance. Set that instance up before building the Mailer, including its SDK,
	 * sampling and exporters; Simple Java Mail neither creates nor closes them. An unconfigured global instance provides no-op reporting.
	 * <p>
	 * Disabling keeps any supplied configuration for later reuse, but creates no telemetry runtime or per-send telemetry work.
	 * A conflicting {@code simplejavamail.locked.telemetry.enabled} value rejects construction rather than silently changing the choice.
	 *
	 * @param enabled Whether mail sends should produce telemetry through the optional module.
	 * @see #withMailSendTelemetry(MailSendTelemetry)
	 */
	T withMailSendTelemetryEnabled(boolean enabled);

	/** @see #withMailSendTelemetryEnabled(boolean) */
	boolean isMailSendTelemetryEnabled();

	/** @see #withMailSendTelemetryEnabled(boolean) */
	T resetMailSendTelemetryEnabled();

	/**
	 * Lets traces identify which SMTP relay handled a submission by including its logical host and port.
	 * Leave this disabled if infrastructure names should stay private. Even when enabled, those details never become metric labels,
	 * and email addresses, content, credentials, message identifiers and raw exception text remain excluded.
	 * <p>
	 * This choice replaces {@code simplejavamail.telemetry.smtp.endpointattributes} from the factory's captured configuration.
	 * Without either choice, the default is {@value #DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES}. If the configuration owner fixes
	 * a different value through {@code simplejavamail.locked.telemetry.smtp.endpointattributes}, building the Mailer fails with that conflict.
	 * <p>
	 * Clear the choice to restore the captured property, or reset it to explicitly select the built-in default. Replacing or clearing a telemetry
	 * handle does not change this permission. The setting does not install an SDK or enable telemetry by itself; use
	 * {@link #withMailSendTelemetryEnabled(boolean)} or {@link #withMailSendTelemetry(MailSendTelemetry)} to enable reporting.
	 *
	 * @param enabled Whether the selected logical SMTP host and port may appear in trace attributes.
	 */
	T withMailSendTelemetrySmtpEndpointAttributes(boolean enabled);

	/** @see #withMailSendTelemetrySmtpEndpointAttributes(boolean) */
	boolean isMailSendTelemetrySmtpEndpointAttributesEnabled();

	/** @see #withMailSendTelemetrySmtpEndpointAttributes(boolean) */
	T resetMailSendTelemetrySmtpEndpointAttributes();

	/**
	 * Restores the endpoint permission from this factory's captured configuration.
	 *
	 * @see #withMailSendTelemetrySmtpEndpointAttributes(boolean)
	 */
	@Cli.ExcludeApi(reason = "Fresh CLI builders already inherit the endpoint permission; use withMailSendTelemetrySmtpEndpointAttributes")
	T clearMailSendTelemetrySmtpEndpointAttributes();

	/**
	 * Returns the integration you supplied, or {@code null} when none is registered. Mailer-specific property resolution does not modify it.
	 *
	 * @see #withMailSendTelemetry(MailSendTelemetry)
	 */
	@Nullable
	MailSendTelemetry getMailSendTelemetry();

	/**
	 * Removes the supplied handle and restores activation from the captured properties, without closing its SDK or changing endpoint permission.
	 * If those properties enable telemetry, the module uses the application's global instance instead.
	 * Use {@link #withMailSendTelemetryEnabled(boolean)} with {@code false} to disable it explicitly.
	 *
	 * @see #withMailSendTelemetry(MailSendTelemetry)
	 */
	@Cli.ExcludeApi(reason = "Fresh CLI builders have no supplied runtime integration to clear; use withMailSendTelemetryEnabled")
	T clearMailSendTelemetry();

	/**
	 * Calls your observer once each individual email send has finished, so you can record its result and inspect any SMTP acceptance facts.
	 * <p>
	 * The callback runs on the thread completing the send, before completion is reported to the caller. Keep it quick and thread-safe: slow callbacks
	 * delay completion, and different sends may call the observer concurrently. If it throws a {@link RuntimeException}, the exception is logged
	 * without changing the send's result.
	 * <p>
	 * Calling this method again replaces the result observer and restores inline execution, even if you previously supplied an observer executor.
	 * Use {@link #clearMailSendResultObserver()} to remove the result observer and its executor from this builder. Neither operation changes your
	 * progress observer or any Mailer already built from this builder.
	 *
	 * @param mailSendResultObserver Thread-safe, quick and non-blocking terminal send observer.
	 * @see MailSendResultObserver
	 */
	@Cli.ExcludeApi(reason = "Mail send observers are runtime Java callbacks and cannot be represented as CLI values")
	T withMailSendResultObserver(@NotNull MailSendResultObserver mailSendResultObserver);

	/**
	 * Sends final outcomes to your observer through the supplied executor, allowing you to process results separately from sending emails.
	 * The Mailer submits each notification before completing the send, but does not wait for an asynchronously executed callback to finish.
	 * Your observer can therefore run before or after the caller sees completion.
	 * <p>
	 * Choose an executor that actually moves work off the sending thread and explicitly rejects tasks it cannot accept. A direct or caller-runs executor
	 * can run the callback on the sending thread, and an executor that blocks when full can delay sending too. Silent-discard policies can lose
	 * notifications without reporting rejection. Callback {@link RuntimeException}s and executor rejections are logged without changing the send result;
	 * rejected notifications are neither retried nor run inline as a fallback.
	 * <p>
	 * You manage this executor's capacity and shutdown; closing the Mailer neither drains nor shuts it down. A completed callback only tells you that
	 * your callback finished, not that any broker or downstream system processed the event.
	 * <p>
	 * Calling this method again replaces the result observer and its executor without changing your progress observer.
	 *
	 * @param mailSendResultObserver Thread-safe terminal send observer; callbacks for different sends may run concurrently or out of order.
	 * @param observerExecutor Application-owned executor; the application owns capacity, draining and shutdown.
	 * @see #withMailSendResultObserver(MailSendResultObserver)
	 */
	@Cli.ExcludeApi(reason = "Observers and executors are runtime Java integrations and cannot be represented as CLI values")
	T withMailSendResultObserver(@NotNull MailSendResultObserver mailSendResultObserver, @NotNull Executor observerExecutor);

	/**
	 * @see #withMailSendResultObserver(MailSendResultObserver)
	 */
	@Cli.ExcludeApi(reason = "Result observers are Java-only registrations; a fresh CLI builder has none to clear")
	T clearMailSendResultObserver();

	/**
	 * Keeps you informed while an email is being sent, for example when it is waiting for a connection or uploading an attachment.
	 * To learn how the send finished, also register a {@link #withMailSendResultObserver(MailSendResultObserver) result observer}:
	 * even {@link MailSendProgressStage#FINISHED FINISHED} means only that sending has ended, not that the server accepted or delivered the email.
	 * <p>
	 * Think of progress as the latest available view, rather than a log of every step. Updates can be combined or dropped, so you may not see every stage.
	 * The detail available also depends on the provider; missing upload counts remain empty and do not prevent sending.
	 * <p>
	 * The Mailer starts its own daemon dispatcher when progress is first needed and calls your observer one update at a time, never on a sending thread.
	 * This lets slow callbacks delay reporting without holding up sending. A callback {@link RuntimeException} is logged and ignored.
	 * To keep pending reporting bounded, the dispatcher retains only the newest snapshot for each observation ID, up to 1,024 pending IDs plus one callback
	 * in flight. If another ID would exceed that limit, the oldest pending snapshot is discarded. Updates that change only the upload count are published
	 * at most once every 250 milliseconds per email; stage changes and final counts can be published sooner, but can still be combined before delivery.
	 * <p>
	 * Progress and final results are delivered independently. Once your result observer receives the final outcome for an observation ID, ignore any later
	 * progress for that ID so an older snapshot cannot make a completed send appear active again.
	 * <p>
	 * Closing the Mailer waits for sends, not progress callbacks: pending updates may be discarded, and a running callback is neither awaited nor interrupted.
	 * Calling this method again replaces the progress observer and restores execution on the Mailer's dispatcher, without changing your result observer.
	 * Use {@link #clearMailSendProgressObserver()} to remove the progress observer and its executor from this builder. Clearing either registration
	 * leaves the other observer and any Mailer already built from this builder unchanged.
	 *
	 * @param progressObserver Non-null progress callback; it must tolerate snapshots already superseded by a final result.
	 * @return This builder.
	 * @see MailSendProgress
	 */
	@Cli.ExcludeApi(reason = "A Java progress callback cannot be represented as a CLI value")
	T withMailSendProgressObserver(@NotNull MailSendProgressObserver progressObserver);

	/**
	 * Delivers progress through your own executor, with the same latest-state behavior and reporting limits as
	 * {@link #withMailSendProgressObserver(MailSendProgressObserver) the default registration}.
	 * <p>
	 * The Mailer's dispatcher submits the callbacks, rather than a sending thread, and waits for each callback before handing off another.
	 * Even a direct or caller-runs executor therefore cannot run a callback inline in the sending call. Keep this executor separate from your sending workers,
	 * though: sharing their executor lets slow progress callbacks occupy threads that could otherwise send mail.
	 * <p>
	 * Choose an executor that explicitly rejects work it cannot accept. Rejections are logged and the update is discarded, without retrying or calling
	 * the observer inline as a fallback. An executor that silently discards a task can leave the dispatcher waiting and stall further progress reporting.
	 * <p>
	 * You own this executor; the Mailer never shuts it down or waits for progress callbacks during close. Calling this method again replaces only the
	 * progress observer and its executor, leaving your result observer unchanged.
	 *
	 * @param progressObserver Non-null progress callback.
	 * @param progressExecutor Non-null application-owned callback executor.
	 * @return This builder.
	 * @see #withMailSendProgressObserver(MailSendProgressObserver)
	 */
	@Cli.ExcludeApi(reason = "Java callbacks and executors cannot be represented as CLI values")
	T withMailSendProgressObserver(@NotNull MailSendProgressObserver progressObserver, @NotNull Executor progressExecutor);

	/**
	 * @see #withMailSendProgressObserver(MailSendProgressObserver)
	 */
	@Cli.ExcludeApi(reason = "Progress observers are Java-only registrations; a fresh CLI builder has none to clear")
	T clearMailSendProgressObserver();

	/**
	 * Lets your callback deliver the prepared email instead of using Simple Java Mail's SMTP transport.
	 * The callback receives the governed Email and prepared MIME message; see {@link CustomMailer} for its transport responsibilities.
	 * <p>
	 * Calling this method again replaces the callback. {@link #clearCustomMailer()} removes it without closing an application object or changing
	 * SMTP configuration. The next Mailer built from this builder uses its remaining SMTP settings, so it needs a host unless logging-only mode applies.
	 * Previously built Mailers retain their own callback. Factory configuration locks are still checked when building the next Mailer.
	 *
	 * @param customMailer Application-owned delivery callback.
	 * @see CustomMailer
	 */
	T withCustomMailer(@NotNull CustomMailer customMailer);

	/** @see #withCustomMailer(CustomMailer) */
	@Cli.ExcludeApi(reason = "Custom mailers are Java-only callbacks; fresh CLI builders have none to clear")
	T clearCustomMailer();

	/**
	 * Uses another configured Mailer when this destination cannot establish a usable SMTP connection and the policy permits failover.
	 * For example, a connection refusal can immediately move the send to a second relay while the primary is reconsidered later.
	 * A busy connection pool or sending-limit wait keeps its normal waiting behavior. Failover never replays an email once provider submission begins.
	 * A separately configured {@link #withMailSendRetryPolicy(MailSendRetryPolicy) retry policy} may repeat a safely refused submission on the selected
	 * destination; that retry does not switch to a failover destination.
	 * <p>
	 * The selected Mailer supplies its own SMTP settings, email defaults/overrides, validation and message protection. This Mailer still
	 * owns the executor, observer, completion and overall deadline. Mandatory message settings configured through
	 * {@code simplejavamail.locked.*} remain binding across the route. The supplied Mailer is borrowed, not closed with this Mailer.
	 * Its own configured failover relationship is honored, without starting another public send or using its executor.
	 * <p>
	 * Repeated calls replace both the destination and its policy. Authentication and certificate-verification failures stop by default;
	 * their independent policy opt-ins permit switching destinations without weakening either destination's security checks.
	 * Both arguments must be non-null. Composition requires Mailers built by Simple Java Mail, including those using third-party providers.
	 *
	 * @see #clearFailoverMailer()
	 */
	@Cli.ExcludeApi(reason = "Live Mailers and paired policies use the internal named-configuration CLI facade")
	T withFailoverMailer(@NotNull Mailer failoverMailer, @NotNull FailoverPolicy policy);

	/** Removes the failover relationship and its policy, unless that relationship is fixed by a {@code simplejavamail.locked.*} property. */
	@Cli.ExcludeApi(reason = "Fresh CLI builders select named configuration rather than clearing live Mailer references")
	T clearFailoverMailer();

	/**
	 * Restores blocking client-side validation by resetting this option to its default ({@value #DEFAULT_DISABLE_ALL_CLIENTVALIDATION}).
	 *
	 * @see #disablingAllClientValidation(Boolean)
	 */
	T resetDisableAllClientValidations();

	/**
	 * Resets session time to its default ({@value DEFAULT_SESSION_TIMEOUT_MILLIS}).
	 *
	 * @see #withSessionTimeout(Integer)
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
	 * Restores a Mailer-owned executor using the current built-in queue settings. With the Batch module present it uses the configured
	 * worker count and keep-alive time; without it, it uses one worker. This does not reset queue settings.
	 *
	 * @see #withExecutorService(ExecutorService)
	 * @see #resetAsyncQueue()
	 */
	T resetExecutorService();

	/**
	 * Resets max thread pool size to its default of {@value #DEFAULT_POOL_SIZE}.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withThreadPoolSize(Integer)
	 */
	T resetThreadPoolSize();

	/**
	 * Resets thread pool keepAliveTime to its default ({@value #DEFAULT_POOL_KEEP_ALIVE_TIME}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withThreadPoolKeepAliveTime(Integer)
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
	 * Resets the connection pool claim timeout to its default ({@value #DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS} milliseconds, approximately 24.9 days).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	T resetConnectionPoolClaimTimeoutMillis();

	/**
	 * Resets connection pool expire-after-milliseconds property to its default ({@value #DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolExpireAfterMillis(Integer)
	 */
	T resetConnectionPoolExpireAfterMillis();

	/**
	 * Disables connection pool creation-age expiration by removing the configured duration.
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the
	 * {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolExpireAfterCreationMillis(Integer)
	 */
	T clearConnectionPoolExpireAfterCreationMillis();

	/**
	 * Resets connection pool load balancing strategy to its default ({@value #DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY}).
	 * <p>
	 * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
	 *
	 * @see #withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	T resetConnectionPoolLoadBalancingStrategy();

	/**
	 * Resets transportModeLoggingOnly to {@value #DEFAULT_TRANSPORT_MODE_LOGGING_ONLY}.
	 *
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	T resetTransportModeLoggingOnly();

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
	 * Clears the explicit defaults template, restoring defaults derived from the Mailer's immutable configuration snapshot.
	 * This does not disable property-backed defaults. Supply an empty defaults Email to replace those too, or use
	 * {@link EmailPopulatingBuilder#ignoringDefaults()} to suppress defaults for a particular submitted email.
	 *
	 * @see #withEmailDefaults(Email)
	 */
	T clearEmailDefaults();

	/**
	 * Makes the reference {@code Email} instance <code>null</code>, meaning no overrides will be applied.
	 *
	 * @see #withEmailOverrides(Email)
	 */
	T clearEmailOverrides();

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
	 * Removes all properties.
	 *
	 * @see #withProperties(Properties)
	 */
	T clearProperties();

	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();

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
	 * @see #withDebugLogging(Boolean)
	 */
	boolean isDebugLogging();

	/**
	 * @see #withDebugPrinter(PrintStream)
	 * @see #withDebugOutput(SessionDebugOutput)
	 */
	@Nullable
	PrintStream getDebugPrinter();

	/**
	 * @see #disablingAllClientValidation(Boolean)
	 */
	boolean isDisableAllClientValidation();

	/**
	 * @see #withSessionTimeout(Integer)
	 */
	@Nullable
	Integer getSessionTimeout();

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
	 * @see #withEmailDefaults(Email)
	 */
	@Nullable
	Email getEmailDefaults();

	/**
	 * @see #withEmailOverrides(Email)
	 */
	@Nullable
	Email getEmailOverrides();

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
	 * @see #withThreadPoolKeepAliveTime(Integer)
	 */
	@NotNull
	Integer getThreadPoolKeepAliveTime();

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
	 * @see #withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	@NotNull
	Integer getConnectionPoolClaimTimeoutMillis();

	/**
	 * @see #withConnectionPoolExpireAfterMillis(Integer)
	 */
	@NotNull
	Integer getConnectionPoolExpireAfterMillis();

	/**
	 * @see #withConnectionPoolExpireAfterCreationMillis(Integer)
	 */
	@Nullable
	Integer getConnectionPoolExpireAfterCreationMillis();

	/**
	 * @see #withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy();

	/**
	 * @see #trustingSSLHosts(String...)
	 */
	@Nullable
	List<String> getSslHostsToTrust();

	/**
	 * @see #trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();

	/**
	 * @see #verifyingServerIdentity(boolean)
	 */
	boolean isVerifyingServerIdentity();

	/**
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();

	/**
	 * @see #withProperties(Properties)
	 */
	@Nullable
	Properties getProperties();

	/**
	 * @see #withCustomMailer(CustomMailer)
	 */
	@Nullable
	CustomMailer getCustomMailer();

	/**
	 * @see #withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider)
	 */
	@Nullable
	OAuth2AccessTokenProvider getOAuth2AccessTokenProvider();
}
