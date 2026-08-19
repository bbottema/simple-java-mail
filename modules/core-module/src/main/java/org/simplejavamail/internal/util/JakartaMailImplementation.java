package org.simplejavamail.internal.util;

import jakarta.mail.util.StreamProvider;

import java.util.ServiceConfigurationError;

/** Verifies that the Jakarta Mail API has a runtime implementation before MIME work starts. */
public final class JakartaMailImplementation {

	private static final String MISSING_IMPLEMENTATION =
			"Simple Java Mail needs a Jakarta Mail implementation for MIME conversion and sending. "
					+ "Keep the default org.simplejavamail:angus-mail-provider-module runtime or add another compatible implementation";

	private JakartaMailImplementation() {
	}

	public static void requireAvailable() {
		try {
			StreamProvider.provider();
		} catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
			throw new IllegalStateException(MISSING_IMPLEMENTATION, e);
		}
	}
}
