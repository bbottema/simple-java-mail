package org.simplejavamail.converter.internal.mimemessage;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets CID URL identities without changing the source HTML or MIME resources. */
final class CidReferences {

    private static final Pattern SCHEME = Pattern.compile("(?<![A-Za-z0-9+.-])(?i:cid):");

    private CidReferences() {
    }

    static Set<String> inHtml(final String html) {
        final Set<String> references = new HashSet<>();
        final Matcher scheme = SCHEME.matcher(html);
        int searchFrom = 0;
        while (scheme.find(searchFrom)) {
            final int start = scheme.end();
            final int end = referenceEnd(html, start, scheme.start());
            final String cid = decode(html.substring(start, end));
            if (cid != null && !cid.isEmpty()) {
                references.add(cid);
            }
            searchFrom = end;
        }
        return references;
    }

    private static int referenceEnd(final String html, final int start, final int schemeStart) {
        final char beforeScheme = schemeStart == 0 ? 0 : html.charAt(schemeStart - 1);
        final char quote = beforeScheme == '\'' || beforeScheme == '"' ? beforeScheme : 0;
        int end = start;
        while (end < html.length()) {
            final char current = html.charAt(end);
            if (Character.isWhitespace(current) || current == '<' || current == '>'
                    || (quote != 0 ? current == quote : current == '\'' || current == '"' || current == '(' || current == ')')) {
                break;
            }
            end++;
        }
        return end;
    }

    @Nullable
    private static String decode(final String reference) {
        final StringBuilder decoded = new StringBuilder(reference.length());
        for (int index = 0; index < reference.length(); index++) {
            final char current = reference.charAt(index);
            if (current != '%') {
                decoded.append(current);
                continue;
            }
            if (index + 2 >= reference.length()) {
                return null;
            }
            final int high = hexDigit(reference.charAt(index + 1));
            final int low = hexDigit(reference.charAt(index + 2));
            // RFC 2392 percent escapes represent US-ASCII octets, not form data or a second round of escaping.
            if (high < 0 || low < 0 || high > 7) {
                return null;
            }
            decoded.append((char) ((high << 4) | low));
            index += 2;
        }
        return decoded.toString();
    }

    private static int hexDigit(final char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
