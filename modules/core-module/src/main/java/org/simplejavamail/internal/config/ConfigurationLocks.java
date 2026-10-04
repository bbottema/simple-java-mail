package org.simplejavamail.internal.config;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Internal, immutable restrictions shared by the configuration owners of one factory. */
@ApiStatus.Internal
public final class ConfigurationLocks {

	private static final String PREFIX = "simplejavamail.locked.";
	private final Map<String, Object> values;
	private final Map<String, String> sources;

	/** Receives already-parsed restrictions and their sources from the shared configuration loader. */
	public ConfigurationLocks(final Map<String, Object> values, final Map<String, String> sources) {
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
	}

	/** Converts a canonical ordinary property name to its locked counterpart. */
	public static String lockedName(final String propertyName) {
		return PREFIX + propertyName.substring("simplejavamail.".length());
	}

	/** Returns the ordinary schema key for either namespace. */
	public static String ordinaryName(final String propertyName) {
		return isLockedName(propertyName) ? "simplejavamail." + propertyName.substring(PREFIX.length()) : propertyName;
	}

	public static boolean isLockedName(@Nullable final String propertyName) {
		return propertyName != null && propertyName.startsWith(PREFIX);
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	public boolean contains(final Property property) {
		return values.containsKey(property.key());
	}

	/** Raw internal values; callers must not log this map. Concrete wildcard children keep their ordinary canonical keys. */
	@NotNull
	public Map<String, Object> getValues() {
		return values;
	}

	/** Checks a final effective choice, including a null/reset choice. Error text never includes either value. */
	public void verify(final Property property, @Nullable final Object actualValue) {
		verify(property.key(), actualValue);
	}

	public void verify(final String propertyName, @Nullable final Object actualValue) {
		verify(propertyName, actualValue, "The supplied setting differs from the locked value. "
				+ "Remove the conflicting customization or reset, use the locked value, or change this lock in its configuration source.");
	}

	/** Keeps comparison and redaction shared while identifying the configuration owner whose choice conflicts. */
	public void verify(final Property property, @Nullable final Object actualValue, final String explanation) {
		verify(property.key(), actualValue, explanation);
	}

	private void verify(final String propertyName, @Nullable final Object actualValue, final String explanation) {
		if (values.containsKey(propertyName) && !Objects.deepEquals(values.get(propertyName), actualValue)) {
			throw conflict(propertyName, explanation);
		}
	}

	/** Creates a secret-safe error for a restriction that the selected path cannot satisfy. */
	public IllegalArgumentException conflict(final Property property, final String remedy) {
		return conflict(property.key(), remedy);
	}

	public IllegalArgumentException conflict(final String propertyName, final String remedy) {
		return new IllegalArgumentException("Configuration property " + escapeControlCharacters(lockedName(propertyName))
				+ " is locked by " + escapeControlCharacters(sources.get(propertyName))
				+ ". This key uses simplejavamail.locked.*, so its setting is fixed for every Mailer created by this factory. "
				+ "Ordinary properties, builder calls and Email settings cannot override it. " + remedy);
	}
}
