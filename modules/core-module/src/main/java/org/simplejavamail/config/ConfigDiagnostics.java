package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable, grouped and safe-to-log view of the explicitly resolved properties in a {@link SimpleJavaMailConfig} snapshot.
 * <p>
 * This report does not include later Email or Mailer builder overrides, generated runtime defaults, caller-owned Jakarta Mail Session values, or live
 * executor, pool and transport state.
 */
public final class ConfigDiagnostics {

	private final List<ConfigDiagnosticGroup> groups;
	private final Map<ConfigDiagnosticGroup, List<ConfigPropertyDiagnostic>> propertiesByGroup;

	ConfigDiagnostics(@NotNull final Collection<ConfigPropertyDiagnostic> properties) {
		final Map<ConfigDiagnosticGroup, List<ConfigPropertyDiagnostic>> mutablePropertiesByGroup = groupAndSort(properties);
		final Map<ConfigDiagnosticGroup, List<ConfigPropertyDiagnostic>> immutablePropertiesByGroup =
				new EnumMap<>(ConfigDiagnosticGroup.class);
		for (ConfigDiagnosticGroup group : ConfigDiagnosticGroup.values()) {
			final List<ConfigPropertyDiagnostic> groupProperties = mutablePropertiesByGroup.get(group);
			if (groupProperties != null && !groupProperties.isEmpty()) {
				immutablePropertiesByGroup.put(group, Collections.unmodifiableList(groupProperties));
			}
		}
		this.propertiesByGroup = Collections.unmodifiableMap(immutablePropertiesByGroup);
		this.groups = Collections.unmodifiableList(new ArrayList<>(immutablePropertiesByGroup.keySet()));
	}

	/**
	 * @return The populated groups in their declared order.
	 */
	@NotNull
	public List<ConfigDiagnosticGroup> getGroups() {
		return groups;
	}

	/**
	 * @param group The functional section to inspect.
	 * @return The configured properties in that group, ordered by canonical property name, or an empty list when the group has no configured properties.
	 */
	@NotNull
	public List<ConfigPropertyDiagnostic> getProperties(@NotNull final ConfigDiagnosticGroup group) {
		final List<ConfigPropertyDiagnostic> properties = propertiesByGroup.get(requireNonNull(group, "group"));
		return properties != null ? properties : Collections.<ConfigPropertyDiagnostic>emptyList();
	}

	@Override
	public String toString() {
		if (groups.isEmpty()) {
			return "No configured Simple Java Mail properties.";
		}
		final StringBuilder report = new StringBuilder();
		for (ConfigDiagnosticGroup group : groups) {
			if (report.length() > 0) {
				report.append(System.lineSeparator());
			}
			report.append(group.getDisplayName()).append(':');
			for (ConfigPropertyDiagnostic property : getProperties(group)) {
				report.append(System.lineSeparator()).append("  ").append(property);
			}
		}
		return report.toString();
	}

	private static Map<ConfigDiagnosticGroup, List<ConfigPropertyDiagnostic>> groupAndSort(
			final Collection<ConfigPropertyDiagnostic> properties) {
		final Map<ConfigDiagnosticGroup, List<ConfigPropertyDiagnostic>> grouped = new EnumMap<>(ConfigDiagnosticGroup.class);
		for (ConfigPropertyDiagnostic property : requireNonNull(properties, "properties")) {
			grouped.computeIfAbsent(property.getGroup(), ignored -> new ArrayList<>()).add(property);
		}
		for (List<ConfigPropertyDiagnostic> groupProperties : grouped.values()) {
			groupProperties.sort(Comparator.comparing(ConfigPropertyDiagnostic::getPropertyName));
		}
		return grouped;
	}
}
