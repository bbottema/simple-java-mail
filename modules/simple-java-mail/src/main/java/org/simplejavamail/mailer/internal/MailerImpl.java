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

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
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

/**
 * @see Mailer
 * @see SpecializedMimeMessageProducer
 */
public class MailerImpl implements Mailer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailerImpl.class);
	static final String SESSION_OWNER = "org.simplejavamail.internal.mailerOwner";

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
	private final RehearsalSessionContext rehearsalSessionContext;
	private final MailSendRateLimiter sendingLimits;
	private final FactoryMailerRuntime.Definition definition;
	@Nullable private final MailerImpl failoverMailer;
	private final boolean ownsFailoverMailer;
	private final DestinationConnectionObserver connectionObserver;
	private final SessionOwner sessionOwner = new SessionOwner(this);

	private final Sync synchronousView = new SynchronousView();
	private final Async asynchronousView = new AsynchronousView();

	@Nullable private CompletableFuture<Void> shutdownFuture;

	/** Reserve all live allowance rules before constructing the validated owned route. */
	static MailerImpl construct(final ValidatedMailerPlan plan) {
		final ConstructionAttempt attempt = ConstructionAttempt.reserve(plan);
		try {
			final MailerImpl mailer = constructNode(plan, attempt);
			if (mailer.failoverMailer() != null) {
				for (MailerImpl participant = mailer; participant != null; participant = participant.failoverMailer()) {
					participant.definition().recovery.enableCoordinatedFailover();
				}
			}
			attempt.commit();
			return mailer;
		} catch (RuntimeException | Error failure) {
			attempt.rollback(failure);
			throw failure;
		}
	}

	private static MailerImpl constructNode(final ValidatedMailerPlan plan, final ConstructionAttempt attempt) {
		final ResolvedMailerConfiguration resolved = plan.getConfiguration();
		final CapturedMailerInputs inputs = resolved.getInputs();
		final MailerOperationalSettings settings = resolved.getOperational();
		final ConstructionAllowance allowance = attempt.nodes.get(plan);
		final MailerImpl child = plan.getNamedChild() == null ? inputs.getBorrowedFailover() : constructNode(plan.getNamedChild(), attempt);
		final ExecutorService executor;
		if (settings.getExecutorService() == null) {
			allowance.ownedExecutor = settings.createOwnedExecutor();
			executor = allowance.ownedExecutor;
		} else {
			executor = settings.getExecutorService();
		}
		final OperationalConfig operational = settings.createOperationalConfig(resolved.getSessionSettings(), executor);
		allowance.notifier = bindObserverNotifier(inputs, settings);
		return new MailerImpl(plan.getConnection().serverConfig(), inputs.isRegularMailer() ? resolved.getSessionSettings().getTransportStrategy() : null,
				resolved.getGovernance(), verifyNonnull(resolved.getSessionSettings().getProxyConfig()), plan.getConnection().targetSession(), operational,
				allowance.notifier, inputs.isOpportunisticTLS(), allowance, inputs.getDefinition(), child, plan.getNamedChild() != null,
				resolved.getSessionSettings(), plan.getConnection());
	}

	/** Captures observer registrations and binds the already-resolved telemetry permission; no progress dispatcher is started here. */
	private static MailSendObserverNotifier bindObserverNotifier(final CapturedMailerInputs inputs, final MailerOperationalSettings settings) {
		return MailSendObserverNotifier.builder().handlers(inputs.getHandlers()).handlersFactory(inputs.getHandlersFactory())
				.idGenerator(inputs.getIdGenerator()).loggingOnly(settings.isTransportModeLoggingOnly())
				.telemetry(MailSendTelemetryRecorder.bind(settings.getTelemetryConfig().getIntegration(),
						Boolean.TRUE.equals(settings.getTelemetryConfig().getEnabled()),
						Boolean.TRUE.equals(settings.getTelemetryConfig().getSmtpEndpointAttributes()))).build();
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
				ConstructionAllowance.standalone(operationalConfig), new FactoryMailerRuntime.Definition(null, ConfigLoader.builder().load()), null, false,
				MailerSessionSettings.fromOperationalConfig(operationalConfig, transportStrategy), null);
	}

	private MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EmailGovernance emailGovernance,
			@NotNull ProxyConfig proxyConfig, @Nullable Session session, @NotNull OperationalConfig operationalConfig,
			@NotNull MailSendObserverNotifier observerNotifier, final boolean opportunisticTLS,
			@NotNull final ConstructionAllowance allowance, final FactoryMailerRuntime.Definition definition,
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
		try {
			final SimpleJavaMailConfig factoryConfig = emailGovernance instanceof EmailGovernanceImpl
					? ((EmailGovernanceImpl) emailGovernance).configuration() : ConfigLoader.builder().load();
			if (!ownsSession) { SessionBasedEmailToMimeMessageConverter.verifySessionCanBeConfigured(session, !locksOf(factoryConfig).isEmpty()); }
			final Properties baseline = ownsSession
					? mailSessionProperties(serverConfig, checkNonEmptyArgument(transportStrategy, "transportStrategy"), opportunisticTLS)
					: preparedPlan == null ? SessionPropertyObservation.snapshot(session.getProperties()) : null;
			final ValidatedConnectionPlan applicationPlan = preparedPlan == null
					? ValidatedConnectionPlan.validateApplication(factoryConfig, serverConfig, proxyConfig, session, baseline, operationalConfig, sessionSettings, ownsSession)
					: preparedPlan;
			if (ownsSession) { session = createOwnedSession(serverConfig, transportStrategy, sessionSettings.finalProperties(baseline, operationalConfig.getProperties())); }
			this.session = session;
			this.rehearsalSessionContext = new RehearsalSessionContext(session, SESSION_OWNER);
			this.operationalConfig = operationalConfig;
			this.connectionObserver = new DestinationConnectionObserver(definition.recovery, describeDestination(definition, serverConfig),
					supportsBackgroundConnectionSetup());
			final TransportStrategy effectiveTransportStrategy = sessionSettings.getTransportStrategy();
			final LockedSmtpConfiguration lockedSmtpConfiguration = new LockedSmtpConfiguration(factoryConfig, serverConfig, proxyConfig,
					operationalConfig, effectiveTransportStrategy, sessionSettings.copyEffectiveProperties());
			this.mailSendObserverNotifier = observerNotifier;
			this.mailSendOperations = new MailSendOperations(operationalConfig);
			this.sendingLimits = allowance.reservation.limiter();
			allowance.mailer = this;
			allowance.ownsSession = ownsSession;
			claimSessionOwnership();
			applicationPlan.verifyClaimedBaseline(session);
			allowance.applied = applicationPlan.changes().capture(session, !ownsSession);
			allowance.applied.apply(operationalConfig.getDebugConfig(),
					new SessionBasedEmailToMimeMessageConverter(session, operationalConfig, emailGovernance, lockedSmtpConfiguration));
			this.proxyServer = createProxy(proxyConfig, operationalConfig);
			finishSendingSessionConfiguration(lockedSmtpConfiguration, ownsSession);
			allowance.poolRegistrationBegun = true;
			initCluster(session, operationalConfig, sendingLimits);
			if (preparedPlan == null) { allowance.attempt.commit(); }
		} catch (RuntimeException | Error failure) {
			if (preparedPlan == null) { allowance.attempt.rollback(failure); }
			throw failure;
		}
	}

	/** Owns provisional allowance rules and construction effects until the complete route succeeds. */
	private static final class ConstructionAttempt {
		private final Map<ValidatedMailerPlan, ConstructionAllowance> nodes = new java.util.IdentityHashMap<>();
		private final List<ConstructionAllowance> allowances = new ArrayList<>();
		private boolean finished;

		private static ConstructionAttempt reserve(final ValidatedMailerPlan plan) {
			final ConstructionAttempt attempt = new ConstructionAttempt();
			try { attempt.reserveNode(plan); return attempt; }
			catch (RuntimeException | Error failure) { attempt.rollback(failure); throw failure; }
		}

		private void reserveNode(final ValidatedMailerPlan plan) {
			final CapturedMailerInputs inputs = plan.getConfiguration().getInputs();
			final MailerOperationalSettings settings = plan.getConfiguration().getOperational();
			final ConstructionAllowance allowance = new ConstructionAllowance(this, inputs.getSendingLimits().reserve(
					inputs.getDefinition().name == null ? null : inputs.getDefinition().privateAllowance,
					settings.getRateLimitGroup(), settings.getMessageRateLimit(), settings.getRecipientRateLimit(), settings.isRateLimitBurstsAllowed()));
			allowances.add(allowance);
			nodes.put(plan, allowance);
			if (plan.getNamedChild() != null) { reserveNode(plan.getNamedChild()); }
		}

		private void commit() {
			if (finished) { return; }
			for (ConstructionAllowance allowance : allowances) { allowance.reservation.commit(); allowance.applied = null; }
			finished = true;
		}

		private void rollback(final Throwable failure) {
			if (finished) { return; }
			finished = true;
			boolean resourcesRetired = true;
			for (ConstructionAllowance allowance : allowances) {
				allowance.resourcesRetired = allowance.retireResources(failure);
				resourcesRetired &= allowance.resourcesRetired;
			}
			for (ConstructionAllowance allowance : allowances) {
				try {
					if (resourcesRetired && allowance.mailer != null) {
						if (allowance.applied == null || allowance.applied.restore(failure)) {
							allowance.mailer.releaseSessionOwnership(allowance.ownsSession);
						}
					}
				} catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
				try { if (allowance.resourcesRetired) { allowance.reservation.abort(); } }
				catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
			}
		}
	}

	private static final class ConstructionAllowance {
		private final ConstructionAttempt attempt;
		private final FactorySendingLimits.Reservation reservation;
		@Nullable private ExecutorService ownedExecutor;
		@Nullable private MailSendObserverNotifier notifier;
		@Nullable private MailerImpl mailer;
		@Nullable private SessionConfigurationChanges.Applied applied;
		private boolean ownsSession;
		private boolean poolRegistrationBegun;
		private boolean resourcesRetired;

		private ConstructionAllowance(final ConstructionAttempt attempt, final FactorySendingLimits.Reservation reservation) {
			this.attempt = attempt;
			this.reservation = reservation;
		}

		private static ConstructionAllowance standalone(final OperationalConfig settings) {
			final ConstructionAttempt attempt = new ConstructionAttempt();
			final ConstructionAllowance allowance = new ConstructionAllowance(attempt, new FactorySendingLimits().reserve(null,
					settings.getRateLimitGroup(), settings.getMessageRateLimit(), settings.getRecipientRateLimit(), settings.isRateLimitBurstsAllowed()));
			if (!settings.isExecutorServiceIsUserProvided()) { allowance.ownedExecutor = settings.getExecutorService(); }
			attempt.allowances.add(allowance);
			return allowance;
		}

		private boolean retireResources(final Throwable failure) {
			if (mailer != null) { return mailer.retireConstructionResources(poolRegistrationBegun, failure); }
			boolean retired = true;
			try { if (ownedExecutor != null) { ownedExecutor.shutdown(); } }
			catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
			try { if (notifier != null) { notifier.closeProgress(); } }
			catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
			return retired;
		}
	}

	private static void suppress(final Throwable primary, final Throwable cleanup) {
		if (primary != cleanup) { primary.addSuppressed(cleanup); }
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
	private void releaseSessionOwnership() { releaseSessionOwnership(true); }

	private void releaseSessionOwnership(final boolean retireConverter) {
		final Properties properties = session.getProperties();
		synchronized (properties) {
			if (properties.get(SESSION_OWNER) == sessionOwner) {
				if (retireConverter) { SessionBasedEmailToMimeMessageConverter.unprimeSession(session); }
				properties.remove(SESSION_OWNER);
			}
		}
	}

	private void finishSendingSessionConfiguration(final LockedSmtpConfiguration lockedSmtpConfiguration, final boolean ownsSession) {
		if (operationalConfig.getCustomMailer() == null) {
			lockedSmtpConfiguration.verifySelected(lockedSmtpConfiguration, session);
		}
		if (ownsSession && operationalConfig.getCustomMailer() == null) {
			try {
				if (proxyConfig.requiresProxy() && !proxyConfig.requiresAuthentication()) {
					MailTransportLifecycleResolver.configureOwnedSession(session, InetSocketAddress.createUnresolved(
							verifyNonnull(proxyConfig.getRemoteProxyHost()), verifyNonnull(proxyConfig.getRemoteProxyPort())));
				} else {
					MailTransportLifecycleResolver.configureOwnedSession(session);
				}
				// Provider initialization must not silently replace a value supplied by a configuration lock.
				lockedSmtpConfiguration.verifySelected(lockedSmtpConfiguration, session);
			} catch (MessagingException failure) {
				throw new MailerException("Couldn't set up the SMTP transport while building the Mailer. "
						+ "Check the cause and your Jakarta Mail provider dependencies.", failure);
			}
		}
	}

	/** Resource retirement precedes restoration and release for every node of the failed owned route. */
	private boolean retireConstructionResources(final boolean poolRegistrationBegun, final Throwable failure) {
		boolean retired = true;
		try { if (!operationalConfig.isExecutorServiceIsUserProvided()) { operationalConfig.getExecutorService().shutdown(); } }
		catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
		try { mailSendOperations.shutdown().get(); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); suppress(failure, interrupted); retired = false; }
		catch (ExecutionException | RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
		try { mailSendObserverNotifier.closeProgress(); }
		catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
		try { if (poolRegistrationBegun) { closeConnectionPools().get(); } }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); suppress(failure, interrupted); retired = false; }
		catch (ExecutionException | RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
		try { if (proxyServer != null) { proxyServer.stop(); } }
		catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); retired = false; }
		return retired;
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
		// Only direct entries are applied. Angus accepts Boolean true or the untrimmed, case-insensitive string "true".
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
		final Properties properties = mailSessionProperties(serverConfig, transportStrategy, true);
		OAuth2AccessTokenResolver.validateConfiguration(properties, new Properties(), transportStrategy, null);
		return createOwnedSession(serverConfig, transportStrategy, properties);
	}

	@NotNull
    static Properties mailSessionProperties(@Nullable final ServerConfig serverConfig, @NotNull final TransportStrategy transportStrategy,
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
				} else {
					props.put(TransportStrategy.OAUTH2_TOKEN_PROPERTY, serverConfig.getPassword());
				}
			}
		}
		return props;
	}

	/** Construct only after validation, with final constructor-consumed values and quiet debug bootstrap. */
	private static Session createOwnedSession(@Nullable final ServerConfig serverConfig, final TransportStrategy strategy,
			final Properties properties) {
		final boolean directDebug = properties.containsKey("mail.debug");
		final Object rawDebug = properties.get("mail.debug");
		properties.setProperty("mail.debug", "false");
		try {
			return Session.getInstance(properties, serverConfig != null && serverConfig.getPassword() != null
					&& !OAuth2AccessTokenResolver.isOAuth2Transport(strategy) ? new SmtpAuthenticator(serverConfig) : null);
		} finally {
			if (directDebug) { properties.put("mail.debug", rawDebug); }
			else { properties.remove("mail.debug"); }
		}
	}

	/** Allocate the authenticated bridge without starting it; initial routing is declared by the validated Session changes. */
	@Nullable
	private static AnonymousSocks5Server createProxy(final ProxyConfig proxy, final OperationalConfig operational) {
		return operational.getCustomMailer() == null && proxy.requiresProxy() && proxy.requiresAuthentication()
				? ModuleLoader.loadAuthenticatedSocksModule().createAnonymousSocks5Server(proxy) : null;
	}

	private void initCluster(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig, final SendingAllowance allowance) {
		if (operationalConfig.getCustomMailer() == null && ModuleLoader.batchModuleAvailable()) {
			ModuleLoader.loadBatchModule().registerToCluster(operationalConfig, operationalConfig.getClusterKey(), session,
					allowance, connectionObserver, OAuth2AccessTokenResolver.captureAccessTokenProvider(session));
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

	RuntimeException retainProxyFailureReceipt(final RuntimeException failure, final MailSubmissionReceipt receipt) {
		return proxyServer == null ? failure : proxyServer.retainSubmissionReceipt(failure, receipt);
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
		if (serverConfig != null) { return serverConfig.getHost(); }
		final String protocol = session.getProperty("mail.transport.protocol");
		return protocol == null ? null : session.getProperty("mail." + protocol + ".host");
	}

	@Nullable
	Integer logicalPort() {
		if (serverConfig != null) {
			return serverConfig.getPort();
		}
		final String protocol = session.getProperty("mail.transport.protocol");
		if (protocol == null) { return null; }
		final String propertyName = "mail." + protocol + ".port";
		final Integer port = ConnectionPropertyValues.integerValue(
				ConnectionPropertyValues.visibleValue(session.getProperties(), propertyName));
		return port != null && port >= 1 && port <= 65535 ? port : null;
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
		return EmailPreparation.rehearse(verifyNonnull(email), rehearsalSessionContext.capture(), emailGovernance,
				operationalConfig.isClientValidationWarningsOnly(), operationalConfig.isLegacySmtpContentSupportEnabled(),
				processSecurityAndValidateSize);
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
			Error captureFailure = null;
			try {
				rehearsalSessionContext.freezeForShutdown();
			} catch (Error failure) {
				captureFailure = failure;
			}
			try {
				mailSendOperations.shutdown();
				shutdownFuture = CompletableFuture.runAsync(this::drainExecutorAndCloseConnectionPools, MailerImpl::startShutdownThread);
			} catch (RuntimeException | Error initiationFailure) {
				if (captureFailure == null) { throw initiationFailure; }
				if (initiationFailure != captureFailure) { captureFailure.addSuppressed(initiationFailure); }
			}
			if (captureFailure != null) { throw captureFailure; }
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
