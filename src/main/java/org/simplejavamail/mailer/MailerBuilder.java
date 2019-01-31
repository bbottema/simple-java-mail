package org.simplejavamail.mailer;

import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.MailerFromSessionBuilderImpl;
import org.simplejavamail.mailer.internal.MailerGenericBuilderImpl;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;

/**
 * Entry builder used to start a {@link MailerGenericBuilderImpl} and fully configure a Mailer.
 * <p>
 * Any of these methods return a specialized builder, of which there are two:
 * <ul>
 * <li>One to configure a Mailer using a custom {@link Session} instance</li>
 * <li>One to fully configure a Mailer which will produce its own {@link Session} instance</li>
 * </ul>
 *
 * @see MailerFromSessionBuilderImpl
 * @see MailerRegularBuilderImpl
 */
@SuppressWarnings("WeakerAccess")
public class MailerBuilder {
	
	/**
	 * Delegates to {@link MailerFromSessionBuilderImpl#usingSession(Session)}.
	 */
	@SuppressWarnings("deprecation")
	public static MailerFromSessionBuilderImpl usingSession(@Nonnull final Session session) {
		return new MailerFromSessionBuilderImpl().usingSession(session);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withTransportStrategy(TransportStrategy)}.
	 */
	@SuppressWarnings("deprecation")
	public static MailerRegularBuilderImpl withTransportStrategy(@Nonnull final TransportStrategy transportStrategy) {
		return new MailerRegularBuilderImpl().withTransportStrategy(transportStrategy);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer, String, String)}.
	 */
	@SuppressWarnings("deprecation")
	public static MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username, @Nullable final String password) {
		return new MailerRegularBuilderImpl().withSMTPServer(host, port, username, password);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer, String)}.
	 */
	@SuppressWarnings({"unused", "deprecation"})
	public static MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port, @Nullable final String username) {
		return new MailerRegularBuilderImpl().withSMTPServer(host, port, username);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServer(String, Integer)}.
	 */
	@SuppressWarnings("deprecation")
	public static MailerRegularBuilderImpl withSMTPServer(@Nullable final String host, @Nullable final Integer port) {
		return new MailerRegularBuilderImpl().withSMTPServer(host, port);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerHost(String)}.
	 */
	@SuppressWarnings("deprecation")
	public static MailerRegularBuilderImpl withSMTPServerHost(@Nullable final String host) {
		return new MailerRegularBuilderImpl().withSMTPServerHost(host);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerPort(Integer)}.
	 */
	@SuppressWarnings({"unused", "deprecation"})
	public static MailerRegularBuilderImpl withSMTPServerPort(@Nullable final Integer port) {
		return new MailerRegularBuilderImpl().withSMTPServerPort(port);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerUsername(String)}.
	 */
	@SuppressWarnings({"unused", "deprecation"})
	public static MailerRegularBuilderImpl withSMTPServerUsername(@Nullable final String username) {
		return new MailerRegularBuilderImpl().withSMTPServerUsername(username);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withSMTPServerPassword(String)}.
	 */
	@SuppressWarnings({"unused", "deprecation"})
	public static MailerRegularBuilderImpl withSMTPServerPassword(@Nullable final String password) {
		return new MailerRegularBuilderImpl().withSMTPServerPassword(password);
	}
	
	/**
	 * Delegates to {@link MailerRegularBuilder#withDebugLogging(Boolean)}
	 * <p>
	 * <strong>Note:</strong> Assumes you don't want to use your own {@link Session} object (otherwise start with {@link #usingSession(Session)}
	 * instead).
	 */
	@SuppressWarnings({"unused", "deprecation"})
	public static MailerRegularBuilderImpl withDebugLogging(Boolean debugLogging) {
		return new MailerRegularBuilderImpl().withDebugLogging(debugLogging);
	}
	
	/**
	 * Shortcuts to {@link MailerRegularBuilder#buildMailer()}. This means that none of the builder methods are used and the configuration completely
	 * depends on defaults being configured from property file ("simplejavamail.properties") on the classpath or through programmatic defaults.
	 */
	@SuppressWarnings("deprecation")
	public static Mailer buildMailer() {
		return new MailerRegularBuilderImpl().buildMailer();
	}
	
	private MailerBuilder() {
	}
	
}