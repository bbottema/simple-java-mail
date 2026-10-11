package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import javax.net.ssl.SSLSocketFactory;

public interface ServerConfig {
	/** @see MailerRegularBuilder#withSMTPServerHost(String) */
	@NotNull String getHost();
	/** @see MailerRegularBuilder#withSMTPServerPort(Integer) */
	@NotNull Integer getPort();
	/** @see MailerRegularBuilder#withSMTPServerUsername(String) */
	@Nullable String getUsername();
	/** @see MailerRegularBuilder#withSMTPServerPassword(String) */
	@Nullable String getPassword();
	/** @see MailerRegularBuilder#withCustomSSLFactoryClass(String) */
	@Nullable String getCustomSSLFactoryClass();
	/** @see MailerRegularBuilder#withCustomSSLFactoryInstance(SSLSocketFactory) */
	@Nullable SSLSocketFactory getCustomSSLFactoryInstance();
}
