package org.simplejavamail.internal.clisupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

class CliCommandData {
	private final String name;
	private final List<CliParamData> possibleParams;
	private Collection<CliCommandData> subCommands = new ArrayList<>();
	
	CliCommandData(String name, List<CliParamData> possibleArguments) {
		this.name = name;
		this.possibleParams = possibleArguments;
	}
	
	public String getName() {
		return name;
	}
	
	public List<CliParamData> getPossibleParams() {
		return possibleParams;
	}
	
	public Collection<CliCommandData> getSubCommands() {
		return Collections.unmodifiableCollection(subCommands);
	}
	
	public void setSubCommands(Collection<CliCommandData> subCommands) {
		this.subCommands = subCommands;
	}
}