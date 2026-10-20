package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

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
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#trustingAllHosts(boolean)
	 */
	boolean DEFAULT_TRUST_ALL_HOSTS = false;
	/**
	 * {@value}
	 *
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#verifyingServerIdentity(boolean)
	 */
	boolean DEFAULT_VERIFY_SERVER_IDENTITY = true;
	/**
	 * Plain SMTP attempts STARTTLS when the server offers it unless explicitly disabled.
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#withOpportunisticTLS(boolean)
	 */
	boolean DEFAULT_OPPORTUNISTIC_TLS = true;
	/**
	 * The default transport socket timeout is sixty seconds (affects socket connect-, read- and write timeouts). Can be overridden from a config file or through System variable.
	 */
	Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMillis(60_000);
	/**
	 * {@value}
	 *
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withThreadPoolSize(Integer)
	 */
	int DEFAULT_POOL_SIZE = 10;
	/**
	 * One millisecond for explicitly reset worker keep-alive and for implicit defaults with the batch module.
	 *
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withKeepAliveTime(Duration)
	 */
	Duration DEFAULT_POOL_KEEP_ALIVE_TIME = Duration.ofMillis(1);
	/**
	 * {@value} (unbounded).
	 *
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withQueueCapacity(int)
	 */
	int DEFAULT_ASYNC_QUEUE_CAPACITY = -1;
	/**
	 * {@link AsyncQueueOverflowPolicy#REJECT}.
	 *
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#rejectWhenFull()
	 */
	AsyncQueueOverflowPolicy DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY = AsyncQueueOverflowPolicy.REJECT;
	/**
	 * One second.
	 *
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#waitForCapacity(java.time.Duration)
	 */
	Duration DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT = Duration.ofMillis(1000);
	/**
	 * {@value}
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withCoreSize(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_CORE_SIZE = 0;
	/**
	 * {@value}
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withMaxSize(Integer)
	 */
	int DEFAULT_CONNECTIONPOOL_MAX_SIZE = 4;
	/**
	 * The duration of {@code Integer.MAX_VALUE} milliseconds, approximately 24.9 days.
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withClaimTimeout(Duration)
	 */
	Duration DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);
	/**
	 * Five seconds.
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withExpireAfter(Duration)
	 */
	Duration DEFAULT_CONNECTIONPOOL_EXPIREAFTER = Duration.ofMillis(5000);
	/** No creation-age expiration unless configured. */
	@Nullable Duration DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION = null;
	/**
	 * {@value}
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withLoadBalancingStrategy(LoadBalancingStrategy)
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
	 * Replaces this builder's supplied debug choices. Unset fields inherit the factory snapshot, never the previous group.
	 * The supplied value and its getters retain absence. A built Mailer's operational configuration contains resolved choices.
	 * For a caller-owned Session, absent factory and group choices preserve its debug flag and output. Streams remain borrowed.
	 * @param debugConfig Immutable supplied choices.
	 * @see #getDebugConfig()
	 * @see #useConfiguredDebugConfig()
	 */
	@Cli.ExcludeApi(reason = "The CLI exposes scalar debug options through its internal configuration adapter")
	T withDebugConfig(@NotNull SessionDebugConfig debugConfig);

	/** @see org.simplejavamail.api.mailer.MailerGenericBuilder#withDebugConfig(SessionDebugConfig) */
	@NotNull SessionDebugConfig getDebugConfig();

	/**
	 * Restores captured debug configuration by removing all supplied group choices.
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withDebugConfig(SessionDebugConfig)
	 */
	@Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured debug configuration")
	T useConfiguredDebugConfig();

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
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Nullable Duration getMailSendTimeout();

	/**
	 * Sets a reference {@link Email} to be used for default values on all emails coming through this <code>Mailer</code> instance.
	 * <p>
	 * The template may be incomplete, for example containing only a signing configuration. Single values fill missing fields; collections such as
	 * recipients and attachments are added to the submitted email. The submitted email can suppress defaults through
	 * {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringDefaults()} or {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyDefaultValueFor}.
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
	 * The submitted email can suppress overrides through {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringOverrides()} or
	 * {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#dontApplyOverrideValueFor}. This replaces the previous override template.
	 * A template cannot displace factory-scoped {@code simplejavamail.locked.} settings; conflicting values fail during email preparation.
	 *
	 * @param emailOverrides The email to use as overrides.
	 * @see #clearEmailOverrides()
	 */
	T withEmailOverrides(@NotNull Email emailOverrides);

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
	 * Adds extra Jakarta Mail settings to the {@link Session} built for this Mailer. Only entries stored directly in this
	 * {@link Properties} object are copied; values inherited from its defaults are not. To include an inherited value,
	 * add it explicitly with {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withProperty(String, Object)}.
	 *
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Map)
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperty(String, Object)
	 * @see #clearProperties()
	 */
	T withProperties(@NotNull Properties properties);

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperties(@NotNull Map<String, String> properties);

	/**
	 * Sets property or removes it if the provided value is <code>null</code>. If provided, the value is always converted <code>toString()</code>.
	 *
	 * @param propertyName  The name of the property that wil be set on the internal Session object.
	 * @param propertyValue The text value of the property that wil be set on the internal Session object.
	 *
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperty(@NotNull String propertyName, @Nullable @Cli.Optional Object propertyValue);

	/**
	 * Configures a runtime provider for current OAuth2 access tokens. The provider is resolved whenever a physical SMTP connection
	 * is opened or reconnected and must be used with {@link TransportStrategy#SMTP_OAUTH2} (mandatory STARTTLS) or
	 * {@link TransportStrategy#SMTPS_OAUTH2} (implicit TLS).
	 * <p>
	 * The provider can be called concurrently and should normally cache a valid token, refreshing it only when necessary. For a
	 * short-lived fixed token, the existing SMTP password configuration remains available.
	 * A caller-supplied {@link jakarta.mail.Session} must declare {@code mail.smtp.auth.mechanisms=XOAUTH2}, or
	 * {@code mail.smtps.auth.mechanisms=XOAUTH2} when its transport protocol is {@code smtps}. Its existing Session and
	 * provider ownership remain unchanged. Configuring both a fixed token and a provider is rejected.
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

	/** @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig) */
	@NotNull MailSendTelemetryConfig getTelemetryConfig();

	/**
	 * Restores captured flags and removes a supplied integration without closing it.
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig)
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
	 * Resets transportModeLoggingOnly to {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_TRANSPORT_MODE_LOGGING_ONLY}.
	 *
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	T resetTransportModeLoggingOnly();

	/**
	 * Clears the explicit defaults template, restoring defaults derived from the Mailer's immutable configuration snapshot.
	 * This does not disable property-backed defaults. Supply an empty defaults Email to replace those too, or use
	 * {@link org.simplejavamail.api.email.config.EmailTemplateOptions.Builder#ignoringDefaults()} to suppress defaults for a particular submitted email.
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
	 * Removes all properties.
	 *
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 */
	T clearProperties();

	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();

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
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();

	/**
	 * Returns a detached property container. Changing it does not change this builder; use the property setters to change the stored choices.
	 * Objects supplied as property values retain their identity.
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
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

    /**
     * Replaces all supplied ConnectionPoolConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see ConnectionPoolConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withConnectionPoolConfig(@NotNull ConnectionPoolConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withConnectionPoolConfig(ConnectionPoolConfig)
     */
    @NotNull ConnectionPoolConfig getConnectionPoolConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withConnectionPoolConfig(ConnectionPoolConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredConnectionPoolConfig();

    /**
     * Replaces all supplied AsyncExecutionConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see AsyncExecutionConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withAsyncExecutionConfig(@NotNull AsyncExecutionConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withAsyncExecutionConfig(AsyncExecutionConfig)
     */
    @NotNull AsyncExecutionConfig getAsyncExecutionConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withAsyncExecutionConfig(AsyncExecutionConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredAsyncExecutionConfig();

    /**
     * Replaces all supplied ProxyConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see SocksProxyConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withProxyConfig(@NotNull SocksProxyConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withProxyConfig(SocksProxyConfig)
     */
    @NotNull SocksProxyConfig getProxyConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withProxyConfig(SocksProxyConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredProxyConfig();

    /**
     * Replaces all supplied SendingLimitsConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see SendingLimitsConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withSendingLimitsConfig(@NotNull SendingLimitsConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withSendingLimitsConfig(SendingLimitsConfig)
     */
    @NotNull SendingLimitsConfig getSendingLimitsConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withSendingLimitsConfig(SendingLimitsConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredSendingLimitsConfig();

    /**
     * Replaces all supplied ConnectionConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see ConnectionConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withConnectionConfig(@NotNull ConnectionConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withConnectionConfig(ConnectionConfig)
     */
    @NotNull ConnectionConfig getConnectionConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withConnectionConfig(ConnectionConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredConnectionConfig();

    /**
     * Replaces all supplied TlsConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see TlsConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withTlsConfig(@NotNull TlsConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withTlsConfig(TlsConfig)
     */
    @NotNull TlsConfig getTlsConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withTlsConfig(TlsConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredTlsConfig();

    /**
     * Replaces all supplied ValidationConfig choices. Unset members inherit the captured factory configuration, not the previous group.
     * Caller-owned Sessions retain their baseline for connection choices left unset; configuration locks remain binding.
     * Detailed setting, resource ownership and reset/clear contracts belong to the supplied value's builder.
     * @param choices Immutable supplied choices; construction starts no workers or connections.
     * @see ValidationConfig#builder()
     */
    @Cli.ExcludeApi(reason = "CLI uses scalar configuration adapters")
    T withValidationConfig(@NotNull ValidationConfig choices);
    /**
     * Returns supplied choices, retaining absence rather than resolving defaults.
     * @see #withValidationConfig(ValidationConfig)
     */
    @NotNull ValidationConfig getValidationConfig();
    /**
     * Restores the captured configuration for the whole group.
     * @see #withValidationConfig(ValidationConfig)
     */
    @Cli.ExcludeApi(reason = "Fresh CLI builders already inherit captured configuration")
    T useConfiguredValidationConfig();
}
