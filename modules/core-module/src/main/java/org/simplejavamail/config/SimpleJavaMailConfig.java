package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * An immutable, thread-safe snapshot of resolved Simple Java Mail configuration.
 * <p>
 * A snapshot contains only explicitly configured values. Runtime defaults such as generated cluster identifiers remain builder concerns. Stored input and
 * wildcard maps are detached during construction; snapshot maps returned by typed reads or {@link #asMap()} are unmodifiable.
 * <p>
 * Typed key reads and {@link #asMap()} return configured values and may therefore expose credentials. Use {@link #getDiagnostics()} for
 * structured output intended for diagnostic logs.
 * <p>
 * Properties under {@code simplejavamail.locked.} supply their ordinary counterpart's effective value and remain restrictions on later customization
 * in every Mailer created from this snapshot's factory. Diagnostics retain the locked name and its source. An independently configured factory remains
 * possible; these local restrictions are not a security sandbox.
 */
public interface SimpleJavaMailConfig {

	/**
	 * @param key Library-defined property key.
	 * @return Whether this snapshot contains a non-blank value for the property.
	 */
	boolean hasProperty(@NotNull final ConfigKey<?> key);

	/**
	 * Returns the configured property through its schema-bound Java type, or null when absent.
	 * <p>
	 * For example, {@code Integer port = config.get(ConfigKeys.SMTP_PORT)} and
	 * {@code Duration timeout = config.get(ConfigKeys.SESSION_TIMEOUT)}. The key determines the type; requesting a port as
	 * a String does not compile. Legacy numeric-millisecond properties retain their text/limits and project to Duration here.
	 * <p>
	 * Aggregate keys retain complete generic map types. Missing maps are null, not fabricated configured empty maps;
	 * present snapshot maps are detached and unmodifiable. This configuration-input convention is separate from operation-result collections.
	 *
	 * @param key Library-defined property key from {@link ConfigKeys}.
	 * @param <T> The key's Java value type.
	 * @return The configured Java value, or null when absent. Runtime/builder defaults are not resolved here.
	 */
	@Nullable
	<T> T get(@NotNull final ConfigKey<T> key);

	/**
	 * For a combined wildcard namespace, inspect individual diagnostic entries for per-child provenance. This getter
	 * reports an aggregate origin only when the resolver recorded one; a configured named-Mailer map has no single origin.
	 * @param key Library-defined property key.
	 * @return The recorded winning source name, or null when absent or without one aggregate origin.
	 */
	@Nullable
	String getPropertySource(@NotNull final ConfigKey<?> key);

	/**
	 * @return An immutable, grouped and safe-to-log description of every configured value and its winning source.
	 * @see org.simplejavamail.api.mailer.Mailer#getOperationalConfig()
	 */
	@NotNull
	ConfigDiagnostics getDiagnostics();

	/**
	 * Returns a supplied nonempty explicit value, otherwise the configured value, otherwise the supplied fallback.
	 * An empty String is absent; the existing treatment of whitespace Strings, zero and false is retained. Only the
	 * key's exact Java type can be supplied for either value, including when the caller assigns the result to Object.
	 * Explicit/fallback objects are returned as supplied; this helper does not detach or take ownership of them.
	 *
	 * @param explicitValue Application choice, or null/empty to consider configured input.
	 * @param key Property defining the exact value type.
	 * @param fallbackValue Value used only when the explicit/configured choice is absent.
	 * @param <T> The key's Java value type.
	 * @return The selected value, retaining absence and existing feature-owner validation boundaries.
	 */
	@Nullable
	<T> T valueOrProperty(@Nullable final T explicitValue,
			@NotNull final ConfigKey<T> key,
			@Nullable final T fallbackValue);

	/**
	 * Selects an explicit or configured value with no fallback.
	 * @param explicitValue Application choice, or null/empty to consider configured input.
	 * @param key Property defining the exact value type.
	 * @param <T> The key's Java value type.
	 * @return The selected value, or null when both choices are absent.
	 * @see #valueOrProperty(Object, ConfigKey, Object)
	 */
	@Nullable
	<T> T valueOrProperty(@Nullable final T explicitValue, @NotNull final ConfigKey<T> key);

	/**
	 * Returns an unmodifiable heterogeneous map for dynamic integration, keyed by textual/parser property identities.
	 * Its values retain the parser representation, including Integer milliseconds for legacy timeout properties, while
	 * {@link #get(ConfigKey)} provides the canonical typed Java view. Wildcard maps remain detached and unmodifiable.
	 * This map contains actual values, including credentials, and is not safe to include in diagnostic logs.
	 * @return Explicit parsed input values; missing properties are absent from the map.
	 */
	@NotNull
	Map<ConfigLoader.Property, Object> asMap();

	@Override
	String toString();
}
