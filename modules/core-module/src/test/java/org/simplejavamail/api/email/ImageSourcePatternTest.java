package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ImageSourcePatternTest {

    @ParameterizedTest
    @MethodSource("quotedSources")
    void capturesTheWholeSourceAndPreservesTheOriginalDelimiters(final String html, final String source,
            final String tagStart, final String closingQuote) {
        final Matcher matcher = EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher(html);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.groupCount()).isEqualTo(3);
        assertThat(matcher.group("imageTagStart")).isEqualTo(tagStart);
        assertThat(matcher.group("src")).isEqualTo(source);
        assertThat(matcher.group("imageSrcEnd")).isEqualTo(closingQuote);
        assertThat(matcher.group(1)).isEqualTo(tagStart);
        assertThat(matcher.group(2)).isEqualTo(source);
        assertThat(matcher.group(3)).isEqualTo(closingQuote);
        assertThat(matcher.find()).isFalse();
    }

    @Test
    void anEmptySourceCannotConsumeTheClosingQuoteOrTheNextImage() {
        final Matcher matcher = EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img src=\"\"><img src='logo.png'>");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("src")).isEqualTo("logo.png");
        assertThat(matcher.group("imageSrcEnd")).isEqualTo("'");
        assertThat(matcher.find()).isFalse();
    }

    @Test
    void theOppositeQuoteCannotFinishAnUnclosedSource() {
        assertThat(EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img src=\"it's.png>").find()).isFalse();
        assertThat(EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img src='say\"hello.png>").find()).isFalse();
    }

    @Test
    void aLongSourceRetainsItsOppositeQuote() {
        final String source = "a".repeat(20000) + "'s.png";
        final Matcher matcher = EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img src=\"" + source + "\">");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group("src")).isEqualTo(source);
        assertThat(matcher.group("imageSrcEnd")).isEqualTo("\"");
    }

    @Test
    void sourceTextInsideAnotherAttributeCannotInventASource() {
        assertThat(EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img alt=\"example src='decoy.png'\">").find()).isFalse();
        assertThat(EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img alt='example src=\"decoy.png\"'>").find()).isFalse();
    }

    @Test
    void aLongAttributePrefixRetainsTheActualSource() {
        final String before = "<img" + manyAttributes() + " src='";
        final Matcher matcher = EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher(before + "logo.png'>");

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.groupCount()).isEqualTo(3);
        assertThat(matcher.group("imageTagStart")).isEqualTo(before);
        assertThat(matcher.group("src")).isEqualTo("logo.png");
        assertThat(matcher.group("imageSrcEnd")).isEqualTo("'");
    }

    @Test
    void aLongAttributePrefixCannotInventAnAbsentSource() {
        assertThat(EmailPopulatingBuilder.IMG_SRC_PATTERN.matcher("<img" + manyAttributes() + ">").find()).isFalse();
    }

    private static String manyAttributes() {
        final StringBuilder attributes = new StringBuilder();
        for (int index = 0; index < 2000; index++) {
            attributes.append(" data-item-").append(index).append("='ordinary-value'");
        }
        return attributes.toString();
    }

    private static Stream<Arguments> quotedSources() {
        return Stream.of(
                Arguments.of("<img src=\"it's.png\">", "it's.png", "<img src=\"", "\""),
                Arguments.of("<img src='say\"hello.png'>", "say\"hello.png", "<img src='", "'"),
                Arguments.of("<img src=\"''logo.png\">", "''logo.png", "<img src=\"", "\""),
                Arguments.of("<img src='\"\"logo.png'>", "\"\"logo.png", "<img src='", "'"),
                Arguments.of("<img src=\"logo.png\" title='caption'>", "logo.png", "<img src=\"", "\""),
                Arguments.of("<IMG id='banner'\n SRC = 'logo.png'>", "logo.png", "<IMG id='banner'\n SRC = '", "'"),
                Arguments.of("<img src=\"folder/it's\nlogo.png\">", "folder/it's\nlogo.png", "<img src=\"", "\""),
                Arguments.of("<img alt=\"example src='decoy.png'\" src=\"logo.png\">", "logo.png",
                        "<img alt=\"example src='decoy.png'\" src=\"", "\""),
                Arguments.of("<img alt='example src=\"decoy.png\"' src='logo.png'>", "logo.png",
                        "<img alt='example src=\"decoy.png\"' src='", "'"),
                Arguments.of("<img title=\"a > b\" src=\"logo.png\">", "logo.png", "<img title=\"a > b\" src=\"", "\""),
                Arguments.of("<img title='a > b' src='logo.png'>", "logo.png", "<img title='a > b' src='", "'"),
                Arguments.of("<IMG loading=lazy hidden\n data-src=decoy.png SRC = \"logo.png\">", "logo.png",
                        "<IMG loading=lazy hidden\n data-src=decoy.png SRC = \"", "\""));
    }
}
