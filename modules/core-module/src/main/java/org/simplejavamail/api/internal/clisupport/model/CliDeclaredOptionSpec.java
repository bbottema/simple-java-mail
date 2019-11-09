package org.simplejavamail.api.internal.clisupport.model;

import org.jetbrains.annotations.NotNull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CliDeclaredOptionSpec implements Comparable<CliDeclaredOptionSpec> {
	@NotNull
	private final String name;
	@NotNull
	private final List<String> description;
	@NotNull
	private final CliBuilderApiType fromBuilderApiType;
	@NotNull
	private final List<CliDeclaredOptionValue> possibleOptionValues;
	@NotNull
	private final Method sourceMethod;
	
	public CliDeclaredOptionSpec(@NotNull String name, @NotNull List<String> description,
								 @NotNull List<CliDeclaredOptionValue> possibleArguments, @NotNull CliBuilderApiType fromBuilderApiType,
								 @NotNull Method sourceMethod) {
		this.name = name;
		this.description = Collections.unmodifiableList(description);
		this.fromBuilderApiType = fromBuilderApiType;
		this.possibleOptionValues = Collections.unmodifiableList(possibleArguments);
		this.sourceMethod = sourceMethod;
	}
	
	@Override
	public String toString() {
		return name;
	}
	
	public boolean applicableToRootCommand(Collection<CliBuilderApiType> compatibleBuilderApiTypes) {
		return compatibleBuilderApiTypes.contains(fromBuilderApiType);
	}
	
	public List<CliDeclaredOptionValue> getMandatoryOptionValues() {
		List<CliDeclaredOptionValue> mandatoryOptionValues = new ArrayList<>();
		for (CliDeclaredOptionValue optionValue : possibleOptionValues) {
			if (optionValue.isRequired()) {
				mandatoryOptionValues.add(optionValue);
			}
		}
		return mandatoryOptionValues;
	}
	
	@Override
	public int compareTo(@NotNull CliDeclaredOptionSpec other) {
		int prefixOrder = getNamePrefix().compareTo(other.getNamePrefix());
		return prefixOrder != 0 ? prefixOrder : getNameAfterPrefix().compareTo(other.getNameAfterPrefix());
	}
	
	private String getNamePrefix() {
		return getName().substring(0, getName().indexOf(":"));
	}
	
	private String getNameAfterPrefix() {
		return getName().substring(getName().indexOf(":"));
	}
	
	@NotNull
	public String getName() {
		return name;
	}
	
	@NotNull
	public List<String> getDescription() {
		return description;
	}
	
	@NotNull
	public List<CliDeclaredOptionValue> getPossibleOptionValues() {
		return possibleOptionValues;
	}
	
	@NotNull
	public Method getSourceMethod() {
		return sourceMethod;
	}
}