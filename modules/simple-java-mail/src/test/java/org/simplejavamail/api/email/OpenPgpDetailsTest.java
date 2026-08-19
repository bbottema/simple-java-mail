/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
