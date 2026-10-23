package org.simplejavamail.internal.util;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.parallel.Resources.LOCALE;

@ResourceLock(LOCALE)
class ImageMimeTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void filenameClassificationAndCharsetNormalizationAreIndependentOfTheDefaultLocale(final String languageTag) {
        final Locale previous = Locale.getDefault();
        final Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        final Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            for (final Map.Entry<String, String> image : Map.of(
                    "LOGO.GIF", "image/gif",
                    "logo.gif", "image/gif",
                    "photo.JFIF", "image/jpeg",
                    "scan.TIFF", "image/tiff",
                    "icon.ICO", "image/x-icon",
                    "image.unknown", "application/octet-stream").entrySet()) {
                assertThat(ImageMimeType.getContentType(image.getKey())).as(image.getKey()).isEqualTo(image.getValue());
                assertThat(ImageMimeType.getContentType(image.getKey(), "ISO-8859-1")).as(image.getKey())
                        .isEqualTo(image.getValue());
            }
            assertThat(ImageMimeType.getContentType("notes.TXT", "ISO-8859-1"))
                    .isEqualTo("text/plain;charset=iso-8859-1");
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }
}
