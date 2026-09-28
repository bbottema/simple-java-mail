package org.simplejavamail.internal.util;

import jakarta.mail.Session;

import java.util.Properties;
import java.util.ServiceConfigurationError;

/** Creates conversion Sessions with an actionable diagnostic when Jakarta Mail cannot find its runtime implementation. */
public final class JakartaMailImplementation {

	private static final String MISSING_IMPLEMENTATION =
			"Simple Java Mail needs a Jakarta Mail implementation for MIME conversion and sending. "
					+ "Keep the default org.simplejavamail:angus-mail-provider-module runtime or add another compatible implementation";

	private JakartaMailImplementation() {
	}

	public static Session createSession(final Properties properties) {
		try {
			// Session already resolves and retains its StreamProvider. A separate availability lookup would repeat that discovery.
			return Session.getInstance(properties);
		} catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
			throw new IllegalStateException(MISSING_IMPLEMENTATION, e);
		}
	}
}
