package org.simplejavamail.internal.openpgpsupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

class MimeEntityHeaderSeparatorTest {

    private static final String CONTENT_HEADERS = "Content-Type: multipart/mixed; boundary=inside";
    private static final String BODY = "--inside\r\nContent-Type: text/plain\r\n\r\nFirst part\r\n"
            + "--inside\r\nContent-Type: application/octet-stream\r\n\r\nSecond part\r\n--inside--\r\n";

    @ParameterizedTest(name = "{0}")
    @MethodSource("separators")
    void contentExtractionPreservesNestedMimeAfterTheFirstEmptyLine(final String name, final String separator) {
        final byte[] message = ("Subject: Outer metadata\r\n" + CONTENT_HEADERS + separator + BODY).getBytes(ISO_8859_1);

        final byte[] entity = MimeEntitySupport.contentEntity(message);

        assertThat(entity).containsExactly((CONTENT_HEADERS + "\r\n\r\n" + BODY).getBytes(ISO_8859_1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("separators")
    void restoredContentKeepsNestedMimeAndTheProtectedOuterHeaders(final String name, final String separator) {
        final byte[] protectedMessage = ("Subject: Protected outer metadata\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nCiphertext").getBytes(ISO_8859_1);
        final byte[] content = (CONTENT_HEADERS + separator + BODY).getBytes(ISO_8859_1);

        final byte[] restored = MimeEntitySupport.restoreMessage(protectedMessage, content);

        assertThat(restored).containsExactly(("Subject: Protected outer metadata\r\n" + CONTENT_HEADERS
                + "\r\n\r\n" + BODY).getBytes(ISO_8859_1));
    }

    private static Stream<Arguments> separators() {
        return Stream.of(Arguments.of("CRLF then CRLF", "\r\n\r\n"), Arguments.of("LF then LF", "\n\n"),
                Arguments.of("CRLF then LF", "\r\n\n"), Arguments.of("LF then CRLF", "\n\r\n"));
    }
}
