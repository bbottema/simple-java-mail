package org.simplejavamail.internal.clisupport.model;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CliOptionData implements Comparable<CliOptionData> {
	private final String name;
	private final List<String> description;
	private final Collection<CliCommand> applicableToCliCommands;
	private final List<CliOptionValueData> possibleOptionValues;
	
	public CliOptionData(String name, List<String> description, List<CliOptionValueData> possibleArguments, Collection<CliCommand> applicableToCliCommands) {
		this.name = name;
		this.description = Collections.unmodifiableList(description);
		this.applicableToCliCommands = Collections.unmodifiableCollection(applicableToCliCommands);
		this.possibleOptionValues = Collections.unmodifiableList(possibleArguments);
	}
	
	@Override
	public String toString() {
		return name;
	}
	
	public boolean applicableToRootCommand(CliCommand name) {
		return this.applicableToCliCommands.contains(CliCommand.all) ||
				this.applicableToCliCommands.contains(name);
	}
	
	@Override
	public int compareTo(@Nonnull CliOptionData other) {
		int prefixOrder = getNamePrefix().compareTo(other.getNamePrefix());
		return prefixOrder != 0 ? prefixOrder : getNameAfterPrefix().compareTo(other.getNameAfterPrefix());
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
	
	public Collection<CliCommand> getApplicableToCliCommands() {
		return applicableToCliCommands;
	}
	
	public List<CliOptionValueData> getPossibleOptionValues() {
		return possibleOptionValues;
	}
}