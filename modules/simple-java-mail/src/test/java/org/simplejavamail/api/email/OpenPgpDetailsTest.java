package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpDetailsTest {

    @Test
    void retainsLargeProtectedMessagesInMemoryWithoutExposingItsInternalArray() {
        final byte[] protectedMessage = new byte[2 * 1024 * 1024];
        Arrays.fill(protectedMessage, (byte) 42);
        final OpenPgpDetails details = OpenPgpDetails.builder()
                .originalProtectedMessage(protectedMessage)
                .build();

        protectedMessage[0] = 1;
        final byte[] firstRead = details.getOriginalProtectedMessage();
        firstRead[1] = 2;

        assertThat(firstRead).hasSize(2 * 1024 * 1024);
        assertThat(details.getOriginalProtectedMessage()[0]).isEqualTo((byte) 42);
        assertThat(details.getOriginalProtectedMessage()[1]).isEqualTo((byte) 42);
    }
}
