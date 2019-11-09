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
