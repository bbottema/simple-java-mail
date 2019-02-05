package org.simplejavamail.mailer.internal.socks.socks5server;

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
}
