package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

import com.sanctionco.jmail.EmailValidator;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.OpenConnectionCallback;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.mimemessage.SpecializedMimeMessageProducer;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.PropertyDiagnosticText;
import org.simplejavamail.internal.util.concurrent.AsyncOperationHelper;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.util.concurrent.NamedRunnable;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;
import org.simplejavamail.mailer.internal.ratelimit.MailSendRateLimiter;
import org.simplejavamail.mailer.internal.util.SmtpAuthenticator;
import org.simplejavamail.mailer.internal.util.TransportRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.simplejavamail.mailer.internal.MailerException.VALIDATION_ERROR;

/**
 * @see Mailer
 * @see SpecializedMimeMessageProducer
 */
public class MailerImpl implements Mailer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailerImpl.class);
	private static final String LOOPBACK_HOST = InetAddress.getLoopbackAddress().getHostAddress();
	private static final String SESSION_OWNER = "org.simplejavamail.internal.mailerOwner";

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly.
	 */
	@NotNull
	private final Session session;

	/**
	 * @see OperationalConfig
	 */
	@NotNull
	private final OperationalConfig operationalConfig;

	/**
	 * Intermediary SOCKS5 relay server that acts as bridge between JavaMail and remote proxy (since JavaMail only supports anonymous SOCKS proxies).
	 * Only set when {@link ProxyConfig} is provided with authentication details.
	 */
	@Nullable
	private final AnonymousSocks5Server proxyServer;

	/**
	 * Used to keep track of running SMTP requests, so that we know when to close down the proxy bridging server (if used).
	 */
	@NotNull
	private final AtomicInteger smtpConnectionCounter = new AtomicInteger();
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withEmailValidator(EmailValidator)
	 */
	@NotNull
	private final EmailGovernance emailGovernance;
	
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
	@NotNull
	private final ProxyConfig proxyConfig;

	@NotNull
	private final MailSendObserverNotifier mailSendObserverNotifier;
	private final MailSendOperations mailSendOperations;
	private final MailSendRateLimiter sendingLimits;
	private final FactoryMailerRuntime.Definition definition;
	@Nullable private final MailerImpl failoverMailer;
	private final boolean ownsFailoverMailer;
	private final DestinationConnectionObserver connectionObserver;
	private final SessionOwner sessionOwner = new SessionOwner(this);

	private final Sync synchronousView = new SynchronousView();
	private final Async asynchronousView = new AsynchronousView();

	@Nullable private CompletableFuture<Void> shutdownFuture;

	MailerImpl(@NotNull final MailerFromSessionBuilderImpl fromSessionBuilder) {
		this(fromSessionBuilder, fromSessionBuilder.prepareConnectionPlan(fromSessionBuilder.getSession()));
	}

	MailerImpl(final MailerFromSessionBuilderImpl fromSessionBuilder, final ValidatedConnectionPlan plan) {
		this(null,
				null,
				fromSessionBuilder.buildEmailGovernance(),
				verifyNonnull(plan.settings().getProxyConfig()),
				plan.targetSession(),
				fromSessionBuilder.buildOperationalConfig(plan.settings()),
				fromSessionBuilder.buildObserverNotifier(),
				true, fromSessionBuilder.getFactorySendingLimits(), fromSessionBuilder.getDefinition(),
				fromSessionBuilder.getResolvedFailoverMailer(), fromSessionBuilder.ownsFailoverMailer(), plan.settings(), plan);
	}
	
	MailerImpl(@NotNull final MailerRegularBuilderImpl regularBuilder) {
		this(regularBuilder, regularBuilder.prepareConnectionPlan(null));
	}

	MailerImpl(final MailerRegularBuilderImpl regularBuilder, final ValidatedConnectionPlan plan) {
		this(plan.serverConfig(),
				plan.settings().getTransportStrategy(),
				regularBuilder.buildEmailGovernance(),
				verifyNonnull(plan.settings().getProxyConfig()),
				null,
				regularBuilder.buildOperationalConfig(plan.settings()),
				regularBuilder.buildObserverNotifier(),
				regularBuilder.isOpportunisticTLS(), regularBuilder.getFactorySendingLimits(), regularBuilder.getDefinition(),
				regularBuilder.getResolvedFailoverMailer(), regularBuilder.ownsFailoverMailer(), plan.settings(), plan);
	}

	MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EmailGovernance emailGovernance,
			@NotNull ProxyConfig proxyConfig, @Nullable Session session, @NotNull OperationalConfig operationalConfig) {
		this(serverConfig, transportStrategy, emailGovernance, proxyConfig, session, operationalConfig, null, true);
	}

	MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EmailGovernance emailGovernance,
			@NotNull ProxyConfig proxyConfig, @Nullable Session session, @NotNull OperationalConfig operationalConfig, final boolean opportunisticTLS) {
		this(serverConfig, transportStrategy, emailGovernance, proxyConfig, session, operationalConfig, null, opportunisticTLS);
	}

	MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EmailGovernance emailGovernance,
			@NotNull ProxyConfig proxyConfig, @Nullable Session session, @NotNull OperationalConfig operationalConfig,
			@Nullable final MailSendResultObserver mailSendResultObserver, final boolean opportunisticTLS) {
		this(serverConfig, transportStrategy, emailGovernance, proxyConfig, session, operationalConfig,
				new MailSendObserverNotifier(mailSendResultObserver, operationalConfig.isTransportModeLoggingOnly()), opportunisticTLS,
				new FactorySendingLimits(), new FactoryMailerRuntime.Definition(null, ConfigLoader.builder().load()), null, false,
				MailerSessionSettings.fromOperationalConfig(operationalConfig, transportStrategy), null);
	}

	private MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EmailGovernance emailGovernance,
			@NotNull ProxyConfig proxyConfig, @Nullable Session session, @NotNull OperationalConfig operationalConfig,
			@NotNull MailSendObserverNotifier observerNotifier, final boolean opportunisticTLS,
			@NotNull final FactorySendingLimits factorySendingLimits, final FactoryMailerRuntime.Definition definition,
			@Nullable final MailerImpl failoverMailer, final boolean ownsFailoverMailer, final MailerSessionSettings sessionSettings,
			@Nullable final ValidatedConnectionPlan preparedPlan) {
		this.definition = definition;
		this.failoverMailer = failoverMailer;
		this.ownsFailoverMailer = ownsFailoverMailer;
		this.serverConfig = serverConfig;
		this.transportStrategy = transportStrategy;
		this.emailGovernance = emailGovernance;
		this.proxyConfig = proxyConfig;
		final boolean ownsSession = session == null;
		final SimpleJavaMailConfig factoryConfig = emailGovernance instanceof EmailGovernanceImpl
				? ((EmailGovernanceImpl) emailGovernance).configuration() : ConfigLoader.builder().load();
		if (!ownsSession) {
			SessionBasedEmailToMimeMessageConverter.verifySessionCanBeConfigured(session, !locksOf(factoryConfig).isEmpty());
		}
		if (session == null) {
			session = createMailSessionWithoutOAuth2Validation(serverConfig, checkNonEmptyArgument(transportStrategy, "transportStrategy"), opportunisticTLS);
		}
		final ValidatedConnectionPlan applicationPlan = preparedPlan == null
				? ValidatedConnectionPlan.validateApplication(factoryConfig, serverConfig, proxyConfig, session, operationalConfig, sessionSettings, ownsSession)
				: preparedPlan;
		this.session = session;
		this.operationalConfig = operationalConfig;
		this.connectionObserver = new DestinationConnectionObserver(definition.recovery, describeDestination(definition, serverConfig),
				supportsBackgroundConnectionSetup());
		final TransportStrategy effectiveTransportStrategy = sessionSettings.getTransportStrategy();
		final LockedSmtpConfiguration lockedSmtpConfiguration = new LockedSmtpConfiguration(factoryConfig, serverConfig, proxyConfig,
				operationalConfig, effectiveTransportStrategy, sessionSettings.copyEffectiveProperties());
		this.mailSendObserverNotifier = observerNotifier;
		this.mailSendOperations = new MailSendOperations(operationalConfig);
		final SessionConfigurationRollback callerRollback = ownsSession ? null
				: new SessionConfigurationRollback(session, applicationPlan, proxyConfig);
		boolean poolRegistrationBegun = false;
		boolean configurationStarted = false;
		try {
			claimSessionOwnership();
			configurationStarted = true;
			this.proxyServer = configureSendingSession(effectiveTransportStrategy, lockedSmtpConfiguration, ownsSession, applicationPlan);
			poolRegistrationBegun = true;
			this.sendingLimits = registerSendingAllowance(factorySendingLimits);
		} catch (RuntimeException | Error failure) {
			rollbackConstruction(poolRegistrationBegun, failure);
			if (configurationStarted && callerRollback != null && session.getProperties().get(SESSION_OWNER) != sessionOwner) {
				callerRollback.restore(failure);
			}
			throw failure;
		}
	}

	/** Restore only this construction's planned writes after its owned resources and claim have retired. */
	private static final class SessionConfigurationRollback {
		private final Session session;
		private final Map<Object, Object> original = new LinkedHashMap<>();
		private final Set<Object> absent = new LinkedHashSet<>();
		private final boolean debug;
		private final PrintStream debugOut;

		private SessionConfigurationRollback(final Session session, final ValidatedConnectionPlan plan, final ProxyConfig proxy) {
			this.session = session;
			this.debug = session.getDebug();
			this.debugOut = session.getDebugOut();
			final Set<Object> keys = new LinkedHashSet<>(plan.additionalProperties().keySet());
			keys.addAll(plan.settings().getConnectionOverrides().keySet());
			if (proxy.requiresProxy()) {
				final TransportStrategy strategy = plan.settings().getTransportStrategy() == null ? SMTP : plan.settings().getTransportStrategy();
				keys.add(strategy.propertyNameSocksHost());
				keys.add(strategy.propertyNameSocksPort());
			}
			for (Object key : keys) {
				if (session.getProperties().containsKey(key)) {
					original.put(key, session.getProperties().get(key));
				} else {
					absent.add(key);
				}
			}
		}

		private void restore(final Throwable failure) {
			for (Map.Entry<Object, Object> entry : original.entrySet()) {
				try {
					session.getProperties().put(entry.getKey(), entry.getValue());
				} catch (RuntimeException | Error restoreFailure) {
					failure.addSuppressed(restoreFailure);
				}
			}
			for (Object key : absent) {
				try {
					session.getProperties().remove(key);
				} catch (RuntimeException | Error restoreFailure) {
					failure.addSuppressed(restoreFailure);
				}
			}
			try {
				if (session.getDebugOut() != debugOut) { session.setDebugOut(debugOut); }
				if (session.getDebug() != debug) { session.setDebug(debug); }
			} catch (RuntimeException | Error restoreFailure) {
				failure.addSuppressed(restoreFailure);
			}
		}
	}

	private static String describeDestination(final FactoryMailerRuntime.Definition definition, @Nullable final ServerConfig serverConfig) {
		if (definition.name != null) {
			return definition.name;
		}
		if (serverConfig != null && serverConfig.getHost() != null) {
			return serverConfig.getHost();
		}
		return "unnamed SMTP configuration";
	}

	/** Reserve before configuration: another constructor must not replace the converter, proxy route or selected governance. */
	private void claimSessionOwnership() {
		final Properties properties = session.getProperties();
		synchronized (properties) {
			if (properties.containsKey(SESSION_OWNER)) {
				throw new IllegalArgumentException("This Session or its Properties object already belongs to another Mailer, "
						+ "which may still be building or shutting down. Sharing it would mix their email defaults, overrides and connection settings. "
						+ "Reuse the existing Mailer, or create a fresh Session with independent Properties copied from your original SMTP settings. "
						+ "If you do not need a custom Session, call this factory's mailerBuilder() without supplying one.");
			}
			properties.put(SESSION_OWNER, sessionOwner);
		}
	}

	/** Only completed cleanup can retire this owner; failed/rejected construction must never retire somebody else's converter. */
	private void releaseSessionOwnership() {
		final Properties properties = session.getProperties();
		synchronized (properties) {
			if (properties.get(SESSION_OWNER) == sessionOwner) {
				SessionBasedEmailToMimeMessageConverter.unprimeSession(session);
				properties.remove(SESSION_OWNER);
			}
		}
	}

	private MailSendRateLimiter registerSendingAllowance(final FactorySendingLimits factorySendingLimits) {
		return factorySendingLimits.register(definition.name == null ? null : definition.privateAllowance,
				operationalConfig.getRateLimitGroup(), operationalConfig.getMessageRateLimit(),
				operationalConfig.getRecipientRateLimit(), operationalConfig.isRateLimitBurstsAllowed(),
				limiter -> initCluster(session, operationalConfig, limiter));
	}

	/** Configuration may allocate a proxy bridge, but does not start it or connect to SMTP. */
	@Nullable
	private AnonymousSocks5Server configureSendingSession(@Nullable final TransportStrategy effectiveTransportStrategy,
			final LockedSmtpConfiguration lockedSmtpConfiguration, final boolean ownsSession, final ValidatedConnectionPlan plan) {
		final MailerSessionSettings sessionSettings = plan.settings();
		// Inferring SMTP property names must not replace a caller Session's existing authentication-selection behavior.
		final TransportStrategy authenticationStrategy = ownsSession ? effectiveTransportStrategy : TransportStrategy.findStrategyForSession(session);
		final Supplier<String> oauth2AccessTokenProvider = operationalConfig.getCustomMailer() == null ? OAuth2AccessTokenResolver.validateConfiguration(
				Session.getInstance(sessionSettings.finalProperties(session, operationalConfig.getProperties())), new Properties(),
				authenticationStrategy, operationalConfig.getOAuth2AccessTokenProvider()
		) : null;
		initSession(session, operationalConfig, sessionSettings);
		final AnonymousSocks5Server configuredProxy = configureSessionWithProxy(proxyConfig, operationalConfig, session,
				effectiveTransportStrategy, sessionSettings.getProxyConfig() != null);
		try {
			finishSendingSessionConfiguration(lockedSmtpConfiguration, ownsSession, oauth2AccessTokenProvider);
			return configuredProxy;
		} catch (RuntimeException | Error failure) {
			if (configuredProxy != null) {
				try {
					configuredProxy.stop();
				} catch (RuntimeException | Error cleanupFailure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	private void finishSendingSessionConfiguration(final LockedSmtpConfiguration lockedSmtpConfiguration, final boolean ownsSession,
			@Nullable final Supplier<String> oauth2AccessTokenProvider) {
		if (operationalConfig.getCustomMailer() == null) {
			lockedSmtpConfiguration.verifySelected(lockedSmtpConfiguration, session);
		}
		SessionBasedEmailToMimeMessageConverter.primeSession(session, operationalConfig, emailGovernance, lockedSmtpConfiguration);
		if (ownsSession && operationalConfig.getCustomMailer() == null) {
			try {
				MailTransportLifecycleResolver.configureOwnedSession(session);
				// Provider initialization must not silently replace a value supplied by a configuration lock.
				lockedSmtpConfiguration.verifySelected(lockedSmtpConfiguration, session);
			} catch (MessagingException failure) {
				throw new MailerException("Couldn't set up the SMTP transport while building the Mailer. "
						+ "Check the cause and your Jakarta Mail provider dependencies.", failure);
			}
		}
		if (oauth2AccessTokenProvider != null) {
			session.getProperties().put(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY, oauth2AccessTokenProvider);
		}
	}

	/** A failed build must not leave a published cluster participant or owned workers behind. The builder owns failover-graph rollback. */
	private void rollbackConstruction(final boolean poolRegistrationBegun, final Throwable failure) {
		if (!operationalConfig.isExecutorServiceIsUserProvided()) {
			operationalConfig.getExecutorService().shutdown();
		}
		try {
			mailSendOperations.shutdown().get();
			mailSendObserverNotifier.closeProgress();
			if (poolRegistrationBegun) {
				closeConnectionPools().get();
			}
			releaseSessionOwnership();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			failure.addSuppressed(interrupted);
		} catch (ExecutionException | RuntimeException | Error cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}

	FactoryMailerRuntime.Definition definition() {
		return definition;
	}

	/**
	 * Rejects a contradictory override before proxy/pool setup; caller-owned Sessions and custom transports keep their existing ownership.
	 *
	 * @see org.simplejavamail.api.mailer.MailerRegularBuilder#buildMailer()
	 */
	static void validateImplicitTlsOAuth2Protocol(@Nullable final TransportStrategy transportStrategy, @NotNull final Properties additionalProperties) {
		if (transportStrategy == TransportStrategy.SMTPS_OAUTH2) {
			final Object protocolOverride = additionalProperties.get("mail.transport.protocol");
			if (protocolOverride != null && !"smtps".equals(protocolOverride)) {
				throw new MailerException("SMTPS_OAUTH2 requires implicit TLS, but mail.transport.protocol changes the smtps transport. "
						+ "Remove that override or set it to smtps so the access token is sent only over implicit TLS.");
			}
		}
	}

	static void validateMandatoryStartTls(@Nullable final TransportStrategy transportStrategy, @NotNull final Properties additionalProperties) {
		if (transportStrategy != SMTP_TLS && transportStrategy != SMTP_OAUTH2) {
			return;
		}
		// Only direct entries are copied by initSession. Angus accepts Boolean true or the untrimmed, case-insensitive string "true".
		final Object requiredOverride = additionalProperties.get("mail.smtp.starttls.required");
		if (requiredOverride == null || Boolean.TRUE.equals(requiredOverride)
				|| requiredOverride instanceof String && "true".equalsIgnoreCase((String) requiredOverride)) {
			return;
		}
		final String correction = transportStrategy == SMTP_OAUTH2
				? "Remove that override so the access token is sent only after TLS succeeds."
				: "Remove that override to require TLS, or choose TransportStrategy.SMTP if you want opportunistic TLS.";
		throw new MailerException(transportStrategy + " requires STARTTLS, but mail.smtp.starttls.required disables it. " + correction);
	}

	/**
	 * Instantiates and configures the {@link Session} instance. Delegates resolving transport protocol specific properties to the given {@link
	 * TransportStrategy} in two ways: <ol> <li>request an initial property list which the strategy may pre-populate</li> <li>by requesting the
	 * property names according to the respective transport protocol it handles (for the host property for example it would be
	 * <em>"mail.smtp.host"</em> for SMTP and <em>"mail.smtps.host"</em> for SMTPS)</li> </ol>
	 * <p>
	 * This method creates the baseline Session. Mailer construction separately resolves explicit connection declarations, applies the accepted
	 * Session settings under exclusive ownership and configures the resolved proxy route.
	 *
	 * @param serverConfig      Remote SMTP server details.
	 * @param transportStrategy The transport protocol strategy enum that actually handles the session configuration. Session configuration meaning
	 *                          setting the right properties for the appropriate transport type (i.e. <em>"mail.smtp.host"</em> for SMTP,
	 *                          <em>"mail.smtps.host"</em> for SMTPS).
	 *
	 * @return A fully configured <code>Session</code> instance complete with transport protocol settings.
	 * @see TransportStrategy#generateProperties()
	 * @see TransportStrategy#propertyNameHost()
	 * @see TransportStrategy#propertyNamePort()
	 * @see TransportStrategy#propertyNameUsername()
	 * @see TransportStrategy#propertyNameAuthenticate()
	 */
	@NotNull
	public static Session createMailSession(@Nullable final ServerConfig serverConfig, @NotNull final TransportStrategy transportStrategy) {
		final Session session = createMailSessionWithoutOAuth2Validation(serverConfig, transportStrategy, true);
		OAuth2AccessTokenResolver.validateConfiguration(session, new Properties(), transportStrategy, null);
		return session;
	}

	@NotNull
	private static Session createMailSessionWithoutOAuth2Validation(@Nullable final ServerConfig serverConfig, @NotNull final TransportStrategy transportStrategy,
			final boolean opportunisticTLS) {
		final Properties props = transportStrategy.generateProperties();
		if (transportStrategy == SMTP) {
			LOGGER.debug("Opportunistic TLS mode {} for SMTP plain protocol.", opportunisticTLS ? "enabled" : "disabled");
			if (!opportunisticTLS) {
				props.remove("mail.smtp.starttls.enable");
				props.remove("mail.smtp.starttls.required");
			}
		}

		if (serverConfig != null) {
			props.put(transportStrategy.propertyNameHost(), serverConfig.getHost());
			props.put(transportStrategy.propertyNamePort(), String.valueOf(serverConfig.getPort()));
			if (serverConfig.getUsername() != null) {
				props.put(transportStrategy.propertyNameUsername(), serverConfig.getUsername());
			}
			// https://archive.ph/VkrwH (https://www.tutorialspoint.com/javamail_api/javamail_api_smtp_servers.htm)
			if (serverConfig.getCustomSSLFactoryInstance() != null) {
				props.put(transportStrategy.propertyNameSSLSocketFactory(), serverConfig.getCustomSSLFactoryInstance());
			} else if (serverConfig.getCustomSSLFactoryClass() != null) {
				props.put(transportStrategy.propertyNameSSLSocketFactoryClass(), serverConfig.getCustomSSLFactoryClass());
			}

			if (serverConfig.getPassword() != null) {
				if (!OAuth2AccessTokenResolver.isOAuth2Transport(transportStrategy)) {
					props.put(transportStrategy.propertyNameAuthenticate(), "true");
					return Session.getInstance(props, new SmtpAuthenticator(serverConfig));
				} else {
					// props.put(transportStrategy.propertyNameAuthenticate(), "false");
					props.put(TransportStrategy.OAUTH2_TOKEN_PROPERTY, serverConfig.getPassword());
					return Session.getInstance(props);
				}
			}
		}
		return Session.getInstance(props);
	}

	private static void initSession(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig,
			final MailerSessionSettings sessionSettings) {
		if (sessionSettings.shouldApplyDebugEnabled()) {
			session.setDebug(Boolean.TRUE.equals(operationalConfig.getDebugConfig().getEnabled()));
		}
		if (sessionSettings.shouldApplyDebugOutput()) {
			if (operationalConfig.getDebugConfig().getPrinter() != null) {
				session.setDebugOut(operationalConfig.getDebugConfig().getPrinter());
			} else if (operationalConfig.getDebugConfig().getOutput() != null) {
				session.setDebugOut(SessionDebugOutputResolver.resolve(operationalConfig.getDebugConfig().getOutput()));
			}
		}
		session.getProperties().putAll(operationalConfig.getProperties());
		sessionSettings.applyConnectionOverrides(session);
	}

	/**
	 * Installs the already resolved remote proxy route on the Session. Construction compares explicit connection declarations before reaching this step.
	 * <p>
	 * These properties are <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em>. The resolved SMTP or SMTPS protocol selects the
	 * property names, including for a caller-supplied Session without Simple Java Mail's transport-strategy marker.
	 * Authenticated proxying sets the host to the JVM loopback address. The configured bridge port is used as the
	 * initial port value; after the bridge starts, the actual bound port is written immediately before the transport connects.
	 *
	 * @param proxyConfig       Proxy server details, optionally with username / password.
	 * @param session           The session with properties to add the new configuration to.
	 * @param transportStrategy Resolved protocol used for property names. Internal callers can omit it to use <em>"mail.smtp.socks.*"</em> names.
	 * @param routePrepared Whether the application plan already installed the anonymous route without filling caller-Session gaps.
	 * @return null in case of no proxy or anonymous proxy, or a AnonymousSocks5Server proxy bridging server instance in case of authenticated proxy.
	 */
	@Nullable
	private static AnonymousSocks5Server configureSessionWithProxy(
			@NotNull final ProxyConfig proxyConfig,
			@NotNull final OperationalConfig operationalConfig,
			@NotNull final Session session,
			@Nullable final TransportStrategy transportStrategy, final boolean routePrepared) {
		if (operationalConfig.getCustomMailer() != null) {
			LOGGER.trace("CustomMailer provided by user, skipping proxy.");
		} else if (!proxyConfig.requiresProxy()) {
			LOGGER.trace("No proxy set, skipping proxy.");
		} else {
			final TransportStrategy proxyPropertyStrategy = transportStrategy != null ? transportStrategy : TransportStrategy.SMTP;
			final Properties sessionProperties = session.getProperties();
			if (!routePrepared) {
				sessionProperties.put(proxyPropertyStrategy.propertyNameSocksHost(), verifyNonnullOrEmpty(proxyConfig.getRemoteProxyHost()));
				sessionProperties.put(proxyPropertyStrategy.propertyNameSocksPort(), String.valueOf(proxyConfig.getRemoteProxyPort()));
			}
			if (proxyConfig.requiresAuthentication()) {
				// wire anonymous proxy request to our own proxy bridge, so we can perform authentication to the actual proxy
				sessionProperties.put(proxyPropertyStrategy.propertyNameSocksHost(), LOOPBACK_HOST);
				sessionProperties.put(proxyPropertyStrategy.propertyNameSocksPort(), String.valueOf(proxyConfig.getProxyBridgePort()));
				return ModuleLoader.loadAuthenticatedSocksModule().createAnonymousSocks5Server(proxyConfig);
			}
		}
		return null;
	}

	private void initCluster(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig, final SendingAllowance allowance) {
		if (ModuleLoader.batchModuleAvailable()) {
			ModuleLoader.loadBatchModule().registerToCluster(operationalConfig, operationalConfig.getClusterKey(), session, allowance, connectionObserver);
		}
	}

	/** @see Mailer#sync() */
	@Override
	@NotNull
	public Sync sync() {
		return synchronousView;
	}

	/** @see Mailer#async() */
	@Override
	@NotNull
	public Async async() {
		return asynchronousView;
	}

	/** @see Mailer.Sync#testConnection() */
	private synchronized void testConnectionSynchronously() {
		ensureConnectionTestAllowed();
		SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
		new TestConnectionClosure(operationalConfig, session, proxyServer, smtpConnectionCounter).run();
	}

	/** @see Mailer.Async#testConnection() */
	private CompletableFuture<Void> testConnectionAsynchronously() {
		try {
			ensureConnectionTestAllowed();
			// Hand off outside the Mailer monitor: the executor may wait for its worker, which rechecks shutdown under that monitor.
			return executeMailOperationAsync("testSMTPConnection process", this::testConnectionSynchronously);
		} catch (RuntimeException failure) {
			return failedConnectionCheckAdmission(failure);
		}
	}

	private synchronized void ensureConnectionTestAllowed() {
		if (shutdownFuture != null) {
			throw new IllegalStateException("This Mailer is shutting down. Build a new Mailer before testing another connection.");
		}
	}

	/** @see Mailer.Sync#probeConnection(boolean) */
	private synchronized SmtpConnectionReport probeConnectionSynchronously(final boolean authenticate) {
		// Lock the owning Mailer, not its view, so shutdown cannot close the shared proxy during this connection.
		if (shutdownFuture != null) {
			throw new IllegalStateException("This Mailer is shutting down. Build a new Mailer before probing another connection.");
		}
		SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
		return SmtpConnectionProbe.probe(session, operationalConfig, authenticate, proxyServer, smtpConnectionCounter);
	}

	/** @see Mailer.Async#probeConnection(boolean) */
	private CompletableFuture<SmtpConnectionReport> probeConnectionAsynchronously(final boolean authenticate) {
		try {
			return CompletableFuture.supplyAsync(() -> probeConnectionSynchronously(authenticate), operationalConfig.getExecutorService());
		} catch (RuntimeException failure) {
			return failedConnectionCheckAdmission(failure);
		}
	}

	/** Maps only scheduling rejection; failures from the actual connection work are already captured by its future. */
	private static <T> CompletableFuture<T> failedConnectionCheckAdmission(final RuntimeException failure) {
		return AsyncOperationHelper.failedFuture(failure instanceof RejectedExecutionException
				? new MailSendRejectedException(AsyncQueueRejectionReason.EXECUTOR_REJECTED, failure) : failure);
	}

	/**
	 * @see Mailer.Sync#sendMail(Email)
	 */
	@NotNull
	private MailSubmissionReceipt sendMailSynchronously(final Email userProvidedEmail) {
		final Email checkedEmail = verifyNonnull(userProvidedEmail);
		final MailSendAttempt attempt = mailSendObserverNotifier.beginAttempt(checkedEmail);
		final MailSendOperation<MailSubmissionReceipt> operation = beginEmailOperation(attempt);
		attempt.readyForAdmission();
		return operation.executeSync(() -> executeEmailSend(checkedEmail, attempt, operation));
	}

	/**
	 * @see Mailer.Async#sendMail(Email)
	 */
	@NotNull
	private MailSend<MailSubmissionReceipt> sendMailAsynchronously(final Email userProvidedEmail) {
		final Email checkedEmail = verifyNonnull(userProvidedEmail);
		final MailSendAttempt attempt;
		try {
			attempt = mailSendObserverNotifier.beginAttempt(checkedEmail);
		} catch (RuntimeException failure) {
			return new MailSend<>(AsyncOperationHelper.failedFuture(failure), () -> { });
		}
		attempt.diagnostics().useAsyncScheduling();
		final MailSendOperation<MailSubmissionReceipt> operation;
		try {
			operation = beginEmailOperation(attempt);
		} catch (final RuntimeException failure) {
			return new MailSend<>(AsyncOperationHelper.failedFuture(failure), () -> { });
		}
		attempt.readyForAdmission();
		operation.schedule(() -> executeEmailSend(checkedEmail, attempt, operation));
		return operation.handle();
	}

	private MailSendOperation<MailSubmissionReceipt> beginEmailOperation(final MailSendAttempt attempt) {
		try {
			final MailSendOperation<MailSubmissionReceipt> operation = mailSendOperations.begin(attempt::completeSuccessfully, attempt::completeWithFailure);
			attempt.attachControl(operation.control());
			operation.observeWith(attempt);
			return operation;
		} catch (RuntimeException failure) {
			attempt.completeWithFailure(failure);
			throw failure;
		}
	}

	private void validateDeadlineSupport() {
		validateDeadlineSupport(operationalConfig.getMailSendTimeout() != null);
	}

	private void validateDeadlineSupport(final boolean deadlineConfigured) {
		if (deadlineConfigured && !operationalConfig.isTransportModeLoggingOnly()) {
			if (operationalConfig.getCustomMailer() != null) {
				throw new MailerException("Don't configure a total send timeout when using CustomMailer: Simple Java Mail can't stop its sending code. "
						+ "Handle timeouts in your CustomMailer instead. If the timeout comes from configuration you can't change, "
						+ "call resetMailSendTimeout() on the builder to disable it.");
			}
			MailTransportLifecycleResolver.requireAbortSupport(session);
		}
	}

	@NotNull
	private MailSubmissionReceipt executeEmailSend(final Email originalEmail, final MailSendAttempt attempt,
			final MailSendOperation<MailSubmissionReceipt> operation) {
		final MailSendControl control = operation.control();
		attempt.started();
		try {
			final ReadyMailSend ready = new MailConnectionRoute<>(this, attempt.diagnostics(), control,
					(destination, selected, stages) -> ReadyMailSend.prepare(this, destination, selected, stages, originalEmail, attempt, control)).execute();
			if (!ready.supportsAutomaticRetry()) {
				return ready.sendAndClose();
			}
			try {
				return operation.executeRetrying(ready.retryingSubmission(mailSendOperations.replayBudget()));
			} catch (RuntimeException | Error failure) {
				// The route already owns a connection. Even failure while constructing the continuation must retire that ownership.
				ready.closeAfterFailure(failure);
				throw failure;
			}
		} catch (RuntimeException failure) {
			throw control.translateFailure(failure);
		}
	}

	MailSendReplayBudget replayBudget() { return mailSendOperations.replayBudget(); }

	static MailerImpl selectedOwner(final Session selectedSession) {
		final Object owner = selectedSession.getProperties().get(SESSION_OWNER);
		if (!(owner instanceof SessionOwner)) {
			throw new MailerException("The selected pool connection has no owning Simple Java Mail Mailer. "
					+ "Register cluster members through mailerBuilder() so their email settings and resource lifetimes can be honored.");
		}
		return ((SessionOwner) owner).mailer;
	}

	/** Session diagnostics must not recursively render the Mailer and its Session, or expose this runtime owner's configuration. */
	private static final class SessionOwner {
		private final MailerImpl mailer;

		private SessionOwner(final MailerImpl mailer) {
			this.mailer = mailer;
		}

		@Override
		public String toString() {
			return "Simple Java Mail sending context";
		}
	}

	/** Ordinary templates belong only to the selected member; the initiating Mailer's mandatory message locks still travel with the send. */
	Email prepareSelectedEmail(final Email originalEmail, final MailerImpl destination, final List<MailerImpl> stages) {
		verifySelectedCustomMailerLockCompatibility(destination, stages);
		final Email governedEmail = applySelectedEmailGovernance(originalEmail, destination, stages);
		final Email lockedEmail = applyTraversedMessageLocks(governedEmail, destination, stages);
		validateSelectedEmail(lockedEmail, destination);
		return lockedEmail;
	}

	private void verifySelectedCustomMailerLockCompatibility(final MailerImpl destination, final List<MailerImpl> stages) {
		if (destination.operationalConfig.getCustomMailer() != null) {
			for (MailerImpl stage : stages) {
				LockedEmailConfiguration.verifyCustomMailerCompatibility(stage.definition.config);
			}
		}
	}

	private Email applySelectedEmailGovernance(final Email originalEmail, final MailerImpl destination, final List<MailerImpl> stages) {
		final InternalEmail internalEmail = InternalEmail.requireInternalEmail(originalEmail);
		if (internalEmail.isExactEml() || !(destination.emailGovernance instanceof EmailGovernanceImpl)) {
			return internalEmail.prepareForConversion(destination.emailGovernance);
		}
		final List<EmailGovernanceImpl> additionalLockOwners = new ArrayList<>();
		for (final MailerImpl stage : stages) {
			if (stage != destination && stage.emailGovernance instanceof EmailGovernanceImpl) {
				additionalLockOwners.add((EmailGovernanceImpl) stage.emailGovernance);
			}
		}
		return ((EmailGovernanceImpl) destination.emailGovernance)
				.produceEmailApplyingDefaultsAndOverrides(originalEmail, additionalLockOwners);
	}

	private Email applyTraversedMessageLocks(final Email governedEmail, final MailerImpl destination, final List<MailerImpl> stages) {
		Email lockedEmail = governedEmail;
		// The selected destination already applied its locks with its own governance; earlier owners contribute locks only.
		for (MailerImpl stage : stages) {
			if (destination != stage && stage.emailGovernance instanceof EmailGovernanceImpl) {
				lockedEmail = ((EmailGovernanceImpl) stage.emailGovernance).applyLocksToPreparedEmail(lockedEmail);
			}
		}
		return lockedEmail;
	}

	private void validateSelectedEmail(final Email email, final MailerImpl destination) {
		InternalEmail.requireInternalEmail(email).validateForSending(destination.emailGovernance,
				destination.operationalConfig.isClientValidationWarningsOnly());
	}

	<T> T withDelegatedOperation(final MailerImpl initiator, final Supplier<T> work) {
		try (MailSendOperations.Scope ignored = this == initiator ? null : mailSendOperations.openScope()) {
			return mailSendOperations.withinOperation(work);
		}
	}

	MailSendOperations.Scope retainDelegatedScope() {
		return mailSendOperations.openScope();
	}

	<T> T withinDelegatedExecution(final Supplier<T> work) {
		return mailSendOperations.withinOperation(work);
	}

	SendingAllowance sendingAllowance() {
		return sendingLimits;
	}

	DestinationConnectionObserver connectionObserver() {
		return connectionObserver;
	}

	void validateSelectedDestination(final boolean deadlineConfigured) {
		validateDeadlineSupport(deadlineConfigured);
		SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
	}

	SmtpProxyScope openProxy() {
		final SmtpProxyScope scope = new SmtpProxyScope(smtpConnectionCounter, proxyServer, session);
		try {
			scope.start();
			return scope;
		} catch (RuntimeException | Error failure) {
			try {
				scope.close();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	@Nullable
	MailerImpl failoverMailer() {
		return failoverMailer;
	}

	boolean usesSmtpConnection() {
		return !operationalConfig.isTransportModeLoggingOnly() && operationalConfig.getCustomMailer() == null;
	}

	private boolean supportsBackgroundConnectionSetup() {
		// Authenticated SOCKS uses an operation-owned local bridge. Background allocation cannot keep that bridge alive or restart it safely.
		return usesSmtpConnection() && !(proxyConfig.requiresProxy() && proxyConfig.requiresAuthentication());
	}

	@Nullable
	String logicalHost() {
		return serverConfig != null ? serverConfig.getHost() : session.getProperty(selectedProtocolPrefix() + ".host");
	}

	@Nullable
	Integer logicalPort() {
		if (serverConfig != null) {
			return serverConfig.getPort();
		}
		try {
			final String value = session.getProperty(selectedProtocolPrefix() + ".port");
			final int port = value == null ? -1 : Integer.parseInt(value);
			return port >= 1 && port <= 65535 ? port : null;
		} catch (NumberFormatException unspecifiedPort) {
			return null;
		}
	}

	private String selectedProtocolPrefix() {
		return "mail." + session.getProperties().getProperty("mail.transport.protocol", "smtp");
	}

	List<SelectedPoolTransport> connectionCandidates(final MailSendControl control, final boolean failoverEnabled) {
		if (!ModuleLoader.batchModuleAvailable()) {
			return Collections.singletonList(null);
		}
		return failoverEnabled ? ModuleLoader.loadBatchModule().selectTransportCandidates(operationalConfig.getClusterKey(), control)
				: Collections.singletonList(TransportRunner.selectTransport(operationalConfig.getClusterKey(), session, control));
	}

	private CompletableFuture<Void> executeMailOperationAsync(final String processName, final Runnable operation) {
		if (ownsMailSendExecutor()) {
			return CompletableFuture.runAsync(new NamedRunnable(processName, operation), operationalConfig.getExecutorService());
		}
		return AsyncOperationHelper.executeAsync(operationalConfig.getExecutorService(), processName, operation);
	}

	/**
	 * @see Mailer#withOpenConnection(OpenConnectionCallback)
	 */
	@Override
	public final <E extends Exception> void withOpenConnection(@NotNull final OpenConnectionCallback<E> openConnectionCallback) throws E {
		val checkedOpenConnectionCallback = verifyNonnull(openConnectionCallback);
		validateDeadlineSupport();
		try (MailSendOperations.Scope ignored = mailSendOperations.openScope()) {
			if (usesSmtpConnection() && failoverMailer != null) {
				new FailoverSharedSends(this, mailSendOperations, mailSendObserverNotifier).withOpenConnection(checkedOpenConnectionCallback);
				return;
			}
			SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
			new SendMailsWithOpenConnectionClosure<>(operationalConfig, session, checkedOpenConnectionCallback, this::prepareEmailForSending,
					mailSendObserverNotifier, proxyServer, operationalConfig.isTransportModeLoggingOnly(), smtpConnectionCounter, mailSendOperations, sendingLimits)
					.runOpenConnectionCallback();
		}
	}

	/** @see Mailer.Sync#sendMailsInSimpleBatch(Iterable) */
	private void sendSimpleBatchSynchronously(final Iterable<Email> emails) {
		final Iterable<Email> checkedEmails = verifyNonnull(emails);
		final MailSendTelemetryBridge.CapturedContext telemetry = mailSendObserverNotifier.captureTelemetryContext();
		final MailSendOperation<Void> operation = mailSendOperations.begin(unused -> { }, failure -> { });
		operation.observeWith(telemetry);
		operation.executeSync(() -> {
			sendSimpleBatch(checkedEmails, operation.control());
			return null;
		});
	}

	/** @see Mailer.Async#sendMailsInSimpleBatch(Iterable) */
	private MailSend<Void> sendSimpleBatchAsynchronously(final Iterable<Email> emails) {
		final Iterable<Email> checkedEmails = verifyNonnull(emails);
		final MailSendTelemetryBridge.CapturedContext telemetry = mailSendObserverNotifier.captureTelemetryContext();
		final MailSendOperation<Void> operation;
		try {
			operation = mailSendOperations.begin(unused -> { }, failure -> { });
			operation.observeWith(telemetry);
		} catch (final RuntimeException failure) {
			return new MailSend<>(AsyncOperationHelper.failedFuture(failure), () -> { });
		}
		operation.schedule(() -> {
			sendSimpleBatch(checkedEmails, operation.control());
			return null;
		});
		return operation.handle();
	}

	private void sendSimpleBatch(@NotNull final Iterable<Email> emails, final MailSendControl control) {
		if (usesSmtpConnection() && failoverMailer != null) {
			new FailoverSharedSends(this, mailSendOperations, mailSendObserverNotifier).sendBatch(emails, control);
			return;
		}
		validateDeadlineSupport();
		SessionBasedEmailToMimeMessageConverter.verifySelectedSession(session, session);
		new SendMailsInSimpleBatchClosure(operationalConfig, session, emails, this::prepareEmailForSending, mailSendObserverNotifier,
				proxyServer, operationalConfig.isTransportModeLoggingOnly(), smtpConnectionCounter, control, sendingLimits)
				.run();
	}

	@NotNull
	private Email prepareEmailForSending(final Email userProvidedEmail) {
		return InternalEmail.requireInternalEmail(verifyNonnull(userProvidedEmail)).prepareForSending(
				emailGovernance, operationalConfig.isClientValidationWarningsOnly());
	}

	/**
	 * @see Mailer#rehearse(Email)
	 */
	@Override
	@NotNull
	public MailRehearsal rehearse(@NotNull final Email email) throws MailException {
		return rehearse(email, true);
	}

	/**
	 * @see Mailer#rehearse(Email, boolean)
	 */
	@Override
	@NotNull
	public MailRehearsal rehearse(@NotNull final Email email, final boolean processSecurityAndValidateSize) throws MailException {
		val governedEmail = prepareEmailForSending(email);
		// A rehearsal owns its effective snapshot and must never propagate a generated Message-ID to the caller's draft.
		//noinspection deprecation
		((InternalEmail) governedEmail).setUserProvidedEmail(null);
		try {
			val rehearsal = SessionBasedEmailToMimeMessageConverter.rehearseMimeMessage(session, governedEmail, processSecurityAndValidateSize);
			if (rehearsal.getEmailId() != null) {
				// Keep the effective Email consistent with the EML while its detached source draft remains unchanged.
				//noinspection deprecation
				((InternalEmail) governedEmail).updateId(rehearsal.getEmailId());
			}
			return rehearsal;
		} catch (MessagingException e) {
			throw new MailerException(MailFailureKind.PREPARATION, format(VALIDATION_ERROR, governedEmail.getId()), e);
		}
	}

	/**
	 * @see Mailer#validate(Email)
	 */
	@Override
	public void validate(@NotNull final Email email)
			throws MailException {
		rehearse(email);
	}

	/**
	 * @see Mailer#validate(Email, boolean)
	 */
	@Override
	public void validate(@NotNull final Email email, final boolean processSecurityAndValidateSize)
			throws MailException {
		rehearse(email, processSecurityAndValidateSize);
	}

	/**
	 * @see Mailer#closeAsync()
	 */
	@Override
	public synchronized CompletableFuture<Void> closeAsync() {
		if (shutdownFuture == null) {
			mailSendOperations.shutdown();
			shutdownFuture = CompletableFuture.runAsync(this::drainExecutorAndCloseConnectionPools, MailerImpl::startShutdownThread);
		}
		// Callers may compose, cancel or complete their view, but only the owned shutdown task can complete actual cleanup.
		return shutdownFuture.copy();
	}

	private void drainExecutorAndCloseConnectionPools() {
		try {
			// Accepted operations may still be parked for retry. Keep their executor open until every logical operation has finished.
			mailSendOperations.shutdown().get();
			// Reporting is not part of graceful draining. Discard pending progress without waiting for an application callback or executor.
			mailSendObserverNotifier.closeProgress();
			if (!operationalConfig.isExecutorServiceIsUserProvided()) { operationalConfig.getExecutorService().shutdown(); }
			while (!operationalConfig.isExecutorServiceIsUserProvided() && !operationalConfig.getExecutorService().awaitTermination(1, TimeUnit.DAYS)) {
				// Graceful shutdown retains accepted work; a capacity timeout is not a send timeout.
			}
			closeOwnedConnectionResources();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new CompletionException(interrupted);
		} catch (ExecutionException failure) {
			throw new CompletionException(failure.getCause());
		}
	}

	private void closeOwnedConnectionResources() throws InterruptedException, ExecutionException {
		try {
			closeConnectionPools().get();
		} catch (InterruptedException | ExecutionException | RuntimeException | Error failure) {
			try {
				closeOwnedFailoverMailer();
			} catch (InterruptedException | ExecutionException | RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
		closeOwnedFailoverMailer();
		releaseSessionOwnership();
	}

	private void closeOwnedFailoverMailer() throws InterruptedException, ExecutionException {
		if (ownsFailoverMailer && failoverMailer != null) {
			failoverMailer.close();
		}
	}

	private static void startShutdownThread(final Runnable cleanup) {
		// Waiting for sends must not occupy the common pool: application callbacks may need that pool to finish those sends.
		final Thread thread = new Thread(cleanup, "Simple Java Mail graceful shutdown");
		thread.setDaemon(true);
		thread.start();
	}

	private Future<Void> closeConnectionPools() {
		if (ModuleLoader.batchModuleAvailable()) {
			return ModuleLoader.loadBatchModule().shutdownConnectionPools(session);
		}
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public String toString() {
		return "MailerImpl {"
				+ "\n\tserverConfig=" + serverConfig
				+ ",\n\ttransportStrategy=" + transportStrategy
				+ ",\n\tproxyConfig=" + proxyConfig
				+ ",\n\toperationalConfig=" + operationalConfig
				+ ",\n\temailGovernance=" + emailGovernance
				+ ",\n\tsession=" + PropertyDiagnosticText.render(session.getProperties())
				+ "\n}";
	}

	/**
	 * @see Mailer#getSession()
	 */
	@Override
	public Session getSession() {
		return session;
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
	@NotNull
	public ProxyConfig getProxyConfig() {
		return this.proxyConfig;
	}

	/**
	 * @see Mailer#getOperationalConfig()
	 */
	@Override
	@NotNull
	public OperationalConfig getOperationalConfig() {
		return operationalConfig;
	}

	/** @see Mailer#getAsyncQueueSnapshot() */
	@Override
	@NotNull
	public Optional<AsyncQueueSnapshot> getAsyncQueueSnapshot() {
		return ownsMailSendExecutor()
				? Optional.of(((MailSendExecutor) operationalConfig.getExecutorService()).snapshot()) : Optional.empty();
	}

	private boolean ownsMailSendExecutor() {
		return !operationalConfig.isExecutorServiceIsUserProvided() && operationalConfig.getExecutorService() instanceof MailSendExecutor;
	}

	/**
	 * @see Mailer#getEmailGovernance()
	 */
	@Override
	@NotNull
	public EmailGovernance getEmailGovernance() {
		return emailGovernance;
	}

	/**
	 * @see Mailer#close()
	 */
	@Override
	public void close() throws ExecutionException, InterruptedException {
		if (mailSendOperations.isCurrentOperation() || (ownsMailSendExecutor() && MailSendExecutor.isCurrentWorker(operationalConfig.getExecutorService()))) {
			throw new IllegalStateException("A Mailer worker cannot wait for its own shutdown; close the Mailer from its owning application thread");
		}
		closeAsync().get();
	}

	/** Fixed execution choices over this Mailer's existing paths; owns no resources or locks. */
	private final class SynchronousView implements Sync {
		/** @see Mailer.Sync#sendMail(Email) */
		@Override
		@NotNull
		public MailSubmissionReceipt sendMail(final Email email) {
			return sendMailSynchronously(email);
		}

		/** @see Mailer.Sync#sendMailsInSimpleBatch(Iterable) */
		@Override
		public void sendMailsInSimpleBatch(final Iterable<Email> emails) {
			sendSimpleBatchSynchronously(emails);
		}

		/** @see Mailer.Sync#testConnection() */
		@Override
		public void testConnection() {
			testConnectionSynchronously();
		}

		/** @see Mailer.Sync#probeConnection() */
		@Override
		@NotNull
		public SmtpConnectionReport probeConnection() {
			return probeConnection(false);
		}

		/** @see Mailer.Sync#probeConnection(boolean) */
		@Override
		@NotNull
		public SmtpConnectionReport probeConnection(final boolean authenticate) {
			return probeConnectionSynchronously(authenticate);
		}
	}

	/** Fixed execution choices over this Mailer's existing paths; owns no resources or locks. */
	private final class AsynchronousView implements Async {
		/** @see Mailer.Async#sendMail(Email) */
		@Override
		@NotNull
		public MailSend<MailSubmissionReceipt> sendMail(final Email email) {
			return sendMailAsynchronously(email);
		}

		/** @see Mailer.Async#sendMailsInSimpleBatch(Iterable) */
		@Override
		@NotNull
		public MailSend<Void> sendMailsInSimpleBatch(final Iterable<Email> emails) {
			return sendSimpleBatchAsynchronously(emails);
		}

		/** @see Mailer.Async#testConnection() */
		@Override
		@NotNull
		public CompletableFuture<Void> testConnection() {
			return testConnectionAsynchronously();
		}

		/** @see Mailer.Async#probeConnection() */
		@Override
		@NotNull
		public CompletableFuture<SmtpConnectionReport> probeConnection() {
			return probeConnection(false);
		}

		/** @see Mailer.Async#probeConnection(boolean) */
		@Override
		@NotNull
		public CompletableFuture<SmtpConnectionReport> probeConnection(final boolean authenticate) {
			return probeConnectionAsynchronously(authenticate);
		}
	}
}
