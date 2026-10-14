package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * An immutable, thread-safe snapshot of resolved Simple Java Mail configuration.
 * <p>
 * A snapshot contains only explicitly configured values. Runtime defaults such as generated cluster identifiers remain builder concerns. All input and
 * wildcard maps are detached during construction and all returned maps are unmodifiable.
 * <p>
 * The typed property getters and {@link #asMap()} return actual configured values and may therefore expose credentials. Use {@link #getDiagnostics()} for
 * structured output intended for diagnostic logs.
 * <p>
 * Properties under {@code simplejavamail.locked.} supply their ordinary counterpart's effective value and remain restrictions on later customization
 * in every Mailer created from this snapshot's factory. Diagnostics retain the locked name and its source. An independently configured factory remains
 * possible; these local restrictions are not a security sandbox.
 */
public interface SimpleJavaMailConfig {

	/**
	 * @return Whether this snapshot contains a non-blank value for the property.
	 */
	boolean hasProperty(@NotNull final ConfigLoader.Property property);

	/**
	 * Returns the property using its schema-declared type.
	 */
	@Nullable
	<T> T getProperty(@NotNull final ConfigLoader.Property property);

	/**
	 * Returns a property whose schema type is {@link String}.
	 */
	@Nullable
	String getStringProperty(@NotNull final ConfigLoader.Property property);

	/**
	 * Returns a property whose schema type is {@link Integer}.
	 */
	@Nullable
	Integer getIntegerProperty(@NotNull final ConfigLoader.Property property);

	/**
	 * Returns a property whose schema type is {@link Boolean}.
	 */
	@Nullable
	Boolean getBooleanProperty(@NotNull final ConfigLoader.Property property);

	/**
	 * @return The diagnostic source name for the winning property, or {@code null} when the property is absent.
	 */
	@Nullable
	String getPropertySource(@NotNull final ConfigLoader.Property property);

	/**
	 * @return An immutable, grouped and safe-to-log description of every configured value and its winning source.
	 * @see org.simplejavamail.api.mailer.Mailer#getOperationalConfig()
	 */
	@NotNull
	ConfigDiagnostics getDiagnostics();

	/**
	 * Returns the explicit value when non-blank, otherwise the configured value, otherwise the supplied fallback.
	 */
	@Nullable
	<T> T valueOrProperty(@Nullable final T explicitValue,
			@NotNull final ConfigLoader.Property property,
			@Nullable final T fallbackValue);

	/**
	 * See {@link #valueOrProperty(Object, ConfigLoader.Property, Object)}.
	 */
	@Nullable
	<T> T valueOrProperty(@Nullable final T explicitValue, @NotNull final ConfigLoader.Property property);

	/**
	 * @return An unmodifiable view of all resolved property values. This map contains actual values and is not safe to include in diagnostic logs.
	 */
	@NotNull
	Map<ConfigLoader.Property, Object> asMap();

	@Override
	String toString();
}
