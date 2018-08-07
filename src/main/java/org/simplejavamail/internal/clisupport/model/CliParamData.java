package org.simplejavamail.internal.clisupport.model;

import java.util.Arrays;

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
	
	public String formatDescription() {
		if (getExamples().length == 0) {
			return getDescription();
		} else if (getExamples().length == 1) {
			return getDescription() + "\nexample: " + getExamples()[0];
		} else {
			return getDescription() + "\nexamples: " + formatExamplesText(getExamples());
		}
	}
	
	private static String formatExamplesText(String[] examples) {
		String examplesArray = Arrays.toString(examples);
		return examplesArray.substring(1, examplesArray.length() - 1);
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