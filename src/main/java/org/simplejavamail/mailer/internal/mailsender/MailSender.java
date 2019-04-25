package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.MailException;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.mailer.MailerGenericBuilder;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.socks.AuthenticatingSocks5Bridge;
import org.simplejavamail.mailer.internal.socks.SocksProxyConfig;
import org.simplejavamail.mailer.internal.socks.socks5server.AnonymousSocks5Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static java.lang.String.format;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEML;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Class that performs the actual javax.mail SMTP integration.
 * <p>
 * Refer to {@link #send(Email, boolean)} for details.
 * <p>
 * <hr>
 * On a technical note, this is the most complex class in the library (aside from the SOCKS5 bridging server), because it deals with optional
 * asynchronous mailing requests and an optional proxy server that needs to be started and stopped on the fly depending on how many emails are (still)
 * being sent. Especially the combination of asynchronous emails and synchronous emails needs to be managed properly.
 */
public class MailSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSender.class);

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly.
	 */
	private final Session session;
	
	/**
	 * Depending on the transport strategy, these properties are different, that's why we need to keep a global hold on this instance.
	 * <p>
	 * <strong>NOTE:</strong><br>
	 * This is an optional parameter and as such some functions will throw an error when used (such as {@link #trustAllHosts(boolean)}) or
	 * will skip setting optional properties (such as default timeouts) and also skip mandatory properties which are assumed to be preconfigured on
	 * the Session instance (these will be logged on DEBUG level, such as proxy host and port properties).
	 */
	@Nullable
	private final TransportStrategy transportStrategy;
	
	/**
	 * @see OperationalConfig
	 */
	private final OperationalConfig operationalConfig;
	
	/**
	 * Intermediary SOCKS5 relay server that acts as bridge between JavaMail and remote proxy (since JavaMail only supports anonymous SOCKS proxies).
	 * Only set when {@link ProxyConfig} is provided with authentication details.
	 */
	@Nullable
	private AnonymousSocks5Server proxyServer;

	/**
	 * Allows us to manage how many thread we run at the same time using a thread pool.
	 * <p>
	 * Can't be initialized in the field, because we need to reinitialize it whenever the threadpool was closed after a batch of emails and this
	 * MailSender instance is again engaged.
	 */
	private ExecutorService executor;

	/**
	 * Used to keep track of running SMTP requests, so that we know when to close down the proxy bridging server (if used).
	 * <p>
	 * Can't be initialized in the field, because we need to reinitialize if the phaser was terminated after a batch of emails and this MailSender
	 * instance is again engaged.
	 */
	private Phaser smtpRequestsPhaser;
	
	public MailSender(@Nonnull final Session session,
					  @Nonnull final OperationalConfig operationalConfig,
					  @Nonnull final ProxyConfig proxyConfig,
					  @Nullable final TransportStrategy transportStrategy) {
		this.session = session;
		this.operationalConfig = operationalConfig;
		this.transportStrategy = transportStrategy;
		this.proxyServer = configureSessionWithProxy(proxyConfig, session, transportStrategy);
		init(operationalConfig);
	}
	
	private void init(@Nonnull OperationalConfig operationalConfig) {
		session.setDebug(operationalConfig.isDebugLogging());
		session.getProperties().putAll(operationalConfig.getProperties());
		if (transportStrategy != null) {
			if (operationalConfig.isTrustAllSSLHost()) {
				trustAllHosts(true);
			} else {
				trustHosts(operationalConfig.getSslHostsToTrust());
			}
		}
	}
	
	/**
	 * If a {@link ProxyConfig} was provided with a host address, then the appropriate properties are set on the {@link Session}, overriding any SOCKS
	 * properties already there.
	 * <p>
	 * These properties are <em>"mail.smtp(s).socks.host"</em> and <em>"mail.smtp(s).socks.port"</em>, which are set to "localhost" and {@link
	 * ProxyConfig#getProxyBridgePort()}.
	 *
	 * @param proxyConfig       Proxy server details, optionally with username / password.
	 * @param session           The session with properties to add the new configuration to.
	 * @param transportStrategy Used to verify if the current combination with proxy is allowed (SMTP with SSL trategy doesn't support any proxy,
	 *                          virtue of the underlying JavaMail framework). Can be omitted if the Session is presumed preconfigured.
	 * @return null in case of no proxy or anonymous proxy, or a AnonymousSocks5Server proxy bridging server instance in case of authenticated proxy.
	 */
	private static AnonymousSocks5Server configureSessionWithProxy(@Nonnull final ProxyConfig proxyConfig,
																   @Nonnull final Session session,
																   @Nullable final TransportStrategy transportStrategy) {
		if (!proxyConfig.requiresProxy()) {
			LOGGER.debug("No proxy set, skipping proxy.");
		} else {
			if (transportStrategy == TransportStrategy.SMTPS) {
				throw new MailSenderException(MailSenderException.INVALID_PROXY_SLL_COMBINATION);
			}
			final Properties sessionProperties = session.getProperties();
			if (transportStrategy != null) {
				sessionProperties.put(transportStrategy.propertyNameSocksHost(), proxyConfig.getRemoteProxyHost());
				sessionProperties.put(transportStrategy.propertyNameSocksPort(), String.valueOf(proxyConfig.getRemoteProxyPort()));
			} else {
				LOGGER.debug("no transport strategy provided, expecting mail.smtp(s).socks.host and .port properties to be set to proxy " +
						"config on Session");
			}
			if (proxyConfig.requiresAuthentication()) {
				if (transportStrategy != null) {
					// wire anonymous proxy request to our own proxy bridge so we can perform authentication to the actual proxy
					sessionProperties.put(transportStrategy.propertyNameSocksHost(), "localhost");
					sessionProperties.put(transportStrategy.propertyNameSocksPort(), String.valueOf(proxyConfig.getProxyBridgePort()));
				} else {
					LOGGER.debug("no transport strategy provided but authenticated proxy required, expecting mail.smtp(s).socks.host and .port " +
							"properties to be set to localhost and port " + proxyConfig.getProxyBridgePort());
				}
				SocksProxyConfig socksProxyConfig = new SocksProxyConfig(proxyConfig.getRemoteProxyHost(), proxyConfig.getRemoteProxyPort(),
						proxyConfig.getUsername(), proxyConfig.getPassword(), proxyConfig.getProxyBridgePort());
				return new AnonymousSocks5Server(new AuthenticatingSocks5Bridge(socksProxyConfig), proxyConfig.getProxyBridgePort());
			}
		}
		return null;
	}

	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}.
	 * <p>
	 * Sends the Sun JavaMail {@link Message} object using {@link Session#getTransport()}. It will call {@link Transport#connect()} assuming all
	 * connection details have been configured in the provided {@link Session} instance and finally {@link Transport#sendMessage(Message,
	 * javax.mail.Address[])}.
	 * <p>
	 * Performs a call to {@link Message#saveChanges()} as the Sun JavaMail API indicates it is needed to configure the message headers and providing
	 * a message id.
	 * <p>
	 * If the email should be sent asynchrounously - perhaps as part of a batch, then a new thread is started using the {@link #executor} for
	 * threadpooling.
	 * <p>
	 * If the email should go through an authenticated proxy server, then the SOCKS proxy bridge is started if not already running. When the last
	 * email in a batch has finished, the proxy bridging server is shut down.
	 *
	 * @param email The information for the email to be sent.
	 * @param async If false, this method blocks until the mail has been processed completely by the SMTP server. If true, a new thread is started to
	 *              send the email and this method returns immediately.
	 * @throws MailException Can be thrown if an email isn't validating correctly, or some other problem occurs during connection, sending etc.
	 * @see Executors#newFixedThreadPool(int)
	 */
	public final synchronized void send(final Email email, final boolean async) {
		/*
            we need to track even non-async emails to prevent async emails from shutting down
            the proxy bridge server (or connection pool in async mode) while a non-async email is still being processed
		 */
        // phaser auto-terminates each time the all parties have arrived, so re-initialize when needed
        if (smtpRequestsPhaser == null || smtpRequestsPhaser.isTerminated()) {
            smtpRequestsPhaser = new Phaser();
        }
        smtpRequestsPhaser.register();
		if (async) {
			// start up thread pool if necessary
			if (executor == null || executor.isShutdown()) {
				executor = Executors.newFixedThreadPool(operationalConfig.getThreadPoolSize(),
						new NamedThreadFactory("Simple Java Mail async mail sender"));
			}
			configureSessionWithTimeout(session, operationalConfig.getSessionTimeout());
			executor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						sendMailClosure(session, email);
					} catch (Exception e) {
						LOGGER.error("Failed to send email", e);
					}
				}

				@Override
				public String toString() {
					return "sendMail process";
				}
			});
		} else {
			sendMailClosure(session, email);
		}
	}
	
	/**
	 * Configures the {@link Session} with the same timeout for socket connection timeout, read and write timeout.
	 */
	private void configureSessionWithTimeout(final Session session, final int sessionTimeout) {
		if (transportStrategy != null) {
			// socket timeouts handling
			final Properties sessionProperties = session.getProperties();
			sessionProperties.put(transportStrategy.propertyNameConnectionTimeout(), String.valueOf(sessionTimeout));
			sessionProperties.put(transportStrategy.propertyNameTimeout(), String.valueOf(sessionTimeout));
			sessionProperties.put(transportStrategy.propertyNameWriteTimeout(), String.valueOf(sessionTimeout));
		} else {
			LOGGER.debug("No transport strategy provided, skipping defaults for .connectiontimout, .timout and .writetimeout");
		}
	}
	
	/**
	 * Separate closure that can be executed directly or from a thread. Refer to {@link #send(Email, boolean)} for details.
	 *
	 * @param session The session with which to produce the {@link MimeMessage} aquire the {@link Transport} for connections.
	 * @param email   The email that will be converted into a {@link MimeMessage}.
	 */
	private void sendMailClosure(@Nonnull final Session session, @Nonnull final Email email) {
		LOGGER.trace("sending email...");
		try {
			// fill and send wrapped mime message parts
			final MimeMessage message = MimeMessageHelper.produceMimeMessage(
					checkNonEmptyArgument(email, "email"),
					checkNonEmptyArgument(session, "session"));
			
			configureBounceToAddress(session, email);
			
			logSession(session, "mail");
			message.saveChanges(); // some headers and id's will be set for this specific message
			//noinspection deprecation
			email.internalSetId(message.getMessageID());

			try {
				synchronized (this) {
					if (needsAuthenticatedProxy() && !proxyServer.isRunning()) {
						LOGGER.trace("starting proxy bridge");
						proxyServer.start();
					}
				}

				if (!operationalConfig.isTransportModeLoggingOnly()) {
					LOGGER.trace("\t\nEmail: {}", email);
					LOGGER.trace("\t\nMimeMessage: {}\n", mimeMessageToEML(message));

					try (Transport transport = session.getTransport()) {
						transport.connect();
						transport.sendMessage(message, message.getAllRecipients());
					} finally {
						LOGGER.trace("closing transport");
					}
				} else {
					LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual sending...");
					LOGGER.info("\n\nEmail: {}\n", email);
					LOGGER.info("\n\nMimeMessage: {}\n", mimeMessageToEML(message));
				}
			} finally {
				checkShutDownRunningProcesses();
			}
		} catch (final UnsupportedEncodingException e) {
			LOGGER.error("Failed to send email:\n{}", email);
			throw new MailSenderException(MailSenderException.INVALID_ENCODING, e);
		} catch (final MessagingException e) {
			LOGGER.error("Failed to send email:\n{}", email);
			throw new MailSenderException(MailSenderException.GENERIC_ERROR, e);
		} catch (final Exception e) {
			LOGGER.error("Failed to send email:\n{}", email);
			throw e;
		}
	}
	
	private void configureBounceToAddress(final Session session, final Email email) {
		final Recipient bounceAddress = email.getBounceToRecipient();
		if (bounceAddress != null) {
			if (transportStrategy != null) {
				final String formattedRecipient = format("%s <%s>", bounceAddress.getName(), bounceAddress.getAddress());
				session.getProperties().setProperty(transportStrategy.propertyNameEnvelopeFrom(), formattedRecipient);
			} else {
				throw new MailSenderException(MailSenderException.CANNOT_SET_BOUNCETO_WITHOUT_TRANSPORTSTRATEGY);
			}
		}
	}
	
	/**
	 * We need to keep a count of running threads in case a proxyserver is running or a connection pool needs to be shut down.
     */
    private synchronized void checkShutDownRunningProcesses() {
        smtpRequestsPhaser.arriveAndDeregister();
        LOGGER.trace("SMTP request threads left: {}", smtpRequestsPhaser.getUnarrivedParties());
        // if this thread is the last one finishing
        if (smtpRequestsPhaser.getUnarrivedParties() == 0) {
            LOGGER.trace("all threads have finished processing");
			if (needsAuthenticatedProxy() && proxyServer.isRunning() && !proxyServer.isStopping()) {
                LOGGER.trace("stopping proxy bridge...");
                proxyServer.stop();
            }
            // shutdown the threadpool, or else the Mailer will keep any JVM alive forever
            // executor is only available in async mode
            if (executor != null) {
                executor.shutdown();
            }
        }
    }

	/**
	 * Simply logs host details, credentials used and whether authentication will take place and finally the transport protocol used.
	 */
	private static void logSession(final Session session, final String activity) {
		final TransportStrategy transportStrategy = TransportStrategy.findStrategyForSession(session);
		final Properties properties = session.getProperties();
		final String sessionDetails = (transportStrategy != null) ? transportStrategy.toString(properties) : properties.toString();
		LOGGER.debug("starting {} with {}", activity, sessionDetails);
	}

	/**
	 * @see MailerGenericBuilder#trustingAllHosts(Boolean)
	 */
	private void trustAllHosts(final boolean trustAllHosts) {
		if (transportStrategy != null) {
			session.getProperties().remove(transportStrategy.propertyNameSSLTrust());
			if (trustAllHosts) {
				session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), "*");
			}
		} else {
			throw new MailSenderException(MailSenderException.CANNOT_SET_TRUST_WITHOUT_TRANSPORTSTRATEGY);
		}
	}

	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	private void trustHosts(@Nonnull final List<String>  hosts) {
		trustAllHosts(false);
		if (!hosts.isEmpty()) {
			if (transportStrategy == null) {
				throw new MailSenderException(MailSenderException.CANNOT_SET_TRUST_WITHOUT_TRANSPORTSTRATEGY);
			}
			final StringBuilder builder = new StringBuilder(hosts.get(0));
			for (int i = 1; i < hosts.size(); i++) {
				builder.append(" ").append(hosts.get(i));
			}
			session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), builder.toString());
		}
	}
	
	/**
	 * Tries to connect to the configured SMTP server, including (authenticated) proxy if set up.
	 * <p>
	 * Note: synchronizes on this mailer instance, so that we don't get into race condition conflicts with emails actually being sent.
	 */
	public synchronized void testConnection() {
		boolean proxyBridgeStartedForTestingConnection = false;

		configureSessionWithTimeout(session, operationalConfig.getSessionTimeout());

		logSession(session, "connection test");

		try (Transport transport = session.getTransport()) {
			if (needsAuthenticatedProxy() && !proxyServer.isRunning()) {
				LOGGER.trace("starting proxy bridge for testing connection");
				proxyServer.start();
				proxyBridgeStartedForTestingConnection = true;
			}
			transport.connect(); // actual test
		} catch (final MessagingException e) {
			throw new MailSenderException(MailSenderException.ERROR_CONNECTING_SMTP_SERVER, e);
		} finally {
			if (proxyBridgeStartedForTestingConnection) {
				LOGGER.trace("stopping proxy bridge after connection test");
				proxyServer.stop();
			}
		}
	}
	
	/**
	 * Proxy server is null when not needed. Method is for readability.
	 */
	private boolean needsAuthenticatedProxy() {
		return proxyServer != null;
	}
	
	/**
	 * For emergencies, when a client really wants access to the internally created {@link Session} instance.
	 */
	public Session getSession() {
		return session;
	}
	
	public OperationalConfig getOperationalConfig() {
		return operationalConfig;
	}
}