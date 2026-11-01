package org.simplejavamail.internal.html;

import lombok.Value;
import org.jetbrains.annotations.ApiStatus;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;

import java.util.function.Consumer;

/** Reads HTML-decoded attribute values and their original source spans without serializing or changing the document. */
@ApiStatus.Internal
public final class HtmlAttributeValues {
    private HtmlAttributeValues() {
    }

    public static void forEachIn(final String html, final Consumer<AttributeValue> consumer) {
        try (StreamParser parser = new StreamParser(Parser.htmlParser().setTrackPosition(true))) {
            parser.parse(html, "");
            for (Element element : (Iterable<Element>) parser.stream()::iterator) {
                for (Attribute attribute : element.attributes()) {
                    final Range range = attribute.sourceRange().valueRange();
                    if (range.isTracked()) {
                        final int start = range.start().pos();
                        final int end = range.end().pos();
                        final char delimiter = start > 0 ? html.charAt(start - 1) : 0;
                        final boolean quoted = (delimiter == '\'' || delimiter == '"')
                                && end < html.length() && html.charAt(end) == delimiter;
                        consumer.accept(new AttributeValue(element.normalName(), attribute.getKey(), attribute.getValue(), start, end, quoted));
                    }
                }
                // Completed nodes can be discarded; the input String and tracked source offsets remain authoritative.
                element.remove();
            }
        }
    }

    @Value
    public static class AttributeValue {
        String elementName;
        String name;
        String value;
        int start;
        int end;
        boolean quoted;
    }
}
