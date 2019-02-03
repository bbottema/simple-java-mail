package org.simplejavamail.internal.util;

import java.util.List;

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
	
	public static <T> T getFirst(final List<T> list, final T t) {
		return isEmpty(list) ? t : list.get(0);
	}
	
	public static <T> T getLast(final List<T> list, final T t) {
		return isEmpty(list) ? t : list.get(list.size() - 1);
	}
	
	public static <T> boolean isEmpty(final List<T> list) {
		return list == null || list.isEmpty();
	}
}