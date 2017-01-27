package org.simplejavamail.util;

import org.simplejavamail.mailer.Mailer;
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
			@Value("${simplejavamail.javaxmail.debug:#{null}}") String javaxmailDebug,
			@Value("${simplejavamail.transportstrategy:#{null}}") String transportstrategy,
			@Value("${simplejavamail.smtp.host:#{null}}") String smtpHost,
			@Value("${simplejavamail.smtp.port:#{null}}") String smtpPort,
			@Value("${simplejavamail.smtp.username:#{null}}") String smtpUsername,
			@Value("${simplejavamail.smtp.password:#{null}}") String smtpPassword,
			@Value("${simplejavamail.proxy.host:#{null}}") String proxyHost,
			@Value("${simplejavamail.proxy.port:#{null}}") String proxyPort,
			@Value("${simplejavamail.proxy.username:#{null}}") String proxyUsername,
			@Value("${simplejavamail.proxy.password:#{null}}") String proxyPassword,
			@Value("${simplejavamail.proxy.socks5bridge.port:#{null}}") String proxySocks5bridgePort,
			@Value("${simplejavamail.defaults.subject:#{null}}") String defaultSubject,
			@Value("${simplejavamail.defaults.from.name:#{null}}") String defaultFromName,
			@Value("${simplejavamail.defaults.from.address:#{null}}") String defaultFromAddress,
			@Value("${simplejavamail.defaults.replyto.name:#{null}}") String defaultReplytoName,
			@Value("${simplejavamail.defaults.replyto.address:#{null}}") String defaultReplytoAddress,
			@Value("${simplejavamail.defaults.to.name:#{null}}") String defaultToName,
			@Value("${simplejavamail.defaults.to.address:#{null}}") String defaultToAddress,
			@Value("${simplejavamail.defaults.cc.name:#{null}}") String defaultCcName,
			@Value("${simplejavamail.defaults.cc.address:#{null}}") String defaultCcAddress,
			@Value("${simplejavamail.defaults.bcc.name:#{null}}") String defaultBccName,
			@Value("${simplejavamail.defaults.bcc.address:#{null}}") String defaultBccAddress,
			@Value("${simplejavamail.defaults.poolsize:#{null}}") String defaultPoolsize) {
		Properties emailProperties = new Properties();
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

		ConfigLoader.loadProperties(emailProperties, true);

		// this will configure itself with the global config and is read to use
		// ofcourse this is optional simply as a convenience default
		return new Mailer();
	}

	private void setNullableProperty(Properties emailProperties, String key, String value) {
		if (value != null) {
			emailProperties.setProperty(key, value);
		}
	}
}
