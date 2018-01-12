package org.simplejavamail.mailer;

import org.simplejavamail.mailer.config.TransportStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;

import static org.simplejavamail.util.ConfigLoader.Property.SMTP_HOST;
import static org.simplejavamail.util.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.util.ConfigLoader.Property.SMTP_PORT;
import static org.simplejavamail.util.ConfigLoader.Property.SMTP_USERNAME;
import static org.simplejavamail.util.ConfigLoader.Property.TRANSPORT_STRATEGY;
import static org.simplejavamail.util.ConfigLoader.getProperty;
import static org.simplejavamail.util.ConfigLoader.hasProperty;

/**
 * Entry builder used to start a {@link MailerGenericBuilder} and fully configure a Mailer.
 * <p>
 * Any of these methods return a specialized builder, of which there are two:
 * <ul>
 * <li>One to configure a Mailer using a custom {@link Session} instance</li>
 * <li>One to fully configure a Mailer which will produce its own {@link Session} instance</li>
 * </ul>
 *
 * @see MailerFromSessionBuilder
 * @see MailerRegularBuilder
 */
public class MailerBuilder {
	
	/**
	 * Delegates to {@link MailerFromSessionBuilder#usingSession(Session)}.
	 */
	public static MailerFromSessionBuilder usingSession(@Nonnull final Session session) {
		return new MailerFromSessionBuilder().usingSession(session);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withTransportStrategy(TransportStrategy)}.
	 */
	public static MailerRegularBuilder withTransportStrategy(@Nonnull final TransportStrategy transportStrategy) {
		return new MailerRegularBuilder().withTransportStrategy(transportStrategy);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer, String, String)}.
	 */
	public static MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username, @Nullable final String password) {
		return new MailerRegularBuilder().withSMTPServer(host, port, username, password);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer, String)}.
	 */
	public static MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username) {
		return new MailerRegularBuilder().withSMTPServer(host, port, username);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer)}.
	 */
	public static MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port) {
		return new MailerRegularBuilder().withSMTPServer(host, port);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerHost(String)}.
	 */
	public static MailerRegularBuilder withSMTPServerHost(@Nullable final String host) {
		return new MailerRegularBuilder().withSMTPServerHost(host);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerPort(Integer)}.
	 */
	public static MailerRegularBuilder withSMTPServerPort(@Nullable final Integer port) {
		return new MailerRegularBuilder().withSMTPServerPort(port);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerUsername(String)}.
	 */
	public static MailerRegularBuilder withSMTPServerUsername(@Nullable final String username) {
		return new MailerRegularBuilder().withSMTPServerUsername(username);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerPassword(String)}.
	 */
	public static MailerRegularBuilder withSMTPServerPassword(@Nullable final String password) {
		return new MailerRegularBuilder().withSMTPServerPassword(password);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withDebugLogging(Boolean)}
	 * <p>
	 * <strong>Note:</strong> Assumes you don't want to use your own {@link Session} object (otherwise start with {@link #usingSession(Session)}
	 * instead).
	 */
	public static MailerRegularBuilder withDebugLogging(Boolean debugLogging) {
		return new MailerRegularBuilder().withDebugLogging(debugLogging);
	}
	
	/**
	 * Shortcuts to {@link MailerRegularBuilder#buildMailer()}. This means that none of the builder methods are used and the configuration completely
	 * depends on defaults being configured from property file ("simplejavamail.properties") on the classpath or through programmatic defaults.
	 */
	public static Mailer buildMailer() {
		return new MailerRegularBuilder().buildMailer();
	}
	
	private MailerBuilder() {
	}
	
	/**
	 * Default builder for generating Mailer instances.
	 * <p>
	 * In addition on generic Mailer setting, this builder is used to configure SMTP server details and transport strategy needed to produce a valid
	 * {@link Session} instance.
	 *
	 * @see TransportStrategy
	 */
	public static class MailerRegularBuilder extends MailerGenericBuilder<MailerRegularBuilder> {
		
		private String host;
		private Integer port;
		private String username;
		private String password;
		private TransportStrategy transportStrategy;
		
		/**
		 * Sets defaults configured for SMTP host, SMTP port, SMTP username, SMTP password and transport strategy.
		 * <p>
		 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
		 */
		MailerRegularBuilder() {
			if (hasProperty(SMTP_HOST)) {
				withSMTPServerHost((String) getProperty(SMTP_HOST));
			}
			if (hasProperty(SMTP_PORT)) {
				withSMTPServerPort((Integer) getProperty(SMTP_PORT));
			}
			if (hasProperty(SMTP_USERNAME)) {
				withSMTPServerUsername((String) getProperty(SMTP_USERNAME));
			}
			if (hasProperty(SMTP_PASSWORD)) {
				withSMTPServerPassword((String) getProperty(SMTP_PASSWORD));
			}
			withTransportStrategy(TransportStrategy.SMTP);
			if (hasProperty(TRANSPORT_STRATEGY)) {
				withTransportStrategy((TransportStrategy) getProperty(TRANSPORT_STRATEGY));
			}
		}
		
		/**
		 * Sets the optional transport strategy of this mailer. Will default to {@link TransportStrategy#SMTP} is left empty.
		 */
		public MailerRegularBuilder withTransportStrategy(@Nonnull final TransportStrategy transportStrategy) {
			this.transportStrategy = transportStrategy;
			return this;
		}
		
		/**
		 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)}, {@link #withSMTPServerUsername(String)} and {@link
		 * #withSMTPServerPassword(String)}.
		 */
		public MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username, @Nullable final String password) {
			return withSMTPServerHost(host)
					.withSMTPServerPort(port)
					.withSMTPServerUsername(username)
					.withSMTPServerPassword(password);
		}
		
		/**
		 * Delegates to {@link #withSMTPServerHost(String)}, {@link #withSMTPServerPort(Integer)} and {@link #withSMTPServerUsername(String)}.
		 */
		public MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username) {
			return withSMTPServerHost(host)
					.withSMTPServerPort(port)
					.withSMTPServerUsername(username);
		}
		
		/**
		 * Delegates to {@link #withSMTPServerHost(String)} and {@link #withSMTPServerPort(Integer)}.
		 */
		public MailerRegularBuilder withSMTPServer(@Nullable final String host, @Nullable final Integer port) {
			return withSMTPServerHost(host)
					.withSMTPServerPort(port);
		}
		
		/**
		 * Sets the optional SMTP host. Will default to pre-configured property if left empty.
		 */
		public MailerRegularBuilder withSMTPServerHost(@Nullable final String host) {
			this.host = host;
			return this;
		}
		
		/**
		 * Sets the optional SMTP port. Will default to pre-configured property if left empty.
		 */
		public MailerRegularBuilder withSMTPServerPort(@Nullable final Integer port) {
			this.port = port;
			return this;
		}
		
		/**
		 * Sets the optional SMTP username. Will default to pre-configured property if left empty.
		 */
		public MailerRegularBuilder withSMTPServerUsername(@Nullable final String username) {
			this.username = username;
			return this;
		}
		
		/**
		 * Sets the optional SMTP password. Will default to pre-configured property if left empty.
		 */
		public MailerRegularBuilder withSMTPServerPassword(@Nullable final String password) {
			this.password = password;
			return this;
		}
		
		/**
		 * Builds the actual {@link Mailer} instance with everything configured on this builder instance.
		 * <p>
		 * For all configurable values: if omitted, a default value will be attempted by looking at property files or manually defined defauls.
		 */
		public Mailer buildMailer() {
			return new Mailer(this);
		}
		
		/**
		 * For internal use.
		 */
		ServerConfig buildServerConfig() {
			return new ServerConfig(getHost(), getPort(), getUsername(), getPassword());
		}
		
		public String getHost() {
			return host;
		}
		
		public Integer getPort() {
			return port;
		}
		
		public String getUsername() {
			return username;
		}
		
		public String getPassword() {
			return password;
		}
		
		public TransportStrategy getTransportStrategy() {
			return transportStrategy;
		}
	}
}