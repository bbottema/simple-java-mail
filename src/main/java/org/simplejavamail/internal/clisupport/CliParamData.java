package org.simplejavamail.internal.clisupport;

public class CliParamData {
	private final Class<?> paramType;
	private final String name;
	private final String helpLabel;
	private final String description;
	private final boolean required;
	private final String[] examples;
	
	public CliParamData(Class<?> paramType, String name, String helpLabel, String description, boolean required, String[] examples) {
		this.paramType = paramType;
		this.name = name;
		this.helpLabel = helpLabel;
		this.description = description;
		this.required = required;
		this.examples = examples;
	}
	
	public Class<?> getParamType() {
		return paramType;
	}
	
	public String getName() {
		return name;
	}
	
	public String getHelpLabel() {
		return helpLabel;
	}
	
	public String getDescription() {
		return description;
	}
	
	public String[] getExamples() {
		return examples;
	}
	
	public boolean isRequired() {
		return required;
	}
}
