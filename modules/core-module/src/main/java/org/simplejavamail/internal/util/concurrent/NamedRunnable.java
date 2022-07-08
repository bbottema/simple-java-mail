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

import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;


/**
 * This Runnable is smart in the sense that it can shutdown the
 */
@RequiredArgsConstructor
public class NamedRunnable implements Runnable {

	private static final Logger LOGGER = getLogger(NamedRunnable.class);

	@NotNull private final String processName;
	@NotNull private final Runnable operation;

	@Override
	public void run() {
		// by the time the code reaches here, the user would have configured the appropriate handlers
		try {
			operation.run();
		} catch (Exception e) {
			LOGGER.error("Failed to run " + processName, e);
			throw e;
		}
	}

	@Override
	public String toString() {
		return processName;
	}
}