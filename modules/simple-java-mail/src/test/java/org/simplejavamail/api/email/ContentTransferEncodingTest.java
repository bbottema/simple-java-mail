package org.simplejavamail.api.email;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ContentTransferEncodingTest {

    @Test
    public void byEncoder() {
        assertThat(ContentTransferEncoding.byEncoder("BASE_64")).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(ContentTransferEncoding.byEncoder("B")).isEqualTo(ContentTransferEncoding.B);
        assertThat(ContentTransferEncoding.byEncoder("b")).isEqualTo(ContentTransferEncoding.B);
        assertThat(ContentTransferEncoding.byEncoder("x-uuencode")).isEqualTo(ContentTransferEncoding.X_UU);
        assertThat(ContentTransferEncoding.byEncoder("x_uuencode")).isEqualTo(ContentTransferEncoding.X_UU);
        assertThat(ContentTransferEncoding.byEncoder("QUOTED-PRINTABLE")).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        assertThatThrownBy(() -> ContentTransferEncoding.byEncoder("moomoo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown content transfer encoder: moomoo");
    }
}