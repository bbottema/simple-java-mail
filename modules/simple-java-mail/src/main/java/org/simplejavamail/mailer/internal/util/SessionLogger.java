package org.simplejavamail.mailer.internal.util;

import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.slf4j.Logger;

import javax.mail.Session;
import java.util.Properties;

import static org.slf4j.LoggerFactory.getLogger;

public class SessionLogger {

	private static final Logger LOGGER = getLogger(SessionLogger.class);

	/**
	 * Simply logs host details, credentials used and whether authentication will take place and finally the transport protocol used.
	 */
	public static void logSession(final Session session, boolean async, final String activity) {
		final TransportStrategy transportStrategy = TransportStrategy.findStrategyForSession(session);
		final Properties properties = session.getProperties();
		final String sessionDetails = (transportStrategy != null) ? transportStrategy.toString(properties) : properties.toString();
		LOGGER.debug("starting{} {} with {}", async ? " async" : "", activity, sessionDetails);
	}
}
