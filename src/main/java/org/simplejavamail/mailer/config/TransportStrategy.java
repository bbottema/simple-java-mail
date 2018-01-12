package org.simplejavamail.mailer.config;

import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.util.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.Properties;

import static java.lang.String.format;
import static org.simplejavamail.util.ConfigLoader.Property.OPPORTUNISTIC_TLS;

/**
 * Defines the various types of transport protocols and implements respective properties so that a {@link Session} may be configured using a
 * {@link TransportStrategy} implementation.
 */
public enum TransportStrategy {

	/**
	 * Vanilla SMTP with an insecure STARTTLS upgrade (if supported).
	 * <p>
	 * This {@code TransportStrategy} falls back to plaintext when a mail server does not indicate support for
	 * STARTTLS. Additionally, even if a TLS session is negotiated, <strong>server certificates are not validated in
	 * any way</strong>.
	 * <p>
	 * This {@code TransportStrategy} only offers protection against passive network eavesdroppers when the mail server
	 * indicates support for STARTTLS. Active network attackers can trivially bypass the encryption 1) by tampering with
	 * the STARTTLS indicator, 2) by presenting a self-signed certificate, 3) by presenting a certificate issued by an
	 * untrusted certificate authority; or 4) by presenting a certificate that was issued by a valid certificate
	 * authority to a domain other than the mail server's.
	 * <p>
	 * For proper mail transport encryption, see {@link TransportStrategy#SMTPS} or
	 * {@link TransportStrategy#SMTP_TLS}.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *     <li>The transport protocol is explicitly set to {@code smtp}.</li>
	 *     <li>Only {@code mail.smtp} properties are set.</li>
	 *     <li>STARTTLS is enabled by setting {@code mail.smtp.starttls.enable} to {@code true}.</li>
	 *     <li>STARTTLS plaintext fallback is enabled by setting {@code mail.smtp.starttls.required} to {@code false}.</li>
	 *     <li>Certificate issuer checks are disabled by setting {@code mail.smtp.ssl.trust} to {@code "*"}.</li>
	 *     <li>Certificate identity checks are disabled by setting {@code mail.smtp.ssl.checkserveridentity} to {@code false}.</li>
     * </ul>
	 */
	SMTP {
		
		/**
		 * Defaults to enabled opportunistic TLS behavior ({@link #opportunisticTLS}), in case value was not programmatically set or provided
		 * as property value.
		 */
		private static final boolean DEFAULT_OPPORTUNISTIC_TLS = true;
		
		/**
		 * Determines whether TLS should be attempted for SMTP plain protocol (optional if offered by the SMTP server). If not set and no property
		 * was provided, this value defaults to {@value DEFAULT_OPPORTUNISTIC_TLS}.
		 * <p>
		 * Setting this flag to false causes the {@link TransportStrategy#SMTP} to revert back to the legacy behavior.
		 */
		@Nullable
		private Boolean opportunisticTLS;
		
		/**
		 * @see TransportStrategy#SMTP
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put("mail.transport.protocol", "smtp");
			if (ConfigLoader.valueOrProperty(opportunisticTLS, OPPORTUNISTIC_TLS, DEFAULT_OPPORTUNISTIC_TLS)) {
				LOGGER.debug("Opportunistic TLS mode enabled for SMTP plain protocol.");
				props.put("mail.smtp.starttls.enable", "true");
				props.put("mail.smtp.starttls.required", "false");
				props.put("mail.smtp.ssl.trust", "*");
				props.put("mail.smtp.ssl.checkserveridentity", "false");
			}
			return props;
		}

		/**
		 * @return "mail.smtp.host"
		 */
		@Override
		public String propertyNameHost() {
			return "mail.smtp.host";
		}

		/**
		 * @return "mail.smtp.port"
		 */
		@Override
		public String propertyNamePort() {
			return "mail.smtp.port";
		}

		/**
		 * @return "mail.smtp.username"
		 */
		@Override
		public String propertyNameUsername() {
			return "mail.smtp.username";
		}

		/**
		 * @return "mail.smtp.auth"
		 */
		@Override
		public String propertyNameAuthenticate() {
			return "mail.smtp.auth";
		}
		
		/**
		 * @return "mail.smtp.socks.host"
		 */
		@Override
		public String propertyNameSocksHost() {
			return "mail.smtp.socks.host";
		}
		
		/**
		 * @return "mail.smtp.socks.port"
		 */
		@Override
		public String propertyNameSocksPort() {
			return "mail.smtp.socks.port";
		}
		
		/**
		 * @return "mail.smtp.connectiontimeout"
		 */
		@Override
		public String propertyNameConnectionTimeout() {
			return "mail.smtp.connectiontimeout";
		}
		
		/**
		 * @return "mail.smtp.timeout"
		 */
		@Override
		public String propertyNameTimeout() {
			return "mail.smtp.timeout";
		}
		
		/**
		 * @return "mail.smtp.writetimeout"
		 */
		@Override
		public String propertyNameWriteTimeout() {
			return "mail.smtp.writetimeout";
		}
		
		/**
		 * @return "mail.smtp.from"
		 */
		@Override
		public String propertyNameEnvelopeFrom() {
			return "mail.smtp.from";
		}
		
		/**
		 * @return "mail.smtp.ssl.trust"
		 */
		@Override
		public String propertyNameSSLTrust() {
			return "mail.smtp.ssl.trust";
		}
		
		/**
		 * Sets {@link #opportunisticTLS}. Setting {@code null} will revert to property value if available or default to {@value
		 * DEFAULT_OPPORTUNISTIC_TLS}
		 */
		@Override
		public void setOpportunisticTLS(@Nullable final Boolean opportunisticTLS) {
			this.opportunisticTLS = opportunisticTLS;
		}
	},
	/**
	 * SMTP entirely encapsulated by TLS. Commonly known as SMTPS.
	 * <p>
	 * Strict validation of server certificates is enabled. Server certificates must be issued 1) by a certificate
	 * authority in the system trust store; and 2) to a subject matching the identity of the remote SMTP server.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *     <li>The transport protocol is explicitly set to {@code smtps}.</li>
	 *     <li>Only {@code mail.smtps} properties are set.</li>
	 *     <li>Certificate identity checks are enabled by setting {@code mail.smtp.ssl.checkserveridentity} to {@code true}.</li>
	 *     <li>
	 * {@code mail.smtps.quitwait} is set to {@code false} to get rid of a strange SSLException:
	 * <pre>
	 * javax.mail.MessagingException: Exception reading response;
	 * nested exception is:
	 * 	javax.net.ssl.SSLException: Unsupported record version Unknown-50.49
	 * (..)</pre>
	 * <blockquote>The mail is sent but the exception is unwanted. The property <em>quitwait</em> means If set to false, the QUIT command is sent and
	 * the connection is immediately closed. If set to true (the default), causes the transport to wait for the response to the QUIT
	 * command</blockquote><br> <strong>- <a href="http://www.rgagnon.com/javadetails/java-0570.html">source</a></strong>
	 *     </li>
	 * </ul>
	 */
	SMTPS {
		/**
		 * @see TransportStrategy#SMTPS
		 */
		@Override
		public Properties generateProperties() {
			final Properties properties = super.generateProperties();
			properties.put("mail.transport.protocol", "smtps");
			properties.put("mail.smtps.ssl.checkserveridentity", "true");
			properties.put("mail.smtps.quitwait", "false");
			return properties;
		}

		/**
		 * @return "mail.smtps.host"
		 */
		@Override
		public String propertyNameHost() {
			return "mail.smtps.host";
		}

		/**
		 * @return "mail.smtps.port"
		 */
		@Override
		public String propertyNamePort() {
			return "mail.smtps.port";
		}

		/**
		 * @return "mail.smtps.username"
		 */
		@Override
		public String propertyNameUsername() {
			return "mail.smtps.username";
		}

		/**
		 * @return "mail.smtps.auth"
		 */
		@Override
		public String propertyNameAuthenticate() {
			return "mail.smtps.auth";
		}
		
		/**
		 * @return "mail.smtps.socks.host"
		 */
		@Override
		public String propertyNameSocksHost() {
			return "mail.smtps.socks.host";
		}
		
		/**
		 * @return "mail.smtps.socks.port"
		 */
		@Override
		public String propertyNameSocksPort() {
			return "mail.smtps.socks.port";
		}
		
		/**
		 * @return "mail.smtps.connectiontimeout"
		 */
		@Override
		public String propertyNameConnectionTimeout() {
			return "mail.smtps.connectiontimeout";
		}
		
		/**
		 * @return "mail.smtps.timeout"
		 */
		@Override
		public String propertyNameTimeout() {
			return "mail.smtps.timeout";
		}
		
		/**
		 * @return "mail.smtps.writetimeout"
		 */
		@Override
		public String propertyNameWriteTimeout() {
			return "mail.smtps.writetimeout";
		}
		
		/**
		 * @return "mail.smtps.from"
		 */
		@Override
		public String propertyNameEnvelopeFrom() {
			return "mail.smtps.from";
		}
		
		/**
		 * @return "mail.smtps.ssl.trust"
		 */
		@Override
		public String propertyNameSSLTrust() {
			return "mail.smtps.ssl.trust";
		}
	},
	/**
	 * Plaintext SMTP with a mandatory, authenticated STARTTLS upgrade.
	 * <p>
	 * Strict validation of server certificates is enabled. Server certificates must be issued 1) by a certificate
	 * authority in the system trust store; and 2) to a subject matching the identity of the remote SMTP server.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *     <li>The transport protocol is explicitly set to {@code smtp}.</li>
	 *     <li>Only {@code mail.smtp} properties are set.</li>
	 *     <li>STARTTLS is enabled by setting {@code mail.smtp.starttls.enable} to {@code true}.</li>
	 *     <li>STARTTLS plaintext fallback is disabled by setting {@code mail.smtp.starttls.required} to {@code true}.</li>
	 *     <li>Certificate identity checks are enabled by setting {@code mail.smtp.ssl.checkserveridentity} to {@code true}.</li>
	 * </ul>
	 */
	SMTP_TLS {
		/**
		 * @see TransportStrategy#SMTP_TLS
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put("mail.transport.protocol", "smtp");
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
			props.put("mail.smtp.ssl.checkserveridentity", "true");
			return props;
		}

		/**
		 * @return "mail.smtp.host"
		 */
		@Override
		public String propertyNameHost() {
			return "mail.smtp.host";
		}

		/**
		 * @return "mail.smtp.port"
		 */
		@Override
		public String propertyNamePort() {
			return "mail.smtp.port";
		}

		/**
		 * @return "mail.smtp.username"
		 */
		@Override
		public String propertyNameUsername() {
			return "mail.smtp.username";
		}

		/**
		 * @return "mail.smtp.auth"
		 */
		@Override
		public String propertyNameAuthenticate() {
			return "mail.smtp.auth";
		}
		
		/**
		 * @return "mail.smtp.socks.host"
		 */
		@Override
		public String propertyNameSocksHost() {
			return "mail.smtp.socks.host";
		}
		
		/**
		 * @return "mail.smtp.socks.port"
		 */
		@Override
		public String propertyNameSocksPort() {
			return "mail.smtp.socks.port";
		}
		
		/**
		 * @return "mail.smtp.connectiontimeout"
		 */
		@Override
		public String propertyNameConnectionTimeout() {
			return "mail.smtp.connectiontimeout";
		}
		
		/**
		 * @return "mail.smtp.timeout"
		 */
		@Override
		public String propertyNameTimeout() {
			return "mail.smtp.timeout";
		}
		
		/**
		 * @return "mail.smtp.writetimeout"
		 */
		@Override
		public String propertyNameWriteTimeout() {
			return "mail.smtp.writetimeout";
		}
		
		/**
		 * @return "mail.smtp.from"
		 */
		@Override
		public String propertyNameEnvelopeFrom() {
			return "mail.smtp.from";
		}
		
		/**
		 * @return "mail.smtp.ssl.trust"
		 */
		@Override
		public String propertyNameSSLTrust() {
			return "mail.smtp.ssl.trust";
		}
	};
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TransportStrategy.class);
	
	/**
	 * Marker property used to track which {@link TransportStrategy} has been used. This way we can differentiate between preconfigured custom
	 * {@link Session} and sessions created by a {@link Mailer} instance, without checking each and every property for a specific strategy.
	 * <p>
	 * This is mainly for logging purposes.
	 */
	private static final String TRANSPORT_STRATEGY_MARKER = "simplejavamail.transportstrategy";
	
	/**
	 * For internal use only.
	 */
	public Properties generateProperties() {
		final Properties properties = new Properties();
		properties.put(TRANSPORT_STRATEGY_MARKER, name());
		return properties;
	}
	
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameHost();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNamePort();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameUsername();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameAuthenticate();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameSocksHost();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameSocksPort();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameConnectionTimeout();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameWriteTimeout();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameEnvelopeFrom();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameSSLTrust();
	/**
	 * For internal use only.
	 */
	public abstract String propertyNameTimeout();
	
	/**
	 * Determines whether TLS should be attempted for SMTP plain protocol (optional if offered by the SMTP server). If not set and no property
	 * was provided, this value defaults to it default.
	 * <p>
	 * Setting this flag to false causes {@link TransportStrategy#SMTP} to revert back to the legacy behavior.
	 */
	public void setOpportunisticTLS(@Nullable final Boolean opportunisticTLS) {}
	
	/**
	 * For internal use only.
	 *
	 * @param session The session to determine the current transport strategy for
	 * @return Which strategy matches the current Session properties.
	 * @see #TRANSPORT_STRATEGY_MARKER
	 * @see #generateProperties()
	 */
	public static TransportStrategy findStrategyForSession(final Session session) {
		final String transportStrategyMarker = session.getProperty(TRANSPORT_STRATEGY_MARKER);
		if (transportStrategyMarker != null) {
			return TransportStrategy.valueOf(transportStrategyMarker);
		}
		return null;
	}

	public String toString(final Properties properties) {
		return format("session (host: %s, port: %s, username: %s, authenticate: %s, transport: %s)",
				properties.get(propertyNameHost()),
				properties.get(propertyNamePort()),
				properties.get(propertyNameUsername()),
				properties.get(propertyNameAuthenticate()),
				this);
	}
}