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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience class that provides a clearer API for obtaining list elements.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public final class ListUtil {
	
	private ListUtil() {
	}
	
	public static <T> T getFirstOrNull(final List<T> list) {
		return getFirst(list, null);
	}
	
	public static <T> T getLastOrNull(final List<T> list) {
		return getLast(list, null);
	}
	
	public static <T> T getFirst(final List<T> list) {
		return list.get(0);
	}
	
	public static <T> T getLast(final List<T> list) {
		return list.get(list.size() - 1);
	}

	/**
	 * @return First of list or if empty, default value {@code t}
	 */
	public static <T> T getFirst(final List<T> list, final T t) {
		return isEmpty(list) ? t : list.get(0);
	}

	/**
	 * @return Last of list or if empty, default value {@code t}
	 */
	public static <T> T getLast(final List<T> list, final T t) {
		return isEmpty(list) ? t : list.get(list.size() - 1);
	}
	
	public static <T> boolean isEmpty(final List<T> list) {
		return list == null || list.isEmpty();
	}

	public static <T> ArrayList<T> merge(final List<T> list1, final List<T> list2) {
		ArrayList<T> merged = new ArrayList<>(list1);
		merged.addAll(list2);
		return merged;
	}
	public static <K, V> HashMap<K, V> merge(final Map<K, V> map1, final Map<K, V> map2) {
		HashMap<K, V> merged = new HashMap<>();
		merged.putAll(map1);
		merged.putAll(map2);
		return merged;
	}
}