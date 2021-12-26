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
		return verifyNonnullOrEmpty(arg, format("%s is required", parameterName));
	}
	
	@NotNull
	public static <T> T verifyNonnull(@Nullable final T arg) {
		if (arg == null) {
			throw new IllegalArgumentException("argument was assumed nonNull, but was null");
		}
		return arg;
	}
	
	@NotNull
	public static <T> T verifyNonnullOrEmpty(@Nullable final T arg) {
		return verifyNonnullOrEmpty(arg, "argument was assumed nonNull and nonEmpty, but was null or empty");
	}
	
	@NotNull
	private static <T> T verifyNonnullOrEmpty(@Nullable final T arg, @NotNull final String message) {
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
