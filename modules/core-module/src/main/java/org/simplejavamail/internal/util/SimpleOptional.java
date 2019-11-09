package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

	@NotNull
	public T orElse(@NotNull T otherValue) {
		return value != null ? value : otherValue;
	}

	@Nullable
	public T orMaybe(@Nullable T otherValue) {
		return value != null ? value : otherValue;
	}
}