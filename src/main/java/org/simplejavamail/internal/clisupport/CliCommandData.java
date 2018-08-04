package org.simplejavamail.internal.clisupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

class CliCommandData {
	private final String name;
	private final List<String> description;
	private final List<CliParamData> possibleParams;
	private Collection<CliCommandData> subCommands = new ArrayList<>();
	
	CliCommandData(String name, List<String> description, List<CliParamData> possibleArguments) {
		this.name = name;
		this.description = description;
		this.possibleParams = possibleArguments;
	}
	
	String getName() {
		return name;
	}
	
	List<CliParamData> getPossibleParams() {
		return possibleParams;
	}
	
	Collection<CliCommandData> getSubCommands() {
		return Collections.unmodifiableCollection(subCommands);
	}
	
	void setSubCommands(Collection<CliCommandData> subCommands) {
		this.subCommands = subCommands;
	}
	
	List<String> getDescription() {
		return description;
	}
}