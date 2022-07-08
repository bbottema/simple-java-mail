/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.util.concurrent;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

/**
 * Util that facilitates running a concurrent operation with CompletableFuture support.
 */
@SuppressWarnings("SameParameterValue")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AsyncOperationHelper {

	/**
	 * Executes using a single-execution ExecutorService, which is shutdown immediately after the operation finishes.
	 *
	 * @see Executors#newSingleThreadExecutor()
	 */
	public static CompletableFuture<Void> executeAsync(final @NotNull String processName, final @NotNull Runnable operation) {
		final ExecutorService executorService = newSingleThreadExecutor();
		return runAsync(new NamedRunnable(processName, operation), executorService)
				.thenRun(executorService::shutdown);
	}
	
	/**
	 * Executes using the given ExecutorService, which is left running after the thread finishes running.
	 */
	public static CompletableFuture<Void> executeAsync(final @NotNull ExecutorService executorService, final @NotNull String processName, final @NotNull Runnable operation) {
		assumeTrue(!executorService.isShutdown(), "cannot send async email, executor service is already shut down!");
		return runAsync(new NamedRunnable(processName, operation), executorService);
	}
}