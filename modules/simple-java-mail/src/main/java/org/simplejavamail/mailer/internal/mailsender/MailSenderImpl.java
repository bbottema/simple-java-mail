package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.mailer.internal.MailerGenericBuilderImpl;
import org.simplejavamail.mailer.internal.mailsender.concurrent.NonJvmBlockingThreadPoolExecutor;
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
import java.util.concurrent.Phaser;

import static java.lang.String.format;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEML;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

/**
 * Class that performs the actual javax.mail SMTP integration.
 * <p>
 * Refer to {@link #send(Email, boolean)} for details.
 * <p>
 * <hr>
 * On a technical note, this is the most complex class in the library (aside from the SOCKS5 bridging server and S/MIME module), because it deals with optional
 * asynchronous mailing requests and an optional proxy server that needs to be started and stopped on the fly depending on how many emails are (still)
 * being sent. Especially the combination of asynchronous emails and synchronous emails needs to be managed properly.
 *
 * @see org.simplejavamail.converter.internal.mimemessage.MimeMessageProducer
 */
public class MailSenderImpl implements MailSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSenderImpl.class);

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly.
	 */
	@Nonnull
	private final Session session;
	
	/**
	 * Depending on the transport strategy, these properties are different, that's why we need to keep a global hold on this instance.
	 * <p>
	 * <strong>NOTE:</strong><br>
	 * This is an optional parameter and as such some functions will throw an error when used (such as {@link #trustAllHosts(Session, boolean, TransportStrategy)}) or
	 * will skip setting optional properties (such as default timeouts) and also skip mandatory properties which are assumed to be preconfigured on
	 * the Session instance (these will be logged on DEBUG level, such as proxy host and port properties).
	 */
	@Nullable
	private final TransportStrategy transportStrategy;
	
	/**
	 * @see OperationalConfig
	 */
	@Nonnull
	private final OperationalConfig operationalConfig;
	
	/**
	 * Intermediary SOCKS5 relay server that acts as bridge between JavaMail and remote proxy (since JavaMail only supports anonymous SOCKS proxies).
	 * Only set when {@link ProxyConfig} is provided with authentication details.
	 */
	@Nullable
	private final AnonymousSocks5Server proxyServer;

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
		initSession(session, operationalConfig, transportStrategy);

		this.session = session;
		this.operationalConfig = operationalConfig;
		this.transportStrategy = transportStrategy;
		this.proxyServer = configureSessionWithProxy(proxyConfig, session, transportStrategy);
	}
	
	private void initSession(@Nonnull final Session session, @Nonnull OperationalConfig operationalConfig, @Nullable final TransportStrategy transportStrategy) {
		session.setDebug(operationalConfig.isDebugLogging());
		session.getProperties().putAll(operationalConfig.getProperties());

		configureSessionWithTimeout(session, operationalConfig.getSessionTimeout(), transportStrategy);

		if (transportStrategy != null) {
			if (operationalConfig.isTrustAllSSLHost()) {
				trustAllHosts(session, true, transportStrategy);
			} else {
				trustHosts(session, operationalConfig.getSslHostsToTrust(), transportStrategy);
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
				return ModuleLoader.loadAuthenticatedSocksModule().createAnonymousSocks5Server(proxyConfig);
			}
		}
		return null;
	}

	/**
	 * @see MailSender#send(Email, boolean)
	 * @see NonJvmBlockingThreadPoolExecutor
	 */
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
			return AsyncOperationHelper.executeAsync(operationalConfig.getExecutorService(), "sendMail process", sendMailClosure);
		}
	}
	
	/**
	 * Configures the {@link Session} with the same timeout for socket connection timeout, read and write timeout.
	 */
	private void configureSessionWithTimeout(@Nonnull final Session session, final int sessionTimeout, @Nullable final TransportStrategy transportStrategy) {
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
	 * <p>
	 * Note that this Runnable implementation is <strong>not</strong> thread related, it is just to encapsulate the code to
	 * be run directly or from a <em>real</em> Runnable.
	 */
	private class SendMailClosure implements Runnable {

		@Nonnull final Session session;
		@Nonnull final Email email;
		private final boolean asyncForLoggingPurpose;


		/**
		 * @param session The session with which to produce the {@link MimeMessage} aquire the {@link Transport} for connections.
		 * @param email   The email that will be converted into a {@link MimeMessage}.
		 * @param asyncForLoggingPurpose   For logging purposes, indicates whether this closure is running in async mode.
		 */
		private SendMailClosure(@Nonnull Session session, @Nonnull Email email, boolean asyncForLoggingPurpose) {
			this.session = session;
			this.email = email;
			this.asyncForLoggingPurpose = asyncForLoggingPurpose;
		}

		@SuppressWarnings("deprecation")
		@Override
		public void run() {
			LOGGER.trace("sending email...");
			try {
				// fill and send wrapped mime message parts
				final MimeMessage message = MimeMessageProducerHelper.produceMimeMessage(email, session);

				configureBounceToAddress(session, email);

				logSession(session, asyncForLoggingPurpose, "mail");
				message.saveChanges(); // some headers and id's will be set for this specific message
				email.internalSetId(message.getMessageID());

				try {
					synchronized (this) {
						if (needsAuthenticatedProxy()) {
							if (!proxyServer.isRunning()) {
								LOGGER.trace("starting proxy bridge");
								proxyServer.start();
							}
							proxyServer.start();
						}
					}

					if (operationalConfig.isTransportModeLoggingOnly()) {
						LOGGER.info("TRANSPORT_MODE_LOGGING_ONLY: skipping actual sending...");
						LOGGER.info("\n\nEmail: {}\n", email);
						LOGGER.info("\n\nMimeMessage: {}\n", mimeMessageToEML(message));
						return;
					}

					LOGGER.trace("\t\nEmail: {}", email);
					LOGGER.trace("\t\nMimeMessage: {}\n", mimeMessageToEML(message));

					try (Transport transport = session.getTransport()) {
						transport.connect();
						transport.sendMessage(message, message.getAllRecipients());
						LOGGER.trace("...email sent");
					} finally {
						LOGGER.trace("closing transport");
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
	 * We need to keep a count of running threads in case a proxyserver is running
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
	 * @see MailerGenericBuilderImpl#trustingSSLHosts(String...)
	 */
	private void trustHosts(@Nonnull final Session session, @Nonnull final List<String> hosts, @Nonnull final TransportStrategy transportStrategy) {
		trustAllHosts(session, false, transportStrategy);
		if (!hosts.isEmpty()) {
			final StringBuilder builder = new StringBuilder(getFirst(hosts));
			for (int i = 1; i < hosts.size(); i++) {
				builder.append(" ").append(hosts.get(i));
			}
			session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), builder.toString());
		}
	}

	/**
	 * @see MailerGenericBuilderImpl#trustingAllHosts(boolean)
	 */
	private void trustAllHosts(@Nonnull final Session session, final boolean trustAllHosts, @Nonnull final TransportStrategy transportStrategy) {
		session.getProperties().remove(transportStrategy.propertyNameSSLTrust());
		if (trustAllHosts) {
			session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), "*");
		}
	}

	/**
	 * @see MailSender#testConnection(boolean)
	 */
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

			configureSessionWithTimeout(session, operationalConfig.getSessionTimeout(), transportStrategy);

			logSession(session, async, "connection test");

			try (Transport transport = session.getTransport()) {
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

	/**
	 * @see MailSender#getSession()
	 */
	@Override
	@Nonnull
	public Session getSession() {
		return session;
	}

	/**
	 * @see MailSender#getOperationalConfig()
	 */
	@Override
	@Nonnull
	public OperationalConfig getOperationalConfig() {
		return operationalConfig;
	}

}