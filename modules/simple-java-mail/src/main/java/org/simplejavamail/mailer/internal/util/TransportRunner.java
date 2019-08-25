package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.util.UUID;

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

	public static void sendMessage(@Nonnull final UUID clusterKey, final Session session, final MimeMessage message, final Address[] allRecipients)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, false, new TransportRunnable() {
			@Override
			public void run(final Transport transport)
					throws MessagingException {
				transport.sendMessage(message, allRecipients);
				LOGGER.trace("...email sent");
			}
		});
	}

	public static void connect(@Nonnull UUID clusterKey, @Nullable final Session session)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, true, new TransportRunnable() {
			@Override
			public void run(final Transport transport) {
				// the fact that we reached here means a connection was made succesfully
				LOGGER.debug("...connection succesful");
			}
		});
	}

	private static void runOnSessionTransport(@Nonnull UUID clusterKey, Session session, final boolean stickySession, TransportRunnable runnable)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			LifecycleDelegatingTransport delegatingTransport = ModuleLoader.loadBatchModule().acquireTransport(clusterKey, session, stickySession);
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