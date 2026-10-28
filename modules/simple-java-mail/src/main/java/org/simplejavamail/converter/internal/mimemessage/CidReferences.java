package org.simplejavamail.converter.internal.mimemessage;

import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interprets CID URL identities without changing the source HTML or MIME resources. */
final class CidReferences {

    private static final Pattern SCHEME = Pattern.compile("(?<![A-Za-z0-9+.-])(?i:cid):");
    private static final Pattern SRCSET_ATTRIBUTE = Pattern.compile("(?i)\\bsrcset[\\t\\n\\f\\r ]*=");

    private CidReferences() {
    }

    static Set<String> inHtml(final String html) {
        final Set<String> references = new HashSet<>();
        if (html.indexOf('&') < 0 && !SRCSET_ATTRIBUTE.matcher(html).find()) {
            collectReferences(html, references);
            return references;
        }
        // Attribute values are HTML-decoded by the parser. Keep raw-text/CSS inspection separate, and never serialize its DOM.
        final StringBuilder literalText = new StringBuilder(html);
        try (StreamParser parser = new StreamParser(Parser.htmlParser().setTrackPosition(true))) {
            parser.parse(html, "");
            for (Element element : (Iterable<Element>) parser.stream()::iterator) {
                for (Attribute attribute : element.attributes()) {
                    final Range range = attribute.sourceRange().valueRange();
                    if (range.isTracked() && (isSrcset(attribute)
                            || html.substring(range.start().pos(), range.end().pos()).indexOf('&') >= 0)) {
                        collectAttributeReferences(attribute, references);
                        // Do not also claim a resource matching the attribute's encoded source spelling.
                        for (int index = range.start().pos(); index < range.end().pos(); index++) {
                            literalText.setCharAt(index, ' ');
                        }
                    }
                }
                // Completed nodes are no longer needed. The input String and source offsets remain authoritative.
                element.remove();
            }
        }
        collectReferences(literalText.toString(), references);
        return references;
    }

    private static boolean isSrcset(final Attribute attribute) {
        return "srcset".equalsIgnoreCase(attribute.getKey());
    }

    private static void collectAttributeReferences(final Attribute attribute, final Set<String> references) {
        final String value = attribute.getValue();
        if (isSrcset(attribute)) {
            collectSrcsetReferences(value, references);
            return;
        }
        final String uri = value.strip();
        final Matcher scheme = SCHEME.matcher(uri);
        if (scheme.lookingAt()) {
            // A direct URI attribute has no source quote delimiter after HTML decoding; quotes may be part of its identity.
            addDecodedReference(uri.substring(scheme.end()), references);
        } else {
            collectReferences(value, references);
        }
    }

    private static void collectSrcsetReferences(final String value, final Set<String> references) {
        int position = 0;
        while (position < value.length()) {
            while (position < value.length() && (isAsciiWhitespace(value.charAt(position)) || value.charAt(position) == ',')) {
                position++;
            }
            final int urlStart = position;
            while (position < value.length() && !isAsciiWhitespace(value.charAt(position))) {
                position++;
            }
            int urlEnd = position;
            // Srcset commas inside a URL belong to it; only trailing commas end a candidate without descriptors.
            while (urlEnd > urlStart && value.charAt(urlEnd - 1) == ',') {
                urlEnd--;
            }
            final String uri = value.substring(urlStart, urlEnd);
            final Matcher scheme = SCHEME.matcher(uri);
            if (scheme.lookingAt()) {
                addDecodedReference(uri.substring(scheme.end()), references);
            }
            if (urlEnd == position) {
                position = afterSrcsetDescriptors(value, position);
            }
        }
    }

    private static int afterSrcsetDescriptors(final String value, final int start) {
        boolean inParentheses = false;
        for (int position = start; position < value.length(); position++) {
            final char current = value.charAt(position);
            if (current == ',' && !inParentheses) {
                return position + 1;
            }
            if (current == '(') {
                inParentheses = true;
            } else if (current == ')') {
                inParentheses = false;
            }
        }
        return value.length();
    }

    private static boolean isAsciiWhitespace(final char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\f' || character == '\r';
    }

    private static void addDecodedReference(final String reference, final Set<String> references) {
        final String cid = decode(reference);
        if (cid != null && !cid.isEmpty()) {
            references.add(cid);
        }
    }

    private static void collectReferences(final String html, final Set<String> references) {
        final Matcher scheme = SCHEME.matcher(html);
        int searchFrom = 0;
        while (scheme.find(searchFrom)) {
            final int start = scheme.end();
            final int end = referenceEnd(html, start, scheme.start());
            addDecodedReference(html.substring(start, end), references);
            searchFrom = end;
        }
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
