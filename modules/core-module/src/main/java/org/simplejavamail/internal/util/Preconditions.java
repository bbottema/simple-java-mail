package org.simplejavamail.internal.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

public final class Preconditions {
	private Preconditions() {
		// util / helper class
	}
	
	@Nonnull
	public static <T> T checkNonEmptyArgument(@Nullable final T arg, @Nonnull final String parameterName) {
		return verifyNonnull(arg, format("%s is required", parameterName));
	}
	
	@Nonnull
	public static <T> T assumeNonNull(@Nullable final T arg) {
		return verifyNonnull(arg, "argument was assumed nonNull, but was null");
	}
	
	@Nonnull
	private static <T> T verifyNonnull(@Nullable final T arg, @Nonnull final String message) {
		if (MiscUtil.valueNullOrEmpty(arg)) {
			throw new IllegalArgumentException(message);
		}
		return arg;
	}
	
	@SuppressWarnings({"UnusedReturnValue", "SameReturnValue"})
	public static boolean assumeTrue(boolean state, @Nonnull final String msg) {
		if (!state) {
			throw new IllegalArgumentException(msg);
		}
		return true;
	}
}
