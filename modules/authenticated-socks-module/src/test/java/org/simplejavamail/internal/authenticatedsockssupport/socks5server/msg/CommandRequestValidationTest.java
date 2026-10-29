package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRequestValidationTest {

    @ParameterizedTest(name = "version={0}, reserved={1}")
    @MethodSource("invalidHeaders")
    void rejectsInvalidFixedHeaders(final int version, final int reserved) {
        final byte[] request = {(byte) version, 1, (byte) reserved, 1, 127, 0, 0, 1, 0, 25};
        assertThatThrownBy(() -> new CommandMessage().read(new ByteArrayInputStream(request))).isInstanceOf(SocksException.class);
    }

    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(Arguments.of(0, 0), Arguments.of(4, 0), Arguments.of(255, 0),
                Arguments.of(5, 1), Arguments.of(5, 128), Arguments.of(5, 255));
    }

    @ParameterizedTest(name = "{0} truncated after {1} bytes")
    @MethodSource("truncatedRequests")
    void everyMandatoryRequestByteRequiresARealOctet(final String family, final int length, final byte[] request) {
        assertThatThrownBy(() -> new CommandMessage().read(new ByteArrayInputStream(request))).isInstanceOf(IOException.class);
    }

    private static Stream<Arguments> truncatedRequests() {
        final byte[] ipv4 = {5, 1, 0, 1, 127, 0, 0, 1, 0, 25};
        final byte[] ipv6 = new byte[22];
        ipv6[0] = 5;
        ipv6[1] = 1;
        ipv6[3] = 4;
        ipv6[19] = 1;
        ipv6[21] = 25;
        final byte[] domain = {5, 1, 0, 3, 3, 'm', 'a', 'i', 0, 25};
        return Stream.concat(Stream.concat(truncations("IPv4", ipv4), truncations("IPv6", ipv6)), truncations("domain", domain));
    }

    private static Stream<Arguments> truncations(final String family, final byte[] complete) {
        return IntStream.range(0, complete.length).mapToObj(length -> Arguments.of(family, length, Arrays.copyOf(complete, length)));
    }
}
