package org.simplejavamail.internal.batchsupport;

import org.simplejavamail.api.mailer.AsyncResponse;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.Future;

/**
 * @see AsyncResponse
 */
public class AsyncResponseImpl implements AsyncResponse {
	
	@NotNull private final Future<?> future;
	@Nullable private Runnable successHandler;
	@Nullable private ExceptionConsumer errorHandler;
	
	AsyncResponseImpl(@NotNull Future<?> future) {
		this.future = future;
	}
	
	/**
	 * @see AsyncResponse#onSuccess(Runnable)
	 */
	@Override
	public void onSuccess(@NotNull Runnable onSuccess) {
		this.successHandler = onSuccess;
	}
	
	/**
	 * @see AsyncResponse#onException(ExceptionConsumer)
	 */
	@Override
	public void onException(@NotNull ExceptionConsumer errorHandler) {
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
	@NotNull
	public Future<?> getFuture() {
		return future;
	}
}