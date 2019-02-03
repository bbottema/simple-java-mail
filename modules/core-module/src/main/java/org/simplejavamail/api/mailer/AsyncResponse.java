package org.simplejavamail.api.mailer;

import java.util.concurrent.Future;

/**
 * Useful when sending mails or testing connections asynchronously. <br>
 * Provides both a {@link Future} as well as support for direct callbacks.
 *
 * @see "The async demo's code in TestConnectionDemo"
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
	void onException(ExceptionConsumer errorHandler);
	
	/**
	 * @return A {@link Future} which can be monitored while the originating activity resolves over time.
	 */
	Future<?> getFuture();
	
	/**
	 * Simplified version of Java 8's Consumer, so you can easily implement exception handlers in {@code Java <= 7}.
	 */
	interface ExceptionConsumer {
		void accept(Exception t);
	}
}
