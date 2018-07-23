package org.simplejavamail.internal.clisupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class CliParam {
	private final String name;
	private final List<Class<?>> requiredTypes;
	private final Collection<CliParam> validNextParams = new ArrayList<>();
	
	private List<String> rawValues;
	
	CliParam(String name, List<Class<?>> requiredTypes) {
		this.name = name;
		this.requiredTypes = requiredTypes;
	}
	
	public String getName() {
		return name;
	}
	
	public List<Class<?>> getRequiredTypes() {
		return requiredTypes;
	}
	
	public Collection<CliParam> getValidNextParams() {
		return validNextParams;
	}
	
	public List<String> getRawValues() {
		return rawValues;
	}
	
	public void setRawValues(List<String> rawValues) {
		this.rawValues = rawValues;
	}
}