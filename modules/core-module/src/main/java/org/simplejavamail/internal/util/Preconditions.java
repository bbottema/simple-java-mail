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
package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static java.lang.String.format;

public final class Preconditions {
	private Preconditions() {
		// util / helper class
	}
	
	@NotNull
	public static <T> T checkNonEmptyArgument(@Nullable final T arg, @NotNull final String parameterName) {
		return verifyNonnull(arg, format("%s is required", parameterName));
	}
	
	@NotNull
	public static <T> T assumeNonNull(@Nullable final T arg) {
		return verifyNonnull(arg, "argument was assumed nonNull, but was null");
	}
	
	@NotNull
	private static <T> T verifyNonnull(@Nullable final T arg, @NotNull final String message) {
		if (MiscUtil.valueNullOrEmpty(arg)) {
			throw new IllegalArgumentException(message);
		}
		return arg;
	}
	
	@SuppressWarnings({"UnusedReturnValue", "SameReturnValue"})
	public static boolean assumeTrue(boolean state, @NotNull final String msg) {
		if (!state) {
			throw new IllegalArgumentException(msg);
		}
		return true;
	}
}
