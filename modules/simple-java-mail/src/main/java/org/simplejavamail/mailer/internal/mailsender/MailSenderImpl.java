package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.mailer.internal.MailerGenericBuilderImpl;
import org.simplejavamail.mailer.internal.socks.common.Socks5Bridge;
import org.simplejavamail.mailer.internal.socks.socks5server.AnonymousSocks5Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
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
public class MailSenderImpl implements MailSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSenderImpl.class);

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
	private final AnonymousSocks5Server proxyServer;

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
	
	public MailSenderImpl(@Nonnull final Session session,
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
	@Nullable
	private static AnonymousSocks5Server configureSessionWithProxy(@Nonnull final ProxyConfig proxyConfig,
																   @Nonnull final Session session,
																   @Nullable final TransportStrategy transportStrategy) {
		if (!proxyConfig.requiresProxy()) {
			LOGGER.trace("No proxy set, skipping proxy.");
		} else {
			if (transportStrategy == TransportStrategy.SMTPS) {
				throw new MailSenderException(MailSenderException.INVALID_PROXY_SLL_COMBINATION);
			}
			final Properties sessionProperties = session.getProperties();
			if (transportStrategy != null) {
				sessionProperties.put(transportStrategy.propertyNameSocksHost(), assumeNonNull(proxyConfig.getRemoteProxyHost()));
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
				AuthenticatedSocksModule asm = ModuleLoader.loadAuthenticatedSocksModule();
				ProxyConfig socksProxyConfig = asm.createProxyConfig(proxyConfig.getRemoteProxyHost(), proxyConfig.getRemoteProxyPort(),
						proxyConfig.getUsername(), proxyConfig.getPassword(), proxyConfig.getProxyBridgePort());
				Socks5Bridge socks5Bridge = asm.createAuthenticatingSocks5Bridge(socksProxyConfig);
				return asm.createAnonymousSocks5ServerImpl(socks5Bridge, proxyConfig.getProxyBridgePort());
			}
		}
		return null;
	}

	@Override
	@Nullable
	public final synchronized AsyncResponse send(final Email email, final boolean async) {
		/*
            we need to track even non-async emails to prevent async emails from shutting down
            the proxy bridge server (or connection pool in async mode) while a non-async email is still being processed
		 */
        // phaser auto-terminates each time the all parties have arrived, so re-initialize when needed
        if (smtpRequestsPhaser == null || smtpRequestsPhaser.isTerminated()) {
            smtpRequestsPhaser = new Phaser();
        }
        smtpRequestsPhaser.register();
		
		SendMailClosure sendMailClosure = new SendMailClosure(session, email, async);
		
		if (!async) {
			sendMailClosure.run();
			return null;
		} else {
			// start up thread pool if necessary
			if (executor == null || executor.isTerminated()) {
				executor = Executors.newFixedThreadPool(operationalConfig.getThreadPoolSize());
			}
			configureSessionWithTimeout(session, operationalConfig.getSessionTimeout());
			
			return AsyncOperationHelper.executeAsync(executor, "sendMail process", sendMailClosure);
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
	 */
	// used to be a method with simple parameters! would still have been, if Java 7 supported lambda's :(
	private class SendMailClosure implements Runnable {
		
		@Nonnull final Session session;
		@Nonnull final Email email;
		private final boolean async;
		
		/**
		 * @param session The session with which to produce the {@link MimeMessage} aquire the {@link Transport} for connections.
		 * @param email   The email that will be converted into a {@link MimeMessage}.
		 * @param async   For logging purposes, indicates whether this closure is running in async mode.
		 */
		private SendMailClosure(@Nonnull Session session, @Nonnull Email email, boolean async) {
			this.session = session;
			this.email = email;
			this.async = async;
		}
		
		@SuppressWarnings("deprecation")
		@Override
		public void run() {
			LOGGER.trace("sending email...");
			try {
				// fill and send wrapped mime message parts
				final MimeMessage message = MimeMessageProducerHelper.produceMimeMessage(
						checkNonEmptyArgument(email, "email"),
						checkNonEmptyArgument(session, "session"));
				
				configureBounceToAddress(session, email);
				
				logSession(session, async, "mail");
				message.saveChanges(); // some headers and id's will be set for this specific message
				email.internalSetId(message.getMessageID());
				
				try {
					synchronized (this) {
						if (needsAuthenticatedProxy()) {
							assert proxyServer != null; // actually superfluous, but otherwise IntelliJ won't shut up
							if (!proxyServer.isRunning()) {
								LOGGER.trace("starting proxy bridge");
								proxyServer.start();
							}
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
			LOGGER.trace("...email sent");
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
			//noinspection ConstantConditions
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
	private static void logSession(final Session session, boolean async, final String activity) {
		final TransportStrategy transportStrategy = TransportStrategy.findStrategyForSession(session);
		final Properties properties = session.getProperties();
		final String sessionDetails = (transportStrategy != null) ? transportStrategy.toString(properties) : properties.toString();
		LOGGER.debug("starting{} {} with {}", async ? " async" : "", activity, sessionDetails);
	}

	/**
	 * @see MailerGenericBuilderImpl#trustingAllHosts(boolean)
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
	 * @see MailerGenericBuilderImpl#trustingSSLHosts(String...)
	 */
	private void trustHosts(@Nonnull final List<String>  hosts) {
		trustAllHosts(false);
		if (!hosts.isEmpty()) {
			if (transportStrategy == null) {
				throw new MailSenderException(MailSenderException.CANNOT_SET_TRUST_WITHOUT_TRANSPORTSTRATEGY);
			}
			final StringBuilder builder = new StringBuilder(getFirst(hosts));
			for (int i = 1; i < hosts.size(); i++) {
				builder.append(" ").append(hosts.get(i));
			}
			session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), builder.toString());
		}
	}
	
	@Override
	@Nullable
	public synchronized AsyncResponse testConnection(boolean async) {
		TestConnectionClosure testConnectionClosure = new TestConnectionClosure(session, async);
		if (!async) {
			testConnectionClosure.run();
			return null;
		} else {
			return AsyncOperationHelper.executeAsync("testSMTPConnection process", testConnectionClosure);
		}
	}
	
	/**
	 * Extra closure for the actual connection test, so this can be called regularly as well as from async thread.
	 * <p>
	 * See {@link #testConnection(boolean)} for details.
	 */
	// used to be a method! would still have been, if Java 7 supported lambda's :(
	private class TestConnectionClosure implements Runnable {
		@Nonnull final Session session;
		private final boolean async;
		
		private TestConnectionClosure(@Nonnull Session session, boolean async) {
			this.async = async;
			this.session = session;
		}
		
		@Override
		public void run() {
			LOGGER.debug("testing connection...");
			
			boolean proxyBridgeStartedForTestingConnection = false;
			
			configureSessionWithTimeout(session, operationalConfig.getSessionTimeout());
			
			logSession(session, async, "connection test");
			
			try (Transport transport = session.getTransport()) {
				//noinspection ConstantConditions
				if (needsAuthenticatedProxy() && !proxyServer.isRunning()) {
					LOGGER.trace("starting proxy bridge for testing connection");
					proxyServer.start();
					proxyBridgeStartedForTestingConnection = true;
				}
				transport.connect(); // actual test
				
				LOGGER.debug("...connection succesful");
			} catch (final MessagingException e) {
				throw new MailSenderException(MailSenderException.ERROR_CONNECTING_SMTP_SERVER, e);
			} finally {
				if (proxyBridgeStartedForTestingConnection) {
					LOGGER.trace("stopping proxy bridge after connection test");
					proxyServer.stop();
				}
			}
		}
	}
	
	/**
	 * Proxy server is null when not needed. Method is for readability.
	 */
	private boolean needsAuthenticatedProxy() {
		return proxyServer != null;
	}
	
	@Override
	public Session getSession() {
		return session;
	}
	
	@Override
	public OperationalConfig getOperationalConfig() {
		return operationalConfig;
	}
	
}