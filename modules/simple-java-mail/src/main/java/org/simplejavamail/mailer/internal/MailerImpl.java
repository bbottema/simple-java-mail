/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.mailer.MailerHelper;
import org.simplejavamail.mailer.internal.util.SmtpAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.Session;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.simplejavamail.api.mailer.config.TransportStrategy.findStrategyForSession;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

/**
 * @see Mailer
 * @see org.simplejavamail.converter.internal.mimemessage.MimeMessageProducer
 */
public class MailerImpl implements Mailer {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(MailerImpl.class);

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
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@NotNull
	private final EnumSet<EmailAddressCriteria> emailAddressCriteria;
	
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

	MailerImpl(@NotNull final MailerFromSessionBuilderImpl fromSessionBuilder) {
		this(null,
				null,
				fromSessionBuilder.getEmailAddressCriteria(),
				fromSessionBuilder.buildProxyConfig(),
				fromSessionBuilder.getSession(),
				fromSessionBuilder.buildOperationalConfig());
	}
	
	MailerImpl(@NotNull final MailerRegularBuilderImpl regularBuilder) {
		this(regularBuilder.buildServerConfig(),
				regularBuilder.getTransportStrategy(),
				regularBuilder.getEmailAddressCriteria(),
				regularBuilder.buildProxyConfig(),
				null,
				regularBuilder.buildOperationalConfig());
	}

	MailerImpl(@Nullable ServerConfig serverConfig, @Nullable TransportStrategy transportStrategy, @NotNull EnumSet<EmailAddressCriteria> emailAddressCriteria, @NotNull ProxyConfig proxyConfig,
			@Nullable Session session, @NotNull OperationalConfig operationalConfig) {
		this.serverConfig = serverConfig;
		this.transportStrategy = transportStrategy;
		this.emailAddressCriteria = emailAddressCriteria;
		this.proxyConfig = proxyConfig;
		if (session == null) {
			session = createMailSession(checkNonEmptyArgument(serverConfig, "serverConfig"), checkNonEmptyArgument(transportStrategy, "transportStrategy"));
		}
		this.session = session;
		this.operationalConfig = operationalConfig;
		final TransportStrategy effectiveTransportStrategy = ofNullable(transportStrategy).orMaybe(findStrategyForSession(session));
		this.proxyServer = configureSessionWithProxy(proxyConfig, operationalConfig, session, effectiveTransportStrategy);
		initSession(session, operationalConfig, effectiveTransportStrategy);
		initCluster(session, operationalConfig);
	}

	/**
	 * Instantiates and configures the {@link Session} instance. Delegates resolving transport protocol specific properties to the given {@link
	 * TransportStrategy} in two ways: <ol> <li>request an initial property list which the strategy may pre-populate</li> <li>by requesting the
	 * property names according to the respective transport protocol it handles (for the host property for example it would be
	 * <em>"mail.smtp.host"</em> for SMTP and <em>"mail.smtps.host"</em> for SMTPS)</li> </ol>
	 * <p>
	 * Furthermore adds proxy SOCKS properties if a proxy configuration was provided, overwriting any SOCKS properties already present.
	 *
	 * @param serverConfig      Remote SMTP server details.
	 * @param transportStrategy The transport protocol strategy enum that actually handles the session configuration. Session configuration meaning
	 *                          setting the right properties for the appropriate transport type (ie. <em>"mail.smtp.host"</em> for SMTP,
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
	public static Session createMailSession(@NotNull final ServerConfig serverConfig, @NotNull final TransportStrategy transportStrategy) {
		final Properties props = transportStrategy.generateProperties();
		props.put(transportStrategy.propertyNameHost(), serverConfig.getHost());
		props.put(transportStrategy.propertyNamePort(), String.valueOf(serverConfig.getPort()));
		
		if (serverConfig.getUsername() != null) {
			props.put(transportStrategy.propertyNameUsername(), serverConfig.getUsername());
		}
		
		if (serverConfig.getPassword() != null) {
			props.put(transportStrategy.propertyNameAuthenticate(), "true");
			return Session.getInstance(props, new SmtpAuthenticator(serverConfig));
		} else {
			return Session.getInstance(props);
		}
	}

	private void initSession(@NotNull final Session session, @NotNull OperationalConfig operationalConfig, @Nullable final TransportStrategy transportStrategy) {
		session.setDebug(operationalConfig.isDebugLogging());
		session.getProperties().putAll(operationalConfig.getProperties());

		configureSessionWithTimeout(session, operationalConfig.getSessionTimeout(), transportStrategy);
		configureTrustedHosts(session, operationalConfig, transportStrategy);
		configureServerIdentityVerification(session, operationalConfig, transportStrategy);
	}

	/**
	 * Configures the {@link Session} with the same timeout for socket connection timeout, read and write timeout.
	 */
	private void configureSessionWithTimeout(@NotNull final Session session, final int sessionTimeout, @Nullable final TransportStrategy transportStrategy) {
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

	private void configureTrustedHosts(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig, @Nullable final TransportStrategy transportStrategy) {
		if (transportStrategy != null) {
			if (operationalConfig.isTrustAllSSLHost()) {
				session.getProperties().setProperty(transportStrategy.propertyNameSSLTrust(), "*");
			} else {
				final List<String> hosts = operationalConfig.getSslHostsToTrust();
				String sslPropertyForTrustingHosts = transportStrategy.propertyNameSSLTrust();
				session.getProperties().remove(sslPropertyForTrustingHosts);
				if (!hosts.isEmpty()) {
					final StringBuilder builder = new StringBuilder(getFirst(hosts));
					for (int i = 1; i < hosts.size(); i++) {
						builder.append(" ").append(hosts.get(i));
					}
					session.getProperties().setProperty(sslPropertyForTrustingHosts, builder.toString());
				}
			}
		} else {
			LOGGER.debug("No transport strategy provided, skipping configuration for trusted hosts");
		}
	}

	private void configureServerIdentityVerification(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig, @Nullable final TransportStrategy transportStrategy) {
		if (transportStrategy != null && transportStrategy != TransportStrategy.SMTP) {
			session.getProperties().setProperty(transportStrategy.propertyNameCheckServerIdentity(),
					Boolean.toString(operationalConfig.isVerifyingServerIdentity()));
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
	private static AnonymousSocks5Server configureSessionWithProxy(
			@NotNull final ProxyConfig proxyConfig,
			@NotNull final OperationalConfig operationalConfig,
			@NotNull final Session session,
			@Nullable final TransportStrategy transportStrategy) {
		if (operationalConfig.getCustomMailer() != null) {
			LOGGER.trace("CustomMailer provided by user, skipping proxy.");
		} else if (!proxyConfig.requiresProxy()) {
			LOGGER.trace("No proxy set, skipping proxy.");
		} else {
			if (transportStrategy == TransportStrategy.SMTPS) {
				throw new MailerException(MailerException.INVALID_PROXY_SLL_COMBINATION);
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

	private void initCluster(@NotNull final Session session, @NotNull final OperationalConfig operationalConfig) {
		if (operationalConfig.getCustomMailer() == null && ModuleLoader.batchModuleAvailable()) {
			ModuleLoader.loadBatchModule().registerToCluster(operationalConfig, operationalConfig.getClusterKey(), session);
		}
	}

	/**
	 * @see Mailer#testConnection()
	 */
	@Override
	public void testConnection() {
		this.testConnection(false);
	}

	/**
	 * @see Mailer#testConnection(boolean)
	 */
	@Nullable
	public synchronized AsyncResponse testConnection(boolean async) {
		TestConnectionClosure testConnectionClosure = new TestConnectionClosure(operationalConfig, session, proxyServer, async, smtpConnectionCounter);

		if (!async) {
			testConnectionClosure.run();
			return null;
		} else {
			return ModuleLoader.loadBatchModule()
					.executeAsync("testSMTPConnection process", testConnectionClosure);
		}
	}

	/**
	 * @see Mailer#sendMail(Email)
	 */
	@Override
	public final void sendMail(final Email email) {
		sendMail(email, getOperationalConfig().isAsync());
	}

	/**
	 * @see Mailer#sendMail(Email, boolean)
	 */
	@Override
	@Nullable
	public final AsyncResponse sendMail(final Email email, @SuppressWarnings("SameParameterValue") final boolean async) {
		if (validate(email)) {
			SendMailClosure sendMailClosure = new SendMailClosure(operationalConfig, session, email, proxyServer, async, operationalConfig.isTransportModeLoggingOnly(),
					smtpConnectionCounter);

			if (!async) {
				sendMailClosure.run();
				return null;
			} else {
				return ModuleLoader.loadBatchModule()
						.executeAsync(operationalConfig.getExecutorService(), "sendMail process", sendMailClosure);
			}
		}
		throw new AssertionError("Email not valid, but no MailException was thrown for it");
	}

	/**
	 * @see Mailer#validate(Email)
	 */
	@Override
	@SuppressWarnings({"SameReturnValue"})
	public boolean validate(@NotNull final Email email)
			throws MailException {
		return MailerHelper.validate(email, emailAddressCriteria);
	}

	/**
	 * @see Mailer#shutdownConnectionPool()
	 */
	@Override
	public Future<?> shutdownConnectionPool() {
		return ModuleLoader.loadBatchModule().shutdownConnectionPools(session);
	}

	/**
	 * @see Mailer#getSession()
	 */
	@Override
	public Session getSession() {
		LOGGER.warn("Providing access to Session instance for emergency fall-back scenario. Please let us know why you need it.");
		LOGGER.warn("\t\t> https://github.com/bbottema/simple-java-mail/issues");
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

	/**
	 * @see Mailer#getEmailAddressCriteria()
	 */
	@Override
	@NotNull
	public EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}
}