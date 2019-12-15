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