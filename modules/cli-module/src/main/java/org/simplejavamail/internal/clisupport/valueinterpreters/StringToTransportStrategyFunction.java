package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.api.mailer.config.TransportStrategy;

public class StringToTransportStrategyFunction implements ValueFunction<String, TransportStrategy> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<TransportStrategy> getTargetType() {
		return TransportStrategy.class;
	}
	
	@Override
	public final TransportStrategy convertValue(String value) {
		try {
			return TransportStrategy.valueOf(value);
		} catch (IllegalArgumentException e) {
			throw new IncompatibleTypeException(value, String.class, TransportStrategy.class, e);
		}
	}
}