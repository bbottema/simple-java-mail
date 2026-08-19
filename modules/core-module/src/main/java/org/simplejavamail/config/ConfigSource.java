package org.simplejavamail.config;

import java.util.Map;

/**
 * Supplies one named set of raw Simple Java Mail configuration values to {@link ConfigLoader}.
 * <p>
 * A loader asks each source for its values once per {@link ConfigLoader#load()} call and immediately detaches the returned map. Sources are applied in
 * registration order and a later non-blank value wins. Implementations may therefore return a fresh view on every call when configuration is expected to
 * change between loads.
 */
public interface ConfigSource {

	/**
	 * @return A stable, non-blank name used to report where a resolved value came from.
	 */
	String getName();

	/**
	 * @return Raw values keyed by the canonical {@link ConfigLoader.Property#key()} names, including supported wildcard namespace keys.
	 */
	Map<String, ?> getProperties();

	/**
	 * Strict sources reject unknown keys. Process-wide environment and system-property sources are non-strict because most of their entries belong to other
	 * software.
	 *
	 * @return Whether unknown keys should make loading fail.
	 */
	default boolean isStrict() {
		return true;
	}
}
