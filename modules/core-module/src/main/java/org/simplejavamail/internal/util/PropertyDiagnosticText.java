package org.simplejavamail.internal.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Shares the existing configuration-name redaction rules with automatic Session and Mailer descriptions. */
@ApiStatus.Internal
@UtilityClass
public class PropertyDiagnosticText {

    private static final String[] SENSITIVE_PROPERTY_MARKERS = {
            "password", "passwd", "passphrase", "pwd", "secret", "token", "bearer", "credential", "authorization",
            "privatekey", "accesskey", "apikey", "keystore", "certificate", "cert"
    };

    public static boolean isSensitiveName(final String propertyName) {
        final String normalizedName = normalizePropertyName(propertyName);
        for (String marker : SENSITIVE_PROPERTY_MARKERS) {
            if (normalizedName.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Redact before converting values to text: even an application's provider object can contain credentials. */
    public static String render(final Properties properties) {
        final Map<String, String> displayed = new TreeMap<>();
        properties.forEach((key, value) -> {
            if (key instanceof String) {
                final String name = (String) key;
                displayed.put(escapeControlCharacters(name), isSensitiveName(name) ? "<redacted>" : renderValue(value));
            }
        });
        return displayed.toString();
    }

    /** Formats known immutable scalars; borrowed application objects are described without calling their methods. */
    public static String renderValue(final Object value) {
        final String text;
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            text = String.valueOf(value);
        } else {
            text = "<" + value.getClass().getName() + ">";
        }
        return escapeControlCharacters(text);
    }

    private static String normalizePropertyName(final String propertyName) {
        final String lowerCaseName = propertyName.toLowerCase(Locale.ROOT);
        final StringBuilder normalizedName = new StringBuilder(lowerCaseName.length());
        for (int characterIndex = 0; characterIndex < lowerCaseName.length(); characterIndex++) {
            final char character = lowerCaseName.charAt(characterIndex);
            if (Character.isLetterOrDigit(character)) {
                normalizedName.append(character);
            }
        }
        return normalizedName.toString();
    }
}
