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

import java.util.Comparator;
import java.util.Map;

public class NaturalEntryKeyComparator<T extends Comparable<T>> implements Comparator<Map.Entry<T, Object>> {
	
	@SuppressWarnings("rawtypes")
	public static final NaturalEntryKeyComparator INSTANCE = new NaturalEntryKeyComparator();
	
	// TODO Lombok
	private NaturalEntryKeyComparator(){
	}
	
	
	@Override
	public int compare(Map.Entry<T, Object> o1, Map.Entry<T, Object> o2) {
		return o1.getKey().compareTo(o2.getKey());
	}
}
