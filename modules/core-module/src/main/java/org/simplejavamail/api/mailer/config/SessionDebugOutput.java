package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.MailerGenericBuilder;

/**
 * Built-in targets for Jakarta Mail {@link jakarta.mail.Session} debug output.
 *
 * @see SessionDebugConfig.Builder#withOutput(SessionDebugOutput)
 * @see SessionDebugConfig.Builder#withPrinter(java.io.PrintStream)
 */
public enum SessionDebugOutput {
	/**
	 * Send Jakarta Mail debug output to {@link System#out}.
	 */
	STDOUT,

	/**
	 * Send Jakarta Mail debug output to {@link System#err}.
	 */
	STDERR,

	/**
	 * Send Jakarta Mail debug output to the {@code org.simplejavamail.javamail.debug} SLF4J logger.
	 */
	SLF4J
}
