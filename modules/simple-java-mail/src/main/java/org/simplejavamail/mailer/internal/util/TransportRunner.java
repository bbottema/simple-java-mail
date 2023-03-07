package org.simplejavamail.mailer.internal.util;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.mailer.internal.SessionBasedEmailToMimeMessageConverter;
import org.slf4j.Logger;

import java.util.UUID;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * If available, runs activities on Transport connections using SMTP connection pool from the batch-module.
 * <p>
 * Otherwise, always creates a new connection to run the activity on.
 * <p>
 * <strong>Note</strong> that <a href="https://stackoverflow.com/a/12733317/441662">
 *     multiple threads can safely use a Session</a>, but are synchronized in the Transport connection.
 */
public class TransportRunner {

	private static final Logger LOGGER = getLogger(TransportRunner.class);

	/**
	 * NOTE: only in case batch-module is *not* in use, the {@link Session} passed in here is garuanteed to be used to send this message.
	 */
	public static void sendMessage(@NotNull final UUID clusterKey, final Session session, @NotNull Email email)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, false, (transport, actualSessionUsed) -> {
			val message = SessionBasedEmailToMimeMessageConverter.convertAndLogMimeMessage(actualSessionUsed, email);
			transport.sendMessage(message, message.getAllRecipients());
			LOGGER.trace("...email sent");
		});
	}

	public static void connect(@NotNull UUID clusterKey, final Session session)
			throws MessagingException {
		runOnSessionTransport(clusterKey, session, true, (transport, actualSessionUsed) -> {
			// the fact that we reached here means a connection was made successfully
			LOGGER.debug("...connection successful");
		});
	}

	private static void runOnSessionTransport(@NotNull UUID clusterKey, Session session, final boolean stickySession, TransportRunnable runnable)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			sendUsingConnectionPool(ModuleLoader.loadBatchModule(), clusterKey, session, stickySession, runnable);
		} else {
			try (Transport transport = session.getTransport()) {
				TransportConnectionHelper.connectTransport(transport, session);
				runnable.run(transport, session);
			} finally {
				LOGGER.trace("closing transport");
			}
		}
	}

	private static void sendUsingConnectionPool(@NotNull BatchModule batchModule, @NotNull UUID clusterKey, Session session, boolean stickySession, TransportRunnable runnable)
			throws MessagingException {
		LifecycleDelegatingTransport delegatingTransport = batchModule.acquireTransport(clusterKey, session, stickySession);
		try {
			runnable.run(delegatingTransport.getTransport(), delegatingTransport.getSessionUsedToObtainTransport());
		} catch (final Throwable t) {
			// always make sure claimed resources are released
			delegatingTransport.signalTransportFailed();
			throw t;
		}
		delegatingTransport.signalTransportUsed();
	}

	public interface TransportRunnable {
		void run(Transport transport, Session actualSessionUsed)
				throws MessagingException;
	}
}