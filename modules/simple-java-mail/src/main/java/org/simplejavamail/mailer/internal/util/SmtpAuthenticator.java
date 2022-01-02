package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.MailerImpl;

/**
 * Simple Authenticator used to create a {@link Session} object with in {@link MailerImpl#createMailSession(ServerConfig, TransportStrategy)}.
 */
public class SmtpAuthenticator extends Authenticator {
	private final ServerConfig serverConfig;

	public SmtpAuthenticator(final ServerConfig serverConfig) {
		this.serverConfig = serverConfig;
	}

	@Override
	protected PasswordAuthentication getPasswordAuthentication() {
		return new PasswordAuthentication(serverConfig.getUsername(), serverConfig.getPassword());
	}
}
