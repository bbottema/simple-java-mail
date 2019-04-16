package org.simplejavamail.internal.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@SuppressWarnings("WeakerAccess")
public class SimpleOptional<T> {
	@Nullable
	private final T value;
	
	public SimpleOptional(@Nullable T value) {
		this.value = value;
	}
	
	public static <T> SimpleOptional<T> ofNullable(@Nullable T value) {
		return new SimpleOptional<>(value);
	}

	@Nonnull
	public T orElse(@Nonnull T otherValue) {
		return value != null ? value : otherValue;
	}

	@Nullable
	public T orMaybe(@Nullable T otherValue) {
		return value != null ? value : otherValue;
	}
}