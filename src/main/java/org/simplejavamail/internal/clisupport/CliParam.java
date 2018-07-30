package org.simplejavamail.internal.clisupport;

public class CliParam {
	private final Class<?> argumentType;
	private final String name;
	private final String example;
	
	public CliParam(Class<?> argumentType, String name, String example) {
		this.argumentType = argumentType;
		this.name = name;
		this.example = example;
	}
	
	public Class<?> getArgumentType() {
		return argumentType;
	}
	
	public String getName() {
		return name;
	}
	
	public String getExample() {
		return example;
	}
}
