package org.simplejavamail.internal.clisupport.model;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CliOptionData implements Comparable<CliOptionData> {
	@Nonnull
	private final String name;
	@Nonnull
	private final List<String> description;
	@Nonnull
	private final Collection<CliCommand> applicableToCliCommands;
	@Nonnull
	private final List<CliOptionValueData> possibleOptionValues;
	@Nonnull
	private final Method sourceMethod;
	
	public CliOptionData(@Nonnull String name, @Nonnull List<String> description, @Nonnull List<CliOptionValueData> possibleArguments,
						 @Nonnull Collection<CliCommand> applicableToCliCommands, @Nonnull Method sourceMethod) {
		this.name = name;
		this.description = Collections.unmodifiableList(description);
		this.applicableToCliCommands = Collections.unmodifiableCollection(applicableToCliCommands);
		this.possibleOptionValues = Collections.unmodifiableList(possibleArguments);
		this.sourceMethod = sourceMethod;
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
	
	public Method getSourceMethod() {
		return sourceMethod;
	}
}