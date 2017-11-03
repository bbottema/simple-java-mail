package org.simplejavamail.mailer.config;

import javax.mail.Session;
import java.util.Properties;

import static java.lang.String.format;

/**
 * Defines the various types of transport protocols and implements respective properties so that a {@link Session} may be configured using a
 * <code>TransportStrategy</code> implementation.
 *
 * @author Benny Bottema
 */
public enum TransportStrategy {

	/**
	 * Simplest possible form: only vanilla ".smtp." property names and no extra properties. Additionally the transport protocol is explicitly set to
	 * smtp.
	 */
	SMTP_PLAIN {
		/**
		 * Here protocol "mail.transport.protocol" is set to "smtp".
		 *
		 * @see TransportStrategy#SMTP_PLAIN
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put("mail.transport.protocol", "smtp");
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
	},
	/**
	 * SMTPS / SSL transport strategy, that returns the ".smtps." variation of the SMTP_PLAIN version. Additionally the transport protocol is
	 * explicitly set to smtps. Finally, he property "mail.smtps.quitwait" is set to false, to get rid of a strange SSL exception:<br>
	 * <p>
	 * <pre>
	 * javax.mail.MessagingException: Exception reading response;
	 * nested exception is:
	 * 	javax.net.ssl.SSLException: Unsupported record version Unknown-50.49
	 * (..)
	 * </pre>
	 * <p>
	 * <blockquote>The mail is sent but the exception is unwanted. The property <em>quitwait</em> means If set to false, the QUIT command is sent and
	 * the connection is immediately closed. If set to true (the default), causes the transport to wait for the response to the QUIT
	 * command</blockquote><br> <strong>- <a href="http://www.rgagnon.com/javadetails/java-0570.html">source</a></strong>
	 */
	SMTP_SSL {
		/**
		 * Here protocol "mail.transport.protocol" is set to "smtps" and the quitwait property "mail.smtps.quitwait" is set to "false".
		 *
		 * @see TransportStrategy#SMTP_SSL
		 */
		@Override
		public Properties generateProperties() {
			final Properties properties = super.generateProperties();
			properties.put("mail.transport.protocol", "smtps");
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
	 * <strong>NOTE: this code is in untested beta state</strong>
	 * <p>
	 * Uses standard ".smtp." property names (like {@link TransportStrategy#SMTP_PLAIN}). Additionally the transport protocol is explicitly set to
	 * smtp. Finally, the property "mail.smtp.starttls.enable" is being set to true.
	 */
	SMTP_TLS {
		/**
		 * Here protocol "mail.transport.protocol" is set to "smtp" and the tls property "mail.smtp.starttls.enable" is set to "true".
		 *
		 * @see TransportStrategy#SMTP_TLS
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put("mail.transport.protocol", "smtp");
			props.put("mail.smtp.starttls.enable", "true");
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

	/**
	 * Marker property used to track which {@link TransportStrategy} has been used. This way we can differentiate between preconfigured custom
	 * <code>Session</code> and sessions created by a <code>Mailer</code> instance, without checking each and every property for a specific strategy.
	 * <p>
	 * This is mainly for logging purposes.
	 */
	private static final String TRANSPORT_STRATEGY_MARKER = "simplejavamail.transportstrategy";

	/**
	 * Base implementation that simply returns an empty list of properties and a marker for the specific current strategy.
	 * <p>
	 * Should be overridden by the various strategies where appropriate.
	 *
	 * @return An empty <code>Properties</code> instance.
	 */
	public Properties generateProperties() {
		final Properties properties = new Properties();
		properties.put(TRANSPORT_STRATEGY_MARKER, name());
		return properties;
	}

	public abstract String propertyNameHost();
	public abstract String propertyNamePort();
	public abstract String propertyNameUsername();
	public abstract String propertyNameAuthenticate();
	public abstract String propertyNameSocksHost();
	public abstract String propertyNameSocksPort();
	public abstract String propertyNameConnectionTimeout();
	public abstract String propertyNameWriteTimeout();
	public abstract String propertyNameEnvelopeFrom();
	public abstract String propertyNameSSLTrust();
	public abstract String propertyNameTimeout();
	
	/**
	 * @param session The session to determine the current transport strategy for
	 * @return Which strategy matches the current Session properties.
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