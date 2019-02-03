package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.mailer.AsyncResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Future;

/**
 * @see AsyncResponse
 */
public class AsyncResponseImpl implements AsyncResponse {
	
	@Nonnull private final Future<?> future;
	@Nullable private Runnable successHandler;
	@Nullable private ExceptionConsumer errorHandler;
	
	AsyncResponseImpl(@Nonnull Future<?> future) {
		this.future = future;
	}
	
	/**
	 * @see AsyncResponse#onSuccess(Runnable)
	 */
	@Override
	public void onSuccess(@Nonnull Runnable onSuccess) {
		this.successHandler = onSuccess;
	}
	
	/**
	 * @see AsyncResponse#onException(ExceptionConsumer)
	 */
	@Override
	public void onException(@Nonnull ExceptionConsumer errorHandler) {
		this.errorHandler = errorHandler;
	}
	
	
	void delegateSuccessHandling() {
		if (successHandler != null) {
			successHandler.run();
		}
	}
	
	void delegateExceptionHandling(Exception e) {
		if (errorHandler != null) {
			errorHandler.accept(e);
		}
	}
	
	/**
	 * @see AsyncResponse#getFuture()
	 */
	@Override
	@Nonnull
	public Future<?> getFuture() {
		return future;
	}
}