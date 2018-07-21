package org.simplejavamail.mailer;

import org.simplejavamail.mailer.internal.mailsender.AsyncResponseImpl;

import java.util.concurrent.Future;

/**
 * Useful when sending mails or testing connections asynchronously. Provides both a {@link Future} as well as support for direct callbacks.
 */
public interface AsyncResponse {
	
	/**
	 * Mimicks promise behavior in a very limited way.
	 *
	 * @param onSuccess Why Runnable? Refer to <a href="https://stackoverflow.com/a/45499848/441662.">this answer</a>
	 */
	void onSuccess(Runnable onSuccess);
	
	/**
	 * Mimicks promise behavior in a very limited way.
	 */
	void onException(AsyncResponseImpl.ExceptionConsumer errorHandler);
	
	Future getFuture();
	
	// simplified version of Java 8's Consumer
	interface ExceptionConsumer {
		void accept(Exception t);
	}
}
