package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.internal.modules.ModuleLoader;
import org.slf4j.Logger;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;

import static org.slf4j.LoggerFactory.getLogger;

// FIXME actually use this somewhere
public class TransportRunner {

	private static final Logger LOGGER = getLogger(TransportRunner.class);

	public static void runOnSessionTransport(Session session, TransportRunnable runnable)
			throws MessagingException {
		if (ModuleLoader.batchModuleAvailable()) {
			LifecycleDelegatingTransport delegatingTransport = ModuleLoader.loadBatchModule().acquireTransport(session);
			runnable.run(delegatingTransport.getTransport());
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
		void run(Transport transport);
	}
}
