package org.simplejavamail.api.internal.clisupport.model;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CliDeclaredOptionSpec implements Comparable<CliDeclaredOptionSpec> {
	@Nonnull
	private final String name;
	@Nonnull
	private final List<String> description;
	@Nonnull
	private final CliBuilderApiType fromBuilderApiType;
	@Nonnull
	private final List<CliDeclaredOptionValue> possibleOptionValues;
	@Nonnull
	private final Method sourceMethod;
	
	public CliDeclaredOptionSpec(@Nonnull String name, @Nonnull List<String> description,
								 @Nonnull List<CliDeclaredOptionValue> possibleArguments, @Nonnull CliBuilderApiType fromBuilderApiType,
								 @Nonnull Method sourceMethod) {
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
	public int compareTo(@Nonnull CliDeclaredOptionSpec other) {
		int prefixOrder = getNamePrefix().compareTo(other.getNamePrefix());
		return prefixOrder != 0 ? prefixOrder : getNameAfterPrefix().compareTo(other.getNameAfterPrefix());
	}
	
	private String getNamePrefix() {
		return getName().substring(0, getName().indexOf(":"));
	}
	
	private String getNameAfterPrefix() {
		return getName().substring(getName().indexOf(":"));
	}
	
	@Nonnull
	public String getName() {
		return name;
	}
	
	@Nonnull
	public List<String> getDescription() {
		return description;
	}
	
	@Nonnull
	public List<CliDeclaredOptionValue> getPossibleOptionValues() {
		return possibleOptionValues;
	}
	
	@Nonnull
	public Method getSourceMethod() {
		return sourceMethod;
	}
}