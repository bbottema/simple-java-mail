package org.simplejavamail.api.internal.authenticatedsockssupport.socks5server;

import org.simplejavamail.api.mailer.MailSubmissionReceipt;

/**
 * SOCKS server that accepts anonymous connections from JavaMail.
 * <p>
 * Java Mail only support anonymous SOCKS proxies; in order to support authenticated proxies, we need to create a man-in-the-middle: which is the
 * {@link AnonymousSocks5Server}.
 */
public interface AnonymousSocks5Server extends Runnable {
	/**
	 * Binds the port and starts a thread to listen to incoming proxy connections from JavaMail.
	 */
	void start();
	
	void stop();
	
	boolean isStopping();
	
	boolean isRunning();

	/**
	 * Returns the port number on which this server is listening.
	 *
	 * @return  the port number to which this server is listening or
	 *          -1 if the server is not started yet.
	 */
	int getLocalPort();

	/** Retains known logical-send facts on this module's startup failures; unrelated failures remain unchanged. */
	default RuntimeException retainSubmissionReceipt(final RuntimeException failure, final MailSubmissionReceipt receipt) { return failure; }
}
