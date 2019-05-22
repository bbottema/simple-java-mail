package org.simplejavamail.springsupport;

import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.internal.MailerImpl;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * Provides a {@link MailerImpl} bean completely configured from property defaults. For this to work, you need to include default properties in your
 * Spring config.
 * <p>
 * Using profiles, you can have environment specific configurations that way. See
 * <a href="http://www.simplejavamail.org/#/configuration">simplejavamail.org</a> for example configuration.
 * <p>
 * The following properties will be applied
 * <ul>
 * <li>simplejavamail.javaxmail.debug</li>
 * <li>simplejavamail.transportstrategy</li>
 * <li>simplejavamail.smtp.host</li>
 * <li>simplejavamail.smtp.port</li>
 * <li>simplejavamail.smtp.username</li>
 * <li>simplejavamail.smtp.password</li>
 * <li>simplejavamail.proxy.host</li>
 * <li>simplejavamail.proxy.port</li>
 * <li>simplejavamail.proxy.username</li>
 * <li>simplejavamail.proxy.password</li>
 * <li>simplejavamail.proxy.socks5bridge.port</li>
 * <li>simplejavamail.defaults.subject</li>
 * <li>simplejavamail.defaults.from.name</li>
 * <li>simplejavamail.defaults.from.address</li>
 * <li>simplejavamail.defaults.replyto.name</li>
 * <li>simplejavamail.defaults.replyto.address</li>
 * <li>simplejavamail.defaults.bounceto.name</li>
 * <li>simplejavamail.defaults.bounceto.address</li>
 * <li>simplejavamail.defaults.to.name</li>
 * <li>simplejavamail.defaults.to.address</li>
 * <li>simplejavamail.defaults.cc.name</li>
 * <li>simplejavamail.defaults.cc.address</li>
 * <li>simplejavamail.defaults.bcc.name</li>
 * <li>simplejavamail.defaults.bcc.address</li>
 * <li>simplejavamail.defaults.poolsize</li>
 * <li>simplejavamail.defaults.poolsize.keepalivetime</li>
 * <li>simplejavamail.defaults.sessiontimeoutmillis</li>
 * <li>simplejavamail.transport.mode.logging.only</li>
 * <li>simplejavamail.opportunistic.tls</li>
 * <li>simplejavamail.smime.signing.keystore</li>
 * <li>simplejavamail.smime.signing.keystore_password</li>
 * <li>simplejavamail.smime.signing.key_alias</li>
 * <li>simplejavamail.smime.signing.key_password</li>
 * <li>simplejavamail.smime.encryption.certificate</li>
 * </ul>
 */
@Configuration
public class SimpleJavaMailSpringSupport {

	@Bean
	public Mailer loadGlobalConfigAndCreateDefaultMailer(
			// now obviously there are easier ways to do this, but this is the only way
			// I can think of that actually works across Spring versions
			@Value("${simplejavamail.javaxmail.debug:#{null}}") final String javaxmailDebug,
			@Value("${simplejavamail.transportstrategy:#{null}}") final String transportstrategy,
			@Value("${simplejavamail.smtp.host:#{null}}") final String smtpHost,
			@Value("${simplejavamail.smtp.port:#{null}}") final String smtpPort,
			@Value("${simplejavamail.smtp.username:#{null}}") final String smtpUsername,
			@Value("${simplejavamail.smtp.password:#{null}}") final String smtpPassword,
			@Value("${simplejavamail.proxy.host:#{null}}") final String proxyHost,
			@Value("${simplejavamail.proxy.port:#{null}}") final String proxyPort,
			@Value("${simplejavamail.proxy.username:#{null}}") final String proxyUsername,
			@Value("${simplejavamail.proxy.password:#{null}}") final String proxyPassword,
			@Value("${simplejavamail.proxy.socks5bridge.port:#{null}}") final String proxySocks5bridgePort,
			@Value("${simplejavamail.defaults.subject:#{null}}") final String defaultSubject,
			@Value("${simplejavamail.defaults.from.name:#{null}}") final String defaultFromName,
			@Value("${simplejavamail.defaults.from.address:#{null}}") final String defaultFromAddress,
			@Value("${simplejavamail.defaults.replyto.name:#{null}}") final String defaultReplytoName,
			@Value("${simplejavamail.defaults.replyto.address:#{null}}") final String defaultReplytoAddress,
			@Value("${simplejavamail.defaults.bounceto.name:#{null}}") final String defaultBouncetoName,
			@Value("${simplejavamail.defaults.bounceto.address:#{null}}") final String defaultBouncetoAddress,
			@Value("${simplejavamail.defaults.to.name:#{null}}") final String defaultToName,
			@Value("${simplejavamail.defaults.to.address:#{null}}") final String defaultToAddress,
			@Value("${simplejavamail.defaults.cc.name:#{null}}") final String defaultCcName,
			@Value("${simplejavamail.defaults.cc.address:#{null}}") final String defaultCcAddress,
			@Value("${simplejavamail.defaults.bcc.name:#{null}}") final String defaultBccName,
			@Value("${simplejavamail.defaults.bcc.address:#{null}}") final String defaultBccAddress,
			@Value("${simplejavamail.defaults.poolsize:#{null}}") final String defaultPoolsize,
			@Value("${simplejavamail.defaults.poolsize.keepalive:#{null}}") final String defaultPoolKeepAlivetime,
			@Value("${simplejavamail.defaults.sessiontimeoutmillis:#{null}}") final String defaultSessionTimeoutMillis,
			@Value("${simplejavamail.transport.mode.logging.only:#{null}}") final String defaultTransportModeLoggingOnly,
			@Value("${simplejavamail.opportunistic.tls:#{null}}") final String defaultOpportunisticTls,
			@Value("${simplejavamail.smime.signing.keystore:#{null}}") final String smimeSigningKeyStore,
			@Value("${simplejavamail.smime.signing.keystore_password:#{null}}") final String smimeSigningKeyStorePassword,
			@Value("${simplejavamail.smime.signing.key_alias:#{null}}") final String smimeSigningKeyAlias,
			@Value("${simplejavamail.smime.signing.key_password:#{null}}") final String smimeSigningKeyPassword,
			@Value("${simplejavamail.smime.encryption.certificate:#{null}}") final String smimeEncryptionCertificate) {
		final Properties emailProperties = new Properties();
		setNullableProperty(emailProperties, Property.JAVAXMAIL_DEBUG.key(), javaxmailDebug);
		setNullableProperty(emailProperties, Property.TRANSPORT_STRATEGY.key(), transportstrategy);
		setNullableProperty(emailProperties, Property.SMTP_HOST.key(), smtpHost);
		setNullableProperty(emailProperties, Property.SMTP_PORT.key(), smtpPort);
		setNullableProperty(emailProperties, Property.SMTP_USERNAME.key(), smtpUsername);
		setNullableProperty(emailProperties, Property.SMTP_PASSWORD.key(), smtpPassword);
		setNullableProperty(emailProperties, Property.PROXY_HOST.key(), proxyHost);
		setNullableProperty(emailProperties, Property.PROXY_PORT.key(), proxyPort);
		setNullableProperty(emailProperties, Property.PROXY_USERNAME.key(), proxyUsername);
		setNullableProperty(emailProperties, Property.PROXY_PASSWORD.key(), proxyPassword);
		setNullableProperty(emailProperties, Property.PROXY_SOCKS5BRIDGE_PORT.key(), proxySocks5bridgePort);
		setNullableProperty(emailProperties, Property.DEFAULT_SUBJECT.key(), defaultSubject);
		setNullableProperty(emailProperties, Property.DEFAULT_FROM_NAME.key(), defaultFromName);
		setNullableProperty(emailProperties, Property.DEFAULT_FROM_ADDRESS.key(), defaultFromAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_REPLYTO_NAME.key(), defaultReplytoName);
		setNullableProperty(emailProperties, Property.DEFAULT_REPLYTO_ADDRESS.key(), defaultReplytoAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_BOUNCETO_NAME.key(), defaultBouncetoName);
		setNullableProperty(emailProperties, Property.DEFAULT_BOUNCETO_ADDRESS.key(), defaultBouncetoAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_TO_NAME.key(), defaultToName);
		setNullableProperty(emailProperties, Property.DEFAULT_TO_ADDRESS.key(), defaultToAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_NAME.key(), defaultCcName);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_ADDRESS.key(), defaultCcAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_NAME.key(), defaultBccName);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_ADDRESS.key(), defaultBccAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_POOL_SIZE.key(), defaultPoolsize);
		setNullableProperty(emailProperties, Property.DEFAULT_POOL_KEEP_ALIVE_TIME.key(), defaultPoolKeepAlivetime);
		setNullableProperty(emailProperties, Property.DEFAULT_SESSION_TIMEOUT_MILLIS.key(), defaultSessionTimeoutMillis);
		setNullableProperty(emailProperties, Property.TRANSPORT_MODE_LOGGING_ONLY.key(), defaultTransportModeLoggingOnly);
		setNullableProperty(emailProperties, Property.OPPORTUNISTIC_TLS.key(), defaultOpportunisticTls);
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEYSTORE.key(), smimeSigningKeyStore);
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEYSTORE_PASSWORD.key(), smimeSigningKeyStorePassword);
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_ALIAS.key(), smimeSigningKeyAlias);
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_PASSWORD.key(), smimeSigningKeyPassword);
		setNullableProperty(emailProperties, Property.SMIME_ENCRYPTION_CERTIFICATE.key(), smimeEncryptionCertificate);

		ConfigLoader.loadProperties(emailProperties, true);

		// This will configure itself with the global config and is ready to use.
		// Ofcourse this is optional simply as a convenience default.
		return MailerBuilder.buildMailer();
	}

	private static void setNullableProperty(final Properties emailProperties, final String key, final String value) {
		if (value != null) {
			emailProperties.setProperty(key, value);
		}
	}
}