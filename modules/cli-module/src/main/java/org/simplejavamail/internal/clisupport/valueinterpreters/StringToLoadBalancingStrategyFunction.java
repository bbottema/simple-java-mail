package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;

public class StringToLoadBalancingStrategyFunction implements ValueFunction<String, LoadBalancingStrategy> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<LoadBalancingStrategy> getTargetType() {
		return LoadBalancingStrategy.class;
	}
	
	@Override
	public final LoadBalancingStrategy convertValue(String value) {
		try {
			return LoadBalancingStrategy.valueOf(value);
		} catch (IllegalArgumentException e) {
			throw new IncompatibleTypeException(value, String.class, LoadBalancingStrategy.class, e);
		}
	}
}