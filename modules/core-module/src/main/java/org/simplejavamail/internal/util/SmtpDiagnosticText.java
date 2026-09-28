package org.simplejavamail.internal.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;

import static java.nio.charset.CodingErrorAction.REPLACE;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Keeps server-controlled diagnostic fields single-line and bounded without retaining a raw transcript. */
public final class SmtpDiagnosticText {
    private static final int MAX_DISPLAY_LENGTH = 2048;
    // Workspace sizes, not report limits. A Unicode escape is the longest replacement written by appendEscapedCharacter.
    private static final int ENCODING_CHUNK_CHARACTERS = 1024;
    private static final int MAX_ESCAPED_CHARACTER_LENGTH = 6;

    private SmtpDiagnosticText() {
    }

    public static String display(final String value) {
        return escape(value, MAX_DISPLAY_LENGTH);
    }

    /** Capability snapshots have a shared budget, so individual parameters must not be silently shortened. */
    public static String escape(final CharSequence value) {
        return escape(value, Integer.MAX_VALUE);
    }

    private static String escape(final CharSequence value, final int characterLimit) {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            if (result.length() >= characterLimit) {
                return result.append("...").toString();
            }
            appendEscapedCharacter(result, value.charAt(index));
        }
        return result.toString();
    }

    /** Counts bounded chunks of the escaped text; returns remainingBytes + 1 when the complete value would not fit. */
    static int escapedUtf8Length(final CharSequence value, final int remainingBytes) {
        final CharsetEncoder encoder = UTF_8.newEncoder().onMalformedInput(REPLACE).onUnmappableCharacter(REPLACE);
        final ByteBuffer encodedChunk = ByteBuffer.allocate((int) (ENCODING_CHUNK_CHARACTERS * encoder.maxBytesPerChar()));
        final StringBuilder escapedChunk = new StringBuilder(ENCODING_CHUNK_CHARACTERS);
        int inputIndex = 0;
        int bytes = 0;
        do {
            while (inputIndex < value.length() && escapedChunk.length() <= ENCODING_CHUNK_CHARACTERS - MAX_ESCAPED_CHARACTER_LENGTH) {
                appendEscapedCharacter(escapedChunk, value.charAt(inputIndex++));
            }
            final boolean endOfInput = inputIndex == value.length();
            final CharBuffer characters = CharBuffer.wrap(escapedChunk);
            encodedChunk.clear().limit(Math.min(encodedChunk.capacity(), remainingBytes - bytes));
            if (encoder.encode(characters, encodedChunk, endOfInput).isOverflow()
                    || (endOfInput && encoder.flush(encodedChunk).isOverflow())) {
                return remainingBytes + 1;
            }
            bytes += encodedChunk.position();
            // Keep whatever the JDK has not consumed (for example, half a surrogate pair) for the next chunk.
            escapedChunk.delete(0, characters.position());
        } while (inputIndex < value.length());
        return bytes;
    }

    private static void appendEscapedCharacter(final StringBuilder result, final char character) {
        switch (character) {
            case '\n': result.append("\\n"); break;
            case '\r': result.append("\\r"); break;
            case '\t': result.append("\\t"); break;
            default:
                if (requiresUnicodeEscape(character)) {
                    result.append(String.format("\\u%04x", (int) character));
                } else {
                    result.append(character);
                }
        }
    }

    private static boolean requiresUnicodeEscape(final char character) {
        return Character.isISOControl(character) || Character.getType(character) == Character.FORMAT
                || character == '\u2028' || character == '\u2029';
    }
}
