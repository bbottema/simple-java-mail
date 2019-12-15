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
package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.api.email.CalendarMethod;

public class StringToCalendarMethodFunction implements ValueFunction<String, CalendarMethod> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<CalendarMethod> getTargetType() {
		return CalendarMethod.class;
	}
	
	@Override
	public final CalendarMethod convertValue(String value) {
		try {
			return CalendarMethod.valueOf(value);
		} catch (IllegalArgumentException e) {
			throw new IncompatibleTypeException(value, String.class, CalendarMethod.class, e);
		}
	}
}