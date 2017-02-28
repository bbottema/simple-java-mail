package org.simplejavamail.springsupport;

import org.simplejavamail.mailer.Mailer;
import org.simplejavamail.util.ConfigLoader;
import org.simplejavamail.util.ConfigLoader.Property;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class SimpleJavaMailSpringSupport {

	@Bean
	public Mailer loadGlobalConfigAndCreateDefaultMailer(
			// now obviously there are easier ways to do this, but this is the only way
			// I can think of that actually works accross Spring versions
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
			@Value("${simplejavamail.defaults.to.name:#{null}}") final String defaultToName,
			@Value("${simplejavamail.defaults.to.address:#{null}}") final String defaultToAddress,
			@Value("${simplejavamail.defaults.cc.name:#{null}}") final String defaultCcName,
			@Value("${simplejavamail.defaults.cc.address:#{null}}") final String defaultCcAddress,
			@Value("${simplejavamail.defaults.bcc.name:#{null}}") final String defaultBccName,
			@Value("${simplejavamail.defaults.bcc.address:#{null}}") final String defaultBccAddress,
			@Value("${simplejavamail.defaults.poolsize:#{null}}") final String defaultPoolsize,
			@Value("${simplejavamail.transport.mode.logging.only:#{null}}") final String defaultTransportModeLoggingOnly) {
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
		setNullableProperty(emailProperties, Property.DEFAULT_TO_NAME.key(), defaultToName);
		setNullableProperty(emailProperties, Property.DEFAULT_TO_ADDRESS.key(), defaultToAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_NAME.key(), defaultCcName);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_ADDRESS.key(), defaultCcAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_NAME.key(), defaultBccName);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_ADDRESS.key(), defaultBccAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_POOL_SIZE.key(), defaultPoolsize);
		setNullableProperty(emailProperties, Property.TRANSPORT_MODE_LOGGING_ONLY.key(), defaultTransportModeLoggingOnly);

		ConfigLoader.loadProperties(emailProperties, true);

		// this will configure itself with the global config and is read to use
		// ofcourse this is optional simply as a convenience default
		return new Mailer();
	}

	private static void setNullableProperty(final Properties emailProperties, final String key, final String value) {
		if (value != null) {
			emailProperties.setProperty(key, value);
		}
	}
}
