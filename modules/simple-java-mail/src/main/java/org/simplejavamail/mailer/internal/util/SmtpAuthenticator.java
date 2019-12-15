/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.MailerImpl;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;

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
