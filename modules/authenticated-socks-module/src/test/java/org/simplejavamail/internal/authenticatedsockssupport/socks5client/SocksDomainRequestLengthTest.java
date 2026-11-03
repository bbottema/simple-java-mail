package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class SocksDomainRequestLengthTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "ascii", "utf8"})
    void rejectsAnUnrepresentableEncodedHostBeforeWritingAnyRequestBytes(final String kind) {
        final String host = kind.isEmpty() ? "" : "ascii".equals(kind) ? "a".repeat(256) : "\u00e9".repeat(128);
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        assertThrows(SocksException.class, () -> SocksCommandSender.send(socket(sent, new byte[0]), host, 587));
        assertEquals(0, sent.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "ascii", "utf8"})
    void preservesOriginalEncodedBytesAtTheOneAnd255OctetBoundaries(final String kind) throws Exception {
        final String host = "ascii".equals(kind) ? "a".repeat(255) : "utf8".equals(kind) ? "\u00e9".repeat(127) + "a" : kind;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final ByteArrayInputStream reply = new ByteArrayInputStream(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0, '2', '2', '0'});
        SocksCommandSender.send(new Socket() {
            @Override public InputStream getInputStream() { return reply; }
            @Override public OutputStream getOutputStream() { return sent; }
        }, host, 587);
        final byte[] encodedHost = host.getBytes(UTF_8);
        assertEquals(encodedHost.length, sent.toByteArray()[4] & 255);
        assertArrayEquals(encodedHost, Arrays.copyOfRange(sent.toByteArray(), 5, 5 + encodedHost.length));
        assertArrayEquals(new byte[] {2, 75}, Arrays.copyOfRange(sent.toByteArray(), 5 + encodedHost.length, sent.size()));
        assertArrayEquals(new byte[] {'2', '2', '0'}, reply.readAllBytes());
    }

    private static Socket socket(final ByteArrayOutputStream sent, final byte[] reply) {
        return new Socket() {
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply); }
            @Override public OutputStream getOutputStream() { return sent; }
        };
    }
}
