package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.mailer.AsyncResponse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Future;

public class AsyncResponseImpl implements AsyncResponse {
	
	@Nonnull private final Future<?> future;
	@Nullable private Runnable successHandler;
	@Nullable private ExceptionConsumer errorHandler;
	
	AsyncResponseImpl(Future<?> future) {
		this.future = future;
	}
	
	@Override
	public void onSuccess(@Nonnull Runnable onSuccess) {
		this.successHandler = onSuccess;
	}
	
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
	
	@Override
	public Future<?> getFuture() {
		return future;
	}
}