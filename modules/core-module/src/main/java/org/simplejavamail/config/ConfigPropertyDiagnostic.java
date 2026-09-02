package org.simplejavamail.config;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Safe diagnostic description of one resolved Simple Java Mail configuration property.
 * <p>
 * This type deliberately exposes only a display value. Sensitive values are replaced with {@value #REDACTED_VALUE}, and control characters in values and
 * source names are escaped so an entry can be included in ordinary diagnostic logs.
 */
public final class ConfigPropertyDiagnostic {

	static final String REDACTED_VALUE = "<redacted>";

	private final ConfigDiagnosticGroup group;
	private final String propertyName;
	private final String displayValue;
	private final String sourceName;
	private final boolean redacted;

	ConfigPropertyDiagnostic(@NotNull final ConfigDiagnosticGroup group,
			@NotNull final String propertyName,
			@NotNull final String displayValue,
			@NotNull final String sourceName,
			final boolean redacted) {
		this.group = requireNonNull(group, "group");
		this.propertyName = escapeControlCharacters(requireNonNull(propertyName, "propertyName"));
		this.displayValue = escapeControlCharacters(requireNonNull(displayValue, "displayValue"));
		this.sourceName = escapeControlCharacters(requireNonNull(sourceName, "sourceName"));
		this.redacted = redacted;
	}

	/**
	 * @return The functional section this property belongs to.
	 */
	@NotNull
	public ConfigDiagnosticGroup getGroup() {
		return group;
	}

	/**
	 * @return The canonical {@code simplejavamail.*} property name.
	 */
	@NotNull
	public String getPropertyName() {
		return propertyName;
	}

	/**
	 * @return The normalized, log-safe display value, or {@value #REDACTED_VALUE} when the value is sensitive.
	 */
	@NotNull
	public String getDisplayValue() {
		return displayValue;
	}

	/**
	 * @return The log-safe diagnostic name of the source that supplied the winning value.
	 */
	@NotNull
	public String getSourceName() {
		return sourceName;
	}

	/**
	 * @return Whether the resolved value was considered sensitive and replaced with {@value #REDACTED_VALUE}.
	 */
	public boolean isRedacted() {
		return redacted;
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ConfigPropertyDiagnostic)) {
			return false;
		}
		final ConfigPropertyDiagnostic that = (ConfigPropertyDiagnostic) other;
		return redacted == that.redacted
				&& group == that.group
				&& propertyName.equals(that.propertyName)
				&& displayValue.equals(that.displayValue)
				&& sourceName.equals(that.sourceName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(group, propertyName, displayValue, sourceName, redacted);
	}

	@Override
	public String toString() {
		return propertyName + " = " + displayValue + " (source: " + sourceName + ")";
	}

	private static String escapeControlCharacters(final String value) {
		final StringBuilder escaped = new StringBuilder(value.length());
		for (int characterIndex = 0; characterIndex < value.length(); characterIndex++) {
			final char character = value.charAt(characterIndex);
			switch (character) {
				case '\r':
					escaped.append("\\r");
					break;
				case '\n':
					escaped.append("\\n");
					break;
				case '\t':
					escaped.append("\\t");
					break;
				default:
					appendPrintableCharacter(escaped, character);
			}
		}
		return escaped.toString();
	}

	private static void appendPrintableCharacter(final StringBuilder escaped, final char character) {
		if (Character.isISOControl(character)) {
			escaped.append(String.format("\\u%04x", (int) character));
		} else {
			escaped.append(character);
		}
	}
}
