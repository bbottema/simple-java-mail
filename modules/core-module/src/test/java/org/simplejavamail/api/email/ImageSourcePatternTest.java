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

    private static Stream<Arguments> quotedSources() {
        return Stream.of(
                Arguments.of("<img src=\"it's.png\">", "it's.png", "<img src=\"", "\""),
                Arguments.of("<img src='say\"hello.png'>", "say\"hello.png", "<img src='", "'"),
                Arguments.of("<img src=\"''logo.png\">", "''logo.png", "<img src=\"", "\""),
                Arguments.of("<img src='\"\"logo.png'>", "\"\"logo.png", "<img src='", "'"),
                Arguments.of("<img src=\"logo.png\" title='caption'>", "logo.png", "<img src=\"", "\""),
                Arguments.of("<IMG id='banner'\n SRC = 'logo.png'>", "logo.png", "<IMG id='banner'\n SRC = '", "'"),
                Arguments.of("<img src=\"folder/it's\nlogo.png\">", "folder/it's\nlogo.png", "<img src=\"", "\""));
    }
}
