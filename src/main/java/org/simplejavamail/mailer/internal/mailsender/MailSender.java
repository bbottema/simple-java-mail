package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.MailException;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageHelper;
import org.simplejavamail.email.Email;
import org.simplejavamail.email.Recipient;
import org.simplejavamail.mailer.config.ProxyConfig;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.socks.AuthenticatingSocks5Bridge;
import org.simplejavamail.mailer.internal.socks.socks5server.AnonymousSocks5Server;
import org.simplejavamail.util.ConfigLoader;
import org.simplejavamail.util.ConfigLoader.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
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
 * <hr/>
 * <p>
 * On a technical note, this is the most complex class in the library (aside from the SOCKS5 bridging server), because it deals with optional
 * asynchronous mailing requests and an optional proxy server that needs to be started and stopped on the fly depending on how many emails are (still)
 * being sent. Especially the combination of asynchronous emails and synchronous emails needs to be managed properly.
 */
public class MailSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSender.class);
	
	/**
	 * Defaults to {@code false}, sending mails rather than just only logging the mails.
	 */
	private static final boolean DEFAULT_MODE_LOGGING_ONLY = false;
	
	/**
	 * The default maximum timeout value for the transport socket is {@value #DEFAULT_SESSION_TIMEOUT_MILLIS}
	 * milliseconds. Can be overridden from a config file or through System variable.
	 */
	@SuppressWarnings("JavaDoc")
	private static final int DEFAULT_SESSION_TIMEOUT_MILLIS = 60_000;

	/**
	 * For multi-threaded scenario's where a batch of emails sent asynchronously, the default maximum number of threads is {@value
	 * #DEFAULT_POOL_SIZE}. Can be overridden from a config file or through System variable.
	 *
	 * @see Property#DEFAULT_POOL_SIZE
	 */
	@SuppressWarnings("JavaDoc")
	private static final int DEFAULT_POOL_SIZE = 10;

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly.
	 */
	private final Session session;
	
	/**
	 * The strategy is used initially to configure as much as possible, such as the Session and proxy configuration, however, at the time of
	 * actually sending an email, some more properties need to be set based on the current Email instance.
	 * <p>
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
	 * Intermediary SOCKS5 relay server that acts as bridge between JavaMail and remote proxy (since JavaMail only supports anonymous SOCKS proxies).
	 * Only set when {@link ProxyConfig} is provided with authentication details.
	 */
	@Nullable
	private AnonymousSocks5Server proxyServer = null;

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
	
	/**
	 * The timeout to use when sending emails (affects socket connect-, read- and write timeouts). Defaults to
	 * {@value #DEFAULT_SESSION_TIMEOUT_MILLIS}.
	 */
	private int sessionTimeout;
	
	/**
	 * The number of concurrent threads sending an email each. Used only when sending emails asynchronously (batch job mode). Defaults to
	 * {@value #DEFAULT_POOL_SIZE}.
	 */
	private int threadPoolSize;

	/**
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 */
	private boolean transportModeLoggingOnly;
	
	/**
	 * Configures proxy: {@link #configureSessionWithProxy(ProxyConfig, Session, TransportStrategy)}(ProxyConfig, Session, TransportStrategy)
	 * <p>
	 * Also initializes:
	 * <ul>
	 *     <li>{@link #sessionTimeout} from properties or default {@link #DEFAULT_SESSION_TIMEOUT_MILLIS}</li>
	 *     <li>{@link #threadPoolSize} from properties or default {@link #DEFAULT_POOL_SIZE}</li>
	 *     <li>{@link #transportModeLoggingOnly} from properties or default {@link #DEFAULT_MODE_LOGGING_ONLY}</li>
	 * </ul>
	 */
	public MailSender(final Session session, final ProxyConfig proxyConfig, @Nullable final TransportStrategy transportStrategy) {
		this.session = session;
		this.transportStrategy = transportStrategy;
		this.proxyServer = configureSessionWithProxy(proxyConfig, session, transportStrategy);
		this.threadPoolSize = ConfigLoader.valueOrProperty(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE);
		this.sessionTimeout = ConfigLoader.valueOrProperty(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS);
		this.transportModeLoggingOnly = ConfigLoader.valueOrProperty(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_MODE_LOGGING_ONLY);
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
	 *                          virtue of the underlying JavaMail framework).
	 * @return null in case of no proxy or anonymous proxy, or a AnonymousSocks5Server proxy bridging server instance in case of authenticated proxy.
	 */
	private static AnonymousSocks5Server configureSessionWithProxy(final ProxyConfig proxyConfig, final Session session,
			final TransportStrategy transportStrategy) {
		final ProxyConfig effectiveProxyConfig = (proxyConfig != null) ? proxyConfig : new ProxyConfig();
		if (!effectiveProxyConfig.requiresProxy()) {
			LOGGER.debug("No proxy set, skipping proxy.");
		} else {
			if (transportStrategy == TransportStrategy.SMTPS) {
				throw new MailSenderException(MailSenderException.INVALID_PROXY_SLL_COMBINATION);
			}
			final Properties sessionProperties = session.getProperties();
			if (transportStrategy != null) {
				sessionProperties.put(transportStrategy.propertyNameSocksHost(), effectiveProxyConfig.getRemoteProxyHost());
				sessionProperties.put(transportStrategy.propertyNameSocksPort(), String.valueOf(effectiveProxyConfig.getRemoteProxyPort()));
			} else {
				LOGGER.debug("no transport strategy provided, expecting mail.smtp(s).socks.host and .port properties to be set to proxy " +
						"config on Session");
			}
			if (effectiveProxyConfig.requiresAuthentication()) {
				if (transportStrategy != null) {
					sessionProperties.put(transportStrategy.propertyNameSocksHost(), "localhost");
					sessionProperties.put(transportStrategy.propertyNameSocksPort(), String.valueOf(effectiveProxyConfig.getProxyBridgePort()));
				} else {
					LOGGER.debug("no transport strategy provided but authenticated proxy required, expecting mail.smtp(s).socks.host and .port " +
							"properties to be set to localhost and port " + effectiveProxyConfig.getProxyBridgePort());
				}
				return new AnonymousSocks5Server(new AuthenticatingSocks5Bridge(effectiveProxyConfig),
						effectiveProxyConfig.getProxyBridgePort());
			}
		}
		return null;
	}

	/**
	 * Processes an {@link Email} instance into a completely configured {@link Message}.
	 * <p/>
	 * Sends the Sun JavaMail {@link Message} object using {@link Session#getTransport()}. It will call {@link Transport#connect()} assuming all
	 * connection details have been configured in the provided {@link Session} instance and finally {@link Transport#sendMessage(Message,
	 * Address[])}.
	 * <p/>
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
			if (executor == null || executor.isTerminated()) {
				executor = Executors.newFixedThreadPool(threadPoolSize);
			}
			configureSessionWithTimeout(session, sessionTimeout);
			executor.execute(new Runnable() {
				@Override
				public void run() {
					sendMailClosure(session, email);
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
			
			logSession(session);
			message.saveChanges(); // some headers and id's will be set for this specific message
			email.setId(message.getMessageID());
			final Transport transport = session.getTransport();

			try {
				synchronized (this) {
					// proxy server is null when not needed
					if (proxyServer != null && !proxyServer.isRunning()) {
						LOGGER.trace("starting proxy bridge");
						proxyServer.start();
					}
				}

				if (!transportModeLoggingOnly) {
					LOGGER.trace("\t\nEmail: {}", email);
					LOGGER.trace("\t\nMimeMessage: {}\n", mimeMessageToEML(message));

					try {
						transport.connect();
						transport.sendMessage(message, message.getAllRecipients());
					} finally {
						LOGGER.trace("closing transport");
						//noinspection ThrowFromFinallyBlock
						transport.close();
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
	
	private void configureBounceToAddress(Session session, Email email) {
		Recipient bounceAddress = email.getBounceToRecipient();
		if (bounceAddress != null) {
			if (transportStrategy != null) {
				String formattedRecipient = format("%s <%s>", bounceAddress.getName(), bounceAddress.getAddress());
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
            if (proxyServer != null && proxyServer.isRunning() && !proxyServer.isStopping()) {
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
	private static void logSession(final Session session) {
		final TransportStrategy transportStrategy = TransportStrategy.findStrategyForSession(session);
		final Properties properties = session.getProperties();
		final String sessionDetails = (transportStrategy != null) ? transportStrategy.toString(properties) : properties.toString();
		LOGGER.debug("starting mail with " + sessionDetails);
	}

	/**
	 * Refer to Session{@link Session#setDebug(boolean)}
	 */
	public void setDebug(final boolean debug) {
		session.setDebug(debug);
	}

	/**
	 * Configures the current session to trust all hosts and don't validate any SSL keys. The property "mail.smtp(s).ssl.trust" is set to "*".
	 * <p>
	 * Refer to https://javamail.java.net/nonav/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust
	 */
	public void trustAllHosts(final boolean trustAllHosts) {
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
	 * Configures the current session to only accept server certificates issued to one of the provided hostnames,
	 * <strong>and disables certificate issuer validation.</strong>
	 * <p>
	 * Passing an empty list resets the current session's trust behavior to the default, and is equivalent to never
	 * calling this method in the first place.
	 * <p>
	 * <strong>Security warning:</strong> Any certificate matching any of the provided host names will be accepted,
	 * regardless of the certificate issuer; attackers can abuse this behavior by serving a matching self-signed
	 * certificate during a man-in-the-middle attack.
	 * <p>
	 * This method sets the property {@code mail.smtp(s).ssl.trust} to a space-separated list of the provided
	 * {@code hosts}. If the provided list is empty, {@code mail.smtp(s).ssl.trust} is unset.
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust"><code>mail.smtp.ssl.trust</code></a>
	 */
	public void trustHosts(final String... hosts) {
		trustAllHosts(false);
		if (hosts.length > 0) {
			if (transportStrategy == null) {
				throw new MailSenderException(MailSenderException.CANNOT_SET_TRUST_WITHOUT_TRANSPORTSTRATEGY);
			}
			final StringBuilder builder = new StringBuilder(hosts[0]);
			for (int i = 1; i < hosts.length; i++) {
				builder.append(" ").append(hosts[i]);
			}
			session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), builder.toString());
		}
	}

	/**
	 * @param properties Properties which will be added to the current {@link Session} instance.
	 */
	public void applyProperties(final Properties properties) {
		session.getProperties().putAll(properties);
	}

	/**
	 * For emergencies, when a client really wants access to the internally created {@link Session} instance.
	 */
	public Session getSession() {
		return session;
	}
	
	/**
	 * @param threadPoolSize The maximum number of threads when sending emails in async fashion.
	 * @see Property#DEFAULT_POOL_SIZE
	 */
	public synchronized void setThreadPoolSize(final int threadPoolSize) {
		this.threadPoolSize = threadPoolSize;
	}
	
	/**
	 * @param sessionTimeout The timeout to use when sending emails (affects socket connect-, read- and write timeouts).
	 * @see Property#DEFAULT_SESSION_TIMEOUT_MILLIS
	 */
	public synchronized void setSessionTimeout(final int sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
	}

	/**
	 * Sets the transport mode for this mail sender to logging only, which means no mail will be actually sent out.
	 */
	public synchronized void setTransportModeLoggingOnly(final boolean transportModeLoggingOnly) {
		this.transportModeLoggingOnly = transportModeLoggingOnly;
	}
	
	/**
	 * @return {@link #transportModeLoggingOnly}
	 */
	public boolean isTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}
}