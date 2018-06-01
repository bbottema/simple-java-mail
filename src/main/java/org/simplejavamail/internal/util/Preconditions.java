package org.simplejavamail.internal.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

public final class Preconditions {
	private Preconditions() {
		// util / helper class
	}
	
	public static <T> T checkNonEmptyArgument(@Nullable final T address, @Nonnull final String parameterName) {
		if (MiscUtil.valueNullOrEmpty(address)) {
			throw new IllegalArgumentException(format("%s is required", parameterName));
		}
		return address;
	}
	
	public static boolean assumeTrue(boolean state, @Nonnull final String msg) {
		if (!state) {
			throw new IllegalArgumentException(msg);
		}
		return true;
	}
}
