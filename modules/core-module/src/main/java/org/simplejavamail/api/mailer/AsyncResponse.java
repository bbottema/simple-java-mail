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
package org.simplejavamail.api.mailer;

import java.util.concurrent.Future;

/**
 * Used when sending mails or testing connections asynchronously.
 * <p>
 * Provides both a {@link Future} as well as support for direct callbacks.
 *
 * @see "The async demo's code in TestConnectionDemo"
 * @see MailerGenericBuilder#async()
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
