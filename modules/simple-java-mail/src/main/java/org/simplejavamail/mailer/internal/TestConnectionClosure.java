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
package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.SessionLogger;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.mail.MessagingException;
import javax.mail.Session;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extra closure for the actual connection test, so this can be called regularly as well as from async thread.
 */
class TestConnectionClosure extends AbstractProxyServerSyncingClosure {

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;
	private final boolean async;

	TestConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @Nullable final AnonymousSocks5Server proxyServer, final boolean async, @NotNull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.async = async;
	}

	@Override
	public void executeClosure() {
		LOGGER.debug("testing connection...");
		try {
			SessionLogger.logSession(session, async, "connection test");

			if (operationalConfig.getCustomMailer() != null) {
				operationalConfig.getCustomMailer().testConnection(operationalConfig, session);
			} else {
				TransportRunner.connect(operationalConfig.getClusterKey(), session);
			}
		} catch (final MessagingException e) {
			throw new MailerException(MailerException.ERROR_CONNECTING_SMTP_SERVER, e);
		} catch (final Exception e) {
			LOGGER.error("Failed to test connection email");
			throw e;
		}
	}
}
