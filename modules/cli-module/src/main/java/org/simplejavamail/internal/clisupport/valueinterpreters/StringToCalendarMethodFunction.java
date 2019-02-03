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