package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;

/**
 * Simple Authenticator used to create a {@link Session} object with in {@link MailerImpl#createMailSession(ServerConfig, TransportStrategy)}.
 */
class SmtpAuthenticator extends Authenticator {
	private final ServerConfig serverConfig;

	SmtpAuthenticator(final ServerConfig serverConfig) {
		this.serverConfig = serverConfig;
	}

	@Override
	protected PasswordAuthentication getPasswordAuthentication() {
		return new PasswordAuthentication(serverConfig.getUsername(), serverConfig.getPassword());
	}
}
