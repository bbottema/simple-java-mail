package org.simplejavamail.internal.clisupport.model;

import java.util.List;

import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.MiscUtil.nStrings;

public class CliDeclaredOptionValue {
	private final Class<?> paramType;
	private final String name;
	private final String helpLabel;
	private final String description;
	private final boolean required;
	private final String[] examples;
	
	public CliDeclaredOptionValue(Class<?> paramType, String name, String helpLabel, String description, boolean required, String[] examples) {
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
			return getDescription() + "\n    example: " + getExamples()[0];
		} else {
			return getDescription() + "\n    examples: " + formatExamplesText("    examples: ".length(), asList(getExamples()));
		}
	}
	
	private static String formatExamplesText(int indent, List<String> examples) {
		StringBuilder examplesFormatted = new StringBuilder().append(getFirst(examples)).append("\n");
		for (String example : examples.subList(1, examples.size())) {
			examplesFormatted.append(nStrings(indent, " ")).append(example).append("\n");
		}
		return examplesFormatted.toString();
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