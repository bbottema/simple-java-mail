package org.simplejavamail.internal.util;

/** Keeps server-controlled diagnostic fields single-line and bounded without retaining a raw transcript. */
public final class SmtpDiagnosticText {
    private static final int MAX_DISPLAY_LENGTH = 2048;

    private SmtpDiagnosticText() {
    }

    public static String display(final String value) {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            if (result.length() >= MAX_DISPLAY_LENGTH) {
                return result.append("...").toString();
            }
            final char character = value.charAt(index);
            switch (character) {
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                            || character == '\u2028' || character == '\u2029') {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.toString();
    }
}
