package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;

public class StringToSessionDebugOutputFunction implements ValueFunction<String, SessionDebugOutput> {
	@Override
	public Class<String> getFromType() {
		return String.class;
	}

	@Override
	public Class<SessionDebugOutput> getTargetType() {
		return SessionDebugOutput.class;
	}

	@Override
	public final SessionDebugOutput convertValue(String value) {
		try {
			return SessionDebugOutput.valueOf(value);
		} catch (final IllegalArgumentException e) {
			throw new IncompatibleTypeException(value, String.class, SessionDebugOutput.class, e);
		}
	}
}
