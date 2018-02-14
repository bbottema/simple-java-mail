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
		String getProtocol() {return "smtp";}

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
			if (ConfigLoader.valueOrProperty(opportunisticTLS, OPPORTUNISTIC_TLS, DEFAULT_OPPORTUNISTIC_TLS)) {
				LOGGER.debug("Opportunistic TLS mode enabled for SMTP plain protocol.");
				props.put(getPropertyString(PROTOCOL + STARTTLS_ENABLE), TRUE);
				props.put(getPropertyString(PROTOCOL + STARTTLS_REQUIRED), FALSE);
				props.put(propertyNameSSLTrust(), "*");
				props.put(getPropertyString(SSL_CHECK_SERVER_IDENTITY), FALSE);
			}
			return props;
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
		String getProtocol() {return "smtps";}

		/**
		 * @see TransportStrategy#SMTPS
		 */
		@Override
		public Properties generateProperties() {
			final Properties properties = super.generateProperties();
			properties.put(getPropertyString(SSL_CHECK_SERVER_IDENTITY), TRUE);
			properties.put(getPropertyString(PROTOCOL + "quitwait"), FALSE);
			return properties;
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
		String getProtocol() {return "smtp";}

		/**
		 * @see TransportStrategy#SMTP_TLS
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put(getPropertyString(PROTOCOL + STARTTLS_ENABLE), TRUE);
			props.put(getPropertyString(PROTOCOL + STARTTLS_REQUIRED), TRUE);
			props.put(getPropertyString(SSL_CHECK_SERVER_IDENTITY), TRUE);
			return props;
		}
	};

	private static final String FALSE = "false";
	private static final String TRUE = "true";
	private static final String MAIL = "mail.";
	private static final String PROTOCOL = MAIL + "%s.";
	private static final String MAIL_TRANSPORT_PROTOCOL = MAIL + "transport.protocol";
	private static final String HOST = PROTOCOL + "host";
	private static final String SOCKS_HOST = PROTOCOL + "socks.host";
	private static final String PORT = PROTOCOL + "port";
	private static final String SOCKS_PORT = PROTOCOL + "socks.port";
	private static final String AUTH = PROTOCOL + "auth";
	private static final String USERNAME = PROTOCOL + "username";
	private static final String CONNECTION_TIMEOUT = PROTOCOL + "connectiontimeout";
	private static final String TIMEOUT = PROTOCOL + "timeout";
	private static final String WRITE_TIMEOUT = PROTOCOL + "writetimeout";
	private static final String FROM = PROTOCOL + "from";;
	private static final String SSL = PROTOCOL + "ssl.";
	private static final String SSL_CHECK_SERVER_IDENTITY = SSL + "checkserveridentity";
	private static final String SSL_TRUST = SSL + "trust";
	private static final String STARTTLS = "starttls.";
	private static final String STARTTLS_ENABLE = STARTTLS + "enable";
	private static final String STARTTLS_REQUIRED = STARTTLS + "required";

	protected String getPropertyString(final String propertyName) {
		return format(propertyName, getProtocol());
	}

	abstract String getProtocol();

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
		properties.put(MAIL_TRANSPORT_PROTOCOL, getProtocol());
		return properties;
	}

	/**
	 * For internal use only.
	 */
	public String propertyNameHost() {
		return getPropertyString(HOST);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNamePort() {
		return getPropertyString(PORT);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameUsername() {
		return getPropertyString(USERNAME);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameAuthenticate() {
		return getPropertyString(AUTH);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameSocksHost() {
		return getPropertyString(SOCKS_HOST);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameSocksPort() {
		return getPropertyString(SOCKS_PORT);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameConnectionTimeout() {
		return getPropertyString(CONNECTION_TIMEOUT);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameWriteTimeout() {
		return getPropertyString(WRITE_TIMEOUT);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameEnvelopeFrom() {
		return getPropertyString(FROM);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameSSLTrust() {
		return getPropertyString(SSL_TRUST);
	}
	/**
	 * For internal use only.
	 */
	public String propertyNameTimeout() {
		return getPropertyString(TIMEOUT);
	}
	
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