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

import org.jetbrains.annotations.Nullable;

import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class SimpleConversions {
	
	@Nullable
	public static String convertToString(@Nullable final Object value) {
		return value != null ? value.toString() : null;
	}
	
	@Nullable
	public static Integer convertToInteger(@Nullable final Object value) {
		if (value == null || value instanceof Integer) {
			return (Integer) value;
		} else if (value instanceof Number) {
			return ((Number) value).intValue();
		} else if (value instanceof Boolean) {
			return ((Boolean) value) ? 1 : 0;
		} else {
			assumeTrue(value instanceof String, "Wrong property must have been requested");
			return Integer.valueOf((String) value);
		}
	}
	
	@Nullable
	public static Boolean convertToBoolean(@Nullable final Object value) {
		if (value == null || value instanceof Boolean) {
			return (Boolean) value;
		} else if (value instanceof Number) {
			return ((Number) value).intValue() != 0;
		} else {
			assumeTrue(value instanceof String, "Wrong property must have been requested");
			String strValue = (String) value;
			if (strValue.equalsIgnoreCase("true") || strValue.equalsIgnoreCase("1")) {
				return true;
			} else if (strValue.equalsIgnoreCase("false") || strValue.equalsIgnoreCase("0")) {
				return false;
			}
			throw new AssertionError("Wrong property must have been requested");
		}
	}
}
