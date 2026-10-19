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
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
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
	 * The default transport socket timeout is sixty seconds (affects socket connect-, read- and write timeouts). Can be overridden from a config file or through System variable.
	 */
	Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMillis(60_000);
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolSize(Integer)
	 */
	int DEFAULT_POOL_SIZE = 10;
	/**
	 * One millisecond for explicitly reset worker keep-alive and for implicit defaults with the batch module.
	 *
	 * @see #withThreadPoolKeepAliveTime(Duration)
	 */
	Duration DEFAULT_POOL_KEEP_ALIVE_TIME = Duration.ofMillis(1);
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
	 * One second.
	 *
	 * @see #withAsyncQueueWaitTimeout(Duration)
	 */
	Duration DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT = Duration.ofMillis(1000);
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
	 * The duration of {@code Integer.MAX_VALUE} milliseconds, approximately 24.9 days.
	 *
	 * @see #withConnectionPoolClaimTimeout(Duration)
	 */
	Duration DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);
	/**
	 * Five seconds.
	 *
	 * @see #withConnectionPoolExpireAfter(Duration)
	 */
	Duration DEFAULT_CONNECTIONPOOL_EXPIREAFTER = Duration.ofMillis(5000);
	/** No creation-age expiration unless configured. */
	@Nullable Duration DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION = null;
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
	/** Explicit reset target for Jakarta Mail debug output. Unset output preserves captured configuration or the Session baseline. */
	SessionDebugOutput DEFAULT_JAVAXMAIL_DEBUG_OUTPUT = SessionDebugOutput.STDOUT;
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
	 * Replaces this builder's supplied debug choices. Unset fields inherit the factory snapshot, never the previous group.
	 * The supplied value and its getters retain absence. A built Mailer's operational configuration contains resolved choices.
	 * For a caller-owned Session, absent factory and group choices preserve its debug flag and output. Streams remain borrowed.
	 * @param debugConfig Immutable supplied choices.
	 * @see #getDebugConfig()
	 * @see #useConfiguredDebugConfig()
	 */
	@Cli.ExcludeApi(reason = "The CLI exposes scalar debug options through its internal configuration adapter")
	T withDebugConfig(@NotNull SessionDebugConfig debugConfig);

	/** @see #withDebugConfig(SessionDebugConfig) */
	@NotNull SessionDebugConfig getDebugConfig();

	/**
	 * Restores captured debug configuration by removing all supplied group choices.
	 * @see #withDebugConfig(SessionDebugConfig)
	 */
	@Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured debug configuration")
	T useConfiguredDebugConfig();

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
	 * Sets a reference {@link Email} to be used for default values on all emails coming through this <code>Mailer</code> instance.
	 * <p>
	 * The template may be incomplete, for example containing only a signing configuration. Single values fill missing fields; collections such as
	 * recipients and attachments are added to the submitted email. The submitted email can suppress defaults through
	 * {@link EmailPopulatingBuilder#ignoringDefaults()} or {@link EmailPopulatingBuilder#dontApplyDefaultValueFor}.
	 * <p>
	 * This replaces the previous template, including the automatically derived property defaults; it does not merge templates. To retain configured
	 * property defaults, first materialize them with {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()}, then copy and edit
	 * that Email before supplying it here. Use {@link #useConfiguredEmailDefaults()} to restore the Mailer's snapshot-derived defaults,
	 * or {@link #withoutEmailDefaults()} to suppress ordinary defaults.
	 * Factory-scoped {@code simplejavamail.locked.} settings are separate restrictions: replacing or suppressing this template cannot remove them.
	 *
	 * @param emailDefaults The email to use as defaults.
	 * @see #useConfiguredEmailDefaults()
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
	 * Adds extra Jakarta Mail settings to the {@link Session} built for this Mailer. Only entries stored directly in this
	 * {@link Properties} object are copied; values inherited from its defaults are not. To include an inherited value,
	 * add it explicitly with {@link #withProperty(String, Object)}.
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
	 * Replaces this builder's supplied telemetry choices. Unset flags inherit the captured snapshot, never the previous group.
	 * Activation is independent from supplying or clearing an integration. Enabled telemetry requires the optional module and,
	 * without a supplied integration, uses the application's global OpenTelemetry instance. The application owns SDK shutdown.
	 * SMTP endpoint attributes are opt-in; email content, addresses and credentials remain excluded. Configuration locks apply.
	 * @param telemetryConfig Immutable supplied choices; its getters retain unset fields.
	 * @see #getTelemetryConfig()
	 * @see #useConfiguredTelemetryConfig()
	 */
	@Cli.ExcludeApi(reason = "The CLI exposes scalar telemetry options through its internal configuration adapter")
	T withTelemetryConfig(@NotNull MailSendTelemetryConfig telemetryConfig);

	/** @see #withTelemetryConfig(MailSendTelemetryConfig) */
	@NotNull MailSendTelemetryConfig getTelemetryConfig();

	/**
	 * Restores captured flags and removes a supplied integration without closing it.
	 * @see #withTelemetryConfig(MailSendTelemetryConfig)
	 */
	@Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured telemetry configuration")
	T useConfiguredTelemetryConfig();

	/**
	 * Registers shared callbacks for every logical email send. Replaces only this group, leaving the per-send factory unchanged.
	 * Shared callbacks are handed off before factory-created callbacks; each callback failure is isolated independently.
	 * Executors and callbacks remain caller-owned. Clearing removes this group without closing anything.
	 * @param handlers Immutable shared callback choices.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "Mail send handlers are runtime Java callbacks and cannot be represented as CLI values")
	T withSendHandlers(@NotNull MailSendHandlers handlers);
	/**
	 * Returns supplied shared handlers, or null when absent.
	 * @see #withSendHandlers(MailSendHandlers)
	 */
	@Nullable MailSendHandlers getSendHandlers();
	/** @see #withSendHandlers(MailSendHandlers) */
	@Cli.ExcludeApi(reason = "Runtime Java callback registration")
	T clearSendHandlers();

	/**
	 * Creates callbacks once per logical email using its generated UUID, before preparation or individual-send admission.
	 * Singles initialize on the caller thread; lazy batches initialize each reached email on their execution thread.
	 * Retries and failover reuse the initial callbacks and ID. The initiating Mailer owns reporting; connection checks and rehearsals do not call this factory.
	 * A null result or runtime exception prevents that email's submission: sync throws and async returns a failed completion.
	 * Original runtime failures are retained without automatic retries. A batch stops at that email; earlier submissions remain completed.
	 * A factory failure is reported through shared handlers and telemetry under the generated ID. Fatal Errors propagate normally.
	 * Generated and shared handlers both receive notifications, shared first. Callbacks/executors remain borrowed.
	 * @param factory Thread-safe factory returning non-null handler choices, including an empty group when no callbacks are needed.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "A runtime Java callback factory cannot be represented as a CLI value")
	T withSendHandlersFactory(@NotNull java.util.function.Function<UUID, MailSendHandlers> factory);
	/**
	 * Returns the supplied factory, or null when absent.
	 * @see #withSendHandlersFactory(java.util.function.Function)
	 */
	@Nullable java.util.function.Function<UUID, MailSendHandlers> getSendHandlersFactory();
	/** @see #withSendHandlersFactory(java.util.function.Function) */
	@Cli.ExcludeApi(reason = "Runtime Java callback registration")
	T clearSendHandlersFactory();

	/**
	 * Chooses one observation UUID per logical email send, before factory initialization and preparation/admission.
	 * The default is {@code UUID::randomUUID}. A generator must be thread-safe and return unique, non-null IDs.
	 * IDs stay local and never alter Email or MIME content. A failure or null ID prevents submission and has no observation outcome;
	 * sync throws, async returns a failed completion. Batch initialization remains lazy; retries and failover retain the same ID.
	 * A batch stops at the failing email without undoing earlier submissions. Connection checks and rehearsals do not call this supplier.
	 * Original runtime failures are retained without retries; fatal Errors propagate normally.
	 * @param generator Thread-safe observation-ID supplier.
	 * @return This builder.
	 */
	@Cli.ExcludeApi(reason = "A runtime Java ID supplier cannot be represented as a CLI value")
	T withSendIdGenerator(@NotNull java.util.function.Supplier<UUID> generator);
	/**
	 * Returns the current supplier, including the default.
	 * @see #withSendIdGenerator(java.util.function.Supplier)
	 */
	@NotNull java.util.function.Supplier<UUID> getSendIdGenerator();
	/**
	 * Restores {@code UUID::randomUUID}.
	 * @see #withSendIdGenerator(java.util.function.Supplier)
	 */
	@Cli.ExcludeApi(reason = "Runtime Java ID supplier")
	T resetSendIdGenerator();

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

	/**
	 * Returns the Mailer supplied directly to the setter, or {@code null} when none was supplied. A property-configured named
	 * destination is created only when building the Mailer; inspecting this builder does not create it.
	 *
	 * @see #withFailoverMailer(Mailer, FailoverPolicy)
	 */
	@Nullable
	Mailer getFailoverMailer();

	/**
	 * Returns the policy held by this builder, or {@code null} when no failover relationship is configured.
	 *
	 * @see #withFailoverMailer(Mailer, FailoverPolicy)
	 */
	@Nullable
	FailoverPolicy getFailoverPolicy();

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
	@Cli.ExcludeApi(reason = "The CLI retains clearEmailDefaults through its internal adapter")
	T useConfiguredEmailDefaults();

	/**
	 * Disables ordinary email defaults by supplying an empty template. Mandatory configuration locks still apply.
	 * @see #withEmailDefaults(Email)
	 * @see #useConfiguredEmailDefaults()
	 */
	T withoutEmailDefaults();

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

	/**
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();

	/**
	 * Returns a detached property container. Changing it does not change this builder; use the property setters to change the stored choices.
	 * Objects supplied as property values retain their identity.
	 * @see #withProperties(Properties)
	 */
	@NotNull
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
