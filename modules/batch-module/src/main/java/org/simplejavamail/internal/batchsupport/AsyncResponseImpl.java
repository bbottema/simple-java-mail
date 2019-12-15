/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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