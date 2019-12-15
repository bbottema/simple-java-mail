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