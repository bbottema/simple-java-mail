package org.simplejavamail.internal.clisupport;

import java.lang.reflect.Method;
import java.util.Objects;

public class OptionFollowupCombination {
	private final Method m;
	private final Class<?> returnType;
	
	public OptionFollowupCombination(Method m, Class<?> returnType) {
		this.m = m;
		this.returnType = returnType;
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		OptionFollowupCombination that = (OptionFollowupCombination) o;
		return Objects.equals(m, that.m) &&
				Objects.equals(returnType, that.returnType);
	}
	
	@Override
	public int hashCode() {
		
		return Objects.hash(m, returnType);
	}
	
	public Method getM() {
		return m;
	}
	
	public Class<?> getReturnType() {
		return returnType;
	}
}
