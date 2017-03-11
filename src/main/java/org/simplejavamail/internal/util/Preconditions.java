package org.simplejavamail.internal.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.lang.String.format;

public class Preconditions {
	public static <T> T checkNonEmptyArgument(@Nullable final T address, @Nonnull final String parameterName) {
		if (MiscUtil.valueNullOrEmpty(address)) {
			throw new IllegalArgumentException(format("%s is required", parameterName));
		}
		return address;
	}
}
