package org.simplejavamail.internal.clisupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

class CliOptionFlag {
	private final String name;
	private final List<CliParam> possibleParams;
	private final String helpLabel;
	private Collection<CliOptionFlag> validNextOptions = new ArrayList<>();
	
	private List<String> rawValues;
	
	CliOptionFlag(String name, String helpLabel, List<CliParam> possibleArguments) {
		this.name = name;
		this.helpLabel = helpLabel;
		this.possibleParams = possibleArguments;
	}
	
	public String getName() {
		return name;
	}
	
	public List<CliParam> getPossibleParams() {
		return possibleParams;
	}
	
	public Collection<CliOptionFlag> getValidNextOptions() {
		return Collections.unmodifiableCollection(validNextOptions);
	}
	
	public List<String> getRawValues() {
		return rawValues;
	}
	
	public void setRawValues(List<String> rawValues) {
		this.rawValues = rawValues;
	}
	
	public void setValidNextOptions(Collection<CliOptionFlag> validNextOptions) {
		this.validNextOptions = validNextOptions;
	}
	
	public String getHelpLabel() {
		return helpLabel;
	}
}