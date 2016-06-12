package org.codemonkey.simplejavamail;

import javax.mail.Session;
import java.util.Properties;

/**
 * Defines the various types of transport protocols and implements respective properties so that a {@link Session} may be configured using a
 * <code>TransportStrategy</code> implementation.
 * 
 * @author Benny Bottema
 */
public enum TransportStrategy {

	/**
	 * Simplest possible form: only vanilla ".smtp." property names and no extra properties. Additionally the transport protocol is
	 * explicitly set to smtp.
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
		String propertyNameHost() {
			return "mail.smtp.host";
		}

		/**
		 * @return "mail.smtp.port"
		 */
		@Override
		String propertyNamePort() {
			return "mail.smtp.port";
		}

		/**
		 * @return "mail.smtp.username"
		 */
		@Override
		String propertyNameUsername() {
			return "mail.smtp.username";
		}

		/**
		 * @return "mail.smtp.auth"
		 */
		@Override
		String propertyNameAuthenticate() {
			return "mail.smtp.auth";
		}

		/**
		 * @return Whether Session is configured for vanilla SMTP.
		 */
		@Override
		public boolean sessionConfigured(Session session) {
			return "smtp".equals(session.getProperties().getProperty("mail.transport.protocol"));
		}
	},
	/**
	 * SMTPS / SSL transport strategy, that returns the ".smtps." variation of the SMTP_PLAIN version. Additionally the transport protocol
	 * is explicitly set to smtps. Finally, he property "mail.smtps.quitwait" is set to false, to get rid of a strange SSL exception:<br>
	 * 
	 * <pre>
	 * javax.mail.MessagingException: Exception reading response;
	 * nested exception is:
	 * 	javax.net.ssl.SSLException: Unsupported record version Unknown-50.49
	 * (..)
	 * </pre>
	 * 
	 * <blockquote>The mail is sent but the exception is unwanted. The property <em>quitwait</em> means If set to false, the QUIT command is sent
	 * and the connection is immediately closed. If set to true (the default), causes the transport to wait for the response to the QUIT
	 * command</blockquote><br>
	 * <strong>- <a href="http://www.rgagnon.com/javadetails/java-0570.html">source</a></strong>
	 */
	SMTP_SSL {
		/**
		 * Here protocol "mail.transport.protocol" is set to "smtps" and the quitwait property "mail.smtps.quitwait" is set to "false".
		 * 
		 * @see TransportStrategy#SMTP_SSL
		 */
		@Override
		public Properties generateProperties() {
			final Properties props = super.generateProperties();
			props.put("mail.transport.protocol", "smtps");
			props.put("mail.smtps.quitwait", "false");
			return props;
		}

		/**
		 * @return "mail.smtps.host"
		 */
		@Override
		String propertyNameHost() {
			return "mail.smtps.host";
		}

		/**
		 * @return "mail.smtps.port"
		 */
		@Override
		String propertyNamePort() {
			return "mail.smtps.port";
		}

		/**
		 * @return "mail.smtps.username"
		 */
		@Override
		String propertyNameUsername() {
			return "mail.smtps.username";
		}

		/**
		 * @return "mail.smtps.auth"
		 */
		@Override
		String propertyNameAuthenticate() {
			return "mail.smtps.auth";
		}

		/**
		 * @return Whether Session is configured for SSL.
		 */
		@Override
		public boolean sessionConfigured(Session session) {
			return "smtps".equals(session.getProperties().getProperty("mail.transport.protocol"));
		}
	},
	/**
	 * <strong>NOTE: this code is in untested beta state</strong>
	 * <p>
	 * Uses standard ".smtp." property names (like {@link TransportStrategy#SMTP_PLAIN}). Additionally the transport protocol is explicitly
	 * set to smtp. Finally, the property "mail.smtp.starttls.enable" is being set to true.
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
		String propertyNameHost() {
			return "mail.smtp.host";
		}

		/**
		 * @return "mail.smtp.port"
		 */
		@Override
		String propertyNamePort() {
			return "mail.smtp.port";
		}

		/**
		 * @return "mail.smtp.username"
		 */
		@Override
		String propertyNameUsername() {
			return "mail.smtp.username";
		}

		/**
		 * @return "mail.smtp.auth"
		 */
		@Override
		String propertyNameAuthenticate() {
			return "mail.smtp.auth";
		}

		/**
		 * @return Whether Session is configured for TLS.
		 */
		@Override
		public boolean sessionConfigured(Session session) {
			return "smtp".equals(session.getProperties().getProperty("mail.transport.protocol")) &&
					"true".equals(session.getProperties().getProperty("mail.smtp.starttls.enable"));
		}
	};

	/**
	 * Base implementation that simply returns an empty list of properties. Should be overridden by the various strategies where
	 * appropriate.
	 * 
	 * @return An empty <code>Properties</code> instance.
	 */
	public Properties generateProperties() {
		return new Properties();
	}

	abstract String propertyNameHost();

	abstract String propertyNamePort();

	abstract String propertyNameUsername();

	abstract String propertyNameAuthenticate();

	/**
	 * @return Whether Session is configured with a strategy pattern.
	 */
	abstract boolean sessionConfigured(Session session);

	/**
	 * @param session The session to determine the current transport strategy for
	 * @return Which strategy matches the current Session properties.
	 * @see #sessionConfigured(Session)
	 */
	public static TransportStrategy findStrategyForSession(Session session) {
		for (TransportStrategy strategy : TransportStrategy.values()) {
			if (strategy.sessionConfigured(session)) {
				return strategy;
			}
		}
		return null;
	}
}