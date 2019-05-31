package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.internal.mailsender.MailSender;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.mailer.internal.MailerGenericBuilderImpl;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Class that performs the actual javax.mail SMTP integration including authenticated socks proxy.
 * <p>
 * Refer to {@link #send(Email, boolean)} for details.
 *
 * @see org.simplejavamail.converter.internal.mimemessage.MimeMessageProducer
 */
public class MailSenderImpl implements MailSender {

	private static final Logger LOGGER = getLogger(MailSenderImpl.class);

	/**
	 * Used to actually send the email. This session can come from being passed in the default constructor, or made by <code>Mailer</code> directly.
	 */
	@Nonnull
	private final Session session;
	
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
	 */
	@Nonnull
	private final AtomicInteger smtpConnectionCounter = new AtomicInteger();

	public MailSenderImpl(@Nonnull final Session session,
						  @Nonnull final OperationalConfig operationalConfig,
						  @Nonnull final ProxyConfig proxyConfig,
						  @Nullable final TransportStrategy transportStrategy) {
		initSession(session, operationalConfig, transportStrategy);

		this.session = session;
		this.operationalConfig = operationalConfig;
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
	 * @see MailSender#send(Email, boolean)
	 */
	@Override
	@Nullable
	public final synchronized AsyncResponse send(final Email email, final boolean async) {
		SendMailClosure sendMailClosure = new SendMailClosure(session, email, proxyServer, async, operationalConfig.isTransportModeLoggingOnly(), smtpConnectionCounter);

		if (!async) {
			sendMailClosure.run();
			return null;
		} else {
			return ModuleLoader.loadBatchModule()
					.executeAsync(operationalConfig.getExecutorService(), "sendMail process", sendMailClosure);
		}
	}

	/**
	 * @see MailSender#testConnection(boolean)
	 */
	@Override
	@Nullable
	public synchronized AsyncResponse testConnection(boolean async) {
		TestConnectionClosure testConnectionClosure = new TestConnectionClosure(session, proxyServer, async, smtpConnectionCounter);

		if (!async) {
			testConnectionClosure.run();
			return null;
		} else {
			return ModuleLoader.loadBatchModule()
					.executeAsync("testSMTPConnection process", testConnectionClosure);
		}
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