package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.SessionLogger;
import org.simplejavamail.mailer.internal.util.TransportRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Extra closure for the actual connection test, so this can be called regularly as well as from async thread.
 */
class TestConnectionClosure implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(TestConnectionClosure.class);

	@NotNull private final OperationalConfig operationalConfig;
	@NotNull private final Session session;

	TestConnectionClosure(@NotNull OperationalConfig operationalConfig, @NotNull Session session) {
		this.operationalConfig = operationalConfig;
		this.session = session;
	}

	@Override
	public void run() {
		LOGGER.debug("testing connection...");
		try {
			SessionLogger.logSession(session, "connection test");

			if (operationalConfig.getCustomMailer() != null) {
				operationalConfig.getCustomMailer().testConnection(operationalConfig, session);
			} else {
				TransportRunner.connect(operationalConfig.getClusterKey(), session);
			}
		} catch (final MessagingException e) {
			throw new MailerException(MailFailureKind.CONNECTION, MailerException.ERROR_CONNECTING_SMTP_SERVER, e);
		}
	}
}
