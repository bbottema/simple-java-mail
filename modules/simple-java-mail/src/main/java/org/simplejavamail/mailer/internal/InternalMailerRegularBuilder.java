package org.simplejavamail.mailer.internal;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import javax.net.ssl.SSLSocketFactory;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
/** Internal scalar runtime bridge; application callers use whole configuration groups. */
@ApiStatus.Internal
public interface InternalMailerRegularBuilder<T extends MailerRegularBuilder<?>> extends MailerRegularBuilder<T> {
	/**
	 * Controls whether plain {@link TransportStrategy#SMTP} attempts an optional STARTTLS upgrade when the server offers it.
	 * It defaults to {@value MailerGenericBuilder#DEFAULT_OPPORTUNISTIC_TLS};
	 * setting it to {@code false} is an escape hatch for SMTP servers that break during STARTTLS negotiation.
	 * <p>
	 * A configured snapshot supplies the initial value when this builder is created. Calling this method afterwards overrides that value for the Mailer
	 * built from this builder.
	 * <p>
	 * This setting is deliberately limited to plain SMTP. It has no effect on mandatory TLS in {@link TransportStrategy#SMTP_TLS}, OAuth2 transport, or
	 * implicit TLS in {@link TransportStrategy#SMTPS}.
	 * {@link #resetOpportunisticTLS()} restores the built-in default rather than the captured property; configuration locks still apply.
	 *
	 * @param opportunisticTLS Whether plain SMTP should attempt STARTTLS when offered.
	 */
	T withOpportunisticTLS(boolean opportunisticTLS);
	/** @see #withOpportunisticTLS(boolean) */
	boolean isOpportunisticTLS();
	/**
	 * Restores the built-in opportunistic TLS setting rather than the captured property.
	 * @see #withOpportunisticTLS(boolean)
	 */
	T resetOpportunisticTLS();
	/**
	 * Uses your SSL socket factory class, for example to supply a client certificate or a private trust store.
	 * <p>
	 * A supplied factory instance takes precedence. The class needs a public static {@code getDefault()} method returning an
	 * {@link SSLSocketFactory}; initialization stays lazy until the factory is used. For a modular application using the managed Angus
	 * provider, export its package to {@code org.simplejavamail.mailprovider.angus}, or supply an instance instead.
	 * <p>
	 * On an owned Angus Session, a failure in your factory stops connection setup by default instead of retrying with the default factory.
	 * An explicit {@code mail.smtp.socketFactory.fallback=true} (or the {@code mail.smtps} equivalent) retains that advanced behavior,
	 * but can abandon your factory's client identity or trust decisions. Failed TLS wrapping closes its connected socket before cleanup or fallback.
	 * This does not add physical cancellation support to a custom factory. Caller-owned Sessions keep their own settings.
	 * <p>
	 * Sets {@code mail.smtp.ssl.socketFactory.class} or {@code mail.smtps.ssl.socketFactory.class}. A custom factory can bypass the provider's
	 * SOCKS socket creation, so check authenticated-proxy usage with your factory.
	 * <p>
	 * Passing null or calling {@link #clearCustomSSLFactoryClass()} removes only the stored class name; a supplied instance stays in place.
	 * Clearing does not restore a captured property value, and configuration locks still apply.
	 *
	 * @param factoryClass The fully qualified name of the factory class. Example: <code>javax.net.ssl.SSLSocketFactory</code>
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	T withCustomSSLFactoryClass(@Nullable @Cli.Optional String factoryClass);
	/**
	 * Removes only the stored SSL factory class name.
	 * @see #withCustomSSLFactoryClass(String)
	 */
	T clearCustomSSLFactoryClass();
	/**
	 * Uses your SSL socket factory instance, for example to supply a client certificate or a private trust store.
	 * <p>
	 * Takes precedence over a configured factory class. No reflective module access to your implementation package is needed.
	 * For an owned Angus Session, omitted fallback is disabled: failure in your factory stops connection setup rather than silently
	 * trying the default factory. Explicit transport-specific {@code socketFactory.fallback=true} remains available for deliberate fallback.
	 * Failed TLS wrapping closes the connected socket, retaining the factory failure and suppressing any secondary close failure.
	 * The owned Session holds a cleanup decorator; keep your original reference for implementation-specific factory operations.
	 * <p>
	 * Sets {@code mail.smtp.ssl.socketFactory} or {@code mail.smtps.ssl.socketFactory}. A custom factory can bypass the provider's SOCKS
	 * socket creation, so check authenticated-proxy usage with your factory. This does not add physical cancellation support.
	 * Caller-owned Sessions keep their own settings.
	 * <p>
	 * Passing null or calling {@link #clearCustomSSLFactoryInstance()} removes this instance so a configured factory class can be used again.
	 * This never closes the supplied factory or removes the stored class name.
	 *
	 * @param sslSocketFactoryInstance An instance of the {@link SSLSocketFactory} class.
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html">Java / Jakarta Mail properties</a>
	 * @see #withCustomSSLFactoryClass(String)
	 */
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	T withCustomSSLFactoryInstance(@Nullable @Cli.Optional SSLSocketFactory sslSocketFactoryInstance);
	/**
	 * Removes only the supplied SSL factory instance.
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	@Cli.ExcludeApi(reason = "Factory instances are configured only through Java")
	T clearCustomSSLFactoryInstance();
	/**
	 * Returns the stored factory class name, even when a supplied instance takes precedence.
	 * @see #withCustomSSLFactoryClass(String)
	 */
	@Nullable
	String getCustomSSLFactory();
	/**
	 * Returns the supplied factory instance itself, or null when none is configured.
	 * @see #withCustomSSLFactoryInstance(SSLSocketFactory)
	 */
	@Nullable
	SSLSocketFactory getCustomSSLFactoryInstance();
}
