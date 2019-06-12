package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.slf4j.Logger;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * If available, runs activities on Transport connections using SMTP connection pool from the batch-module.
 * <p>
 * Otherwise always creates a new connection to run the activity on.
 * <p>
 * <strong>Note</strong> that <a href="https://stackoverflow.com/a/12733317/441662">
 *     multiple threads can safely use a Session</a>, but are synchronized in the Transport connection.
 */
public class TransportRunner {

	private static final Logger LOGGER = getLogger(TransportRunner.class);

	public static void sendMessage(final Session session, final MimeMessage message, final Address[] allRecipients)
			throws MessagingException {
		runOnSessionTransport(session, new TransportRunnable() {
			@Override
			public void run(final Transport transport)
					throws MessagingException {
				transport.connect();
				transport.sendMessage(message, allRecipients);
				LOGGER.trace("...email sent");
			}
		});
	}

	public static void connect(final Session session)
			throws MessagingException {
		runOnSessionTransport(session, new TransportRunnable() {
			@Override
			public void run(final Transport transport)
					throws MessagingException {
				transport.connect();
				LOGGER.debug("...connection succesful");
			}
		});
	}

	private static void runOnSessionTransport(Session session, TransportRunnable runnable)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			LifecycleDelegatingTransport delegatingTransport = ModuleLoader.loadBatchModule().acquireTransport(session);
			try {
				runnable.run(delegatingTransport.getTransport());
			} catch (final MessagingException messagingException) {
				delegatingTransport.signalTransportFailed();
				throw messagingException;
			}
			delegatingTransport.signalTransportUsed();
		} else {
			try (Transport transport = session.getTransport()) {
				transport.connect();
				runnable.run(transport);
			} finally {
				LOGGER.trace("closing transport");
			}
		}
	}

	public interface TransportRunnable {
		void run(Transport transport)
				throws MessagingException;
	}
}