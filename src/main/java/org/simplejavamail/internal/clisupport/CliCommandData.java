package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliSupported;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

class CliCommandData implements Comparable<CliCommandData> {
	private final String name;
	private final List<String> description;
	private final List<CliParamData> possibleParams;
	private final Collection<CliSupported.RootCommand> applicableRootCommands;
	private final Collection<CliCommandData> subCommands;
	
	CliCommandData(String name, List<String> description, List<CliParamData> possibleArguments, Collection<CliSupported.RootCommand> applicableRootCommands, Collection<CliCommandData> subCommands) {
		this.name = name;
		this.description = Collections.unmodifiableList(description);
		this.possibleParams = Collections.unmodifiableList(possibleArguments);
		this.applicableRootCommands = Collections.unmodifiableCollection(applicableRootCommands);
		this.subCommands = Collections.unmodifiableCollection(subCommands);
	}
	
	@Override
	public String toString() {
		return name;
	}
	
	boolean applicableToRootCommand(CliSupported.RootCommand name) {
		return this.applicableRootCommands.contains(CliSupported.RootCommand.all) ||
				this.applicableRootCommands.contains(name);
	}
	
	@Override
	public int compareTo(CliCommandData o) {
		int prefixOrder = getNamePrefix().compareTo(o.getNamePrefix());
		return prefixOrder != 0 ? prefixOrder : getNameAfterPrefix().compareTo(o.getNameAfterPrefix());
	}
	
	private String getNamePrefix() {
		return getName().substring(0, getName().indexOf(":"));
	}
	
	private String getNameAfterPrefix() {
		return getName().substring(getName().indexOf(":"));
	}
	
	public String getName() {
		return name;
	}
	
	public List<String> getDescription() {
		return description;
	}
	
	public List<CliParamData> getPossibleParams() {
		return possibleParams;
	}
	
	public Collection<CliSupported.RootCommand> getApplicableRootCommands() {
		return applicableRootCommands;
	}
	
	public Collection<CliCommandData> getSubCommands() {
		return subCommands;
	}
}