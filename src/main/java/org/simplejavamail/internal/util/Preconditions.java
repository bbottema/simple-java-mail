package org.simplejavamail.internal.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

public final class Preconditions {
	private Preconditions() {
		// util / helper class
	}
	
	public static <T> T checkNonEmptyArgument(@Nullable final T arg, @Nonnull final String parameterName) {
		if (MiscUtil.valueNullOrEmpty(arg)) {
			throw new IllegalArgumentException(format("%s is required", parameterName));
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
	
	public static void assumeTrue(boolean expression, String msg) {
		if (!expression) {
			throw new AssertionError("Wrong assumption:\n\t" + msg);
		}
	}
}
