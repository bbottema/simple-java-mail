package org.simplejavamail.internal.util;

import java.util.Comparator;
import java.util.Map;

public class NaturalEntryKeyComparator<T extends Comparable<T>> implements Comparator<Map.Entry<T, Object>> {
	
	public static final NaturalEntryKeyComparator INSTANCE = new NaturalEntryKeyComparator();
	
	// TODO Lombok
	private NaturalEntryKeyComparator(){
	}
	
	
	@Override
	public int compare(Map.Entry<T, Object> o1, Map.Entry<T, Object> o2) {
		int keyComparison = o1.getKey().compareTo(o2.getKey());
		if (keyComparison != 0) {
			return keyComparison;
		}
		return Integer.compare(o1.getValue().hashCode(), o2.getValue().hashCode());
	}
}