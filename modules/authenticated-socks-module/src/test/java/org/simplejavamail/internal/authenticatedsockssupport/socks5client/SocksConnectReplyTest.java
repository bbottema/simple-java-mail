package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocksConnectReplyTest {

    @Test
    void rejectsASuccessHeaderWithoutItsBoundAddressAndPort() {
        final ReplySocket socket = new ReplySocket(new byte[] {5, 0, 0, 1}, false);

        assertThrows(EOFException.class, () -> SocksCommandSender.send(socket, "smtp.example.com", 25));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successfulReplies")
    void rejectsEveryTruncatedReply(final String name, final byte[] reply) {
        for (int length = reply.length - 1; length >= 0; length--) {
            final ReplySocket socket = new ReplySocket(Arrays.copyOf(reply, length), false);
            assertThrows(EOFException.class, () -> SocksCommandSender.send(socket, "smtp.example.com", 25),
                    name + " reply truncated after " + length + " bytes");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 6, 255})
    void rejectsAnIncorrectProtocolVersion(final int version) {
        final byte[] reply = ipv4Reply();
        reply[0] = (byte) version;

        assertConnectionFailure(reply);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 128, 255})
    void rejectsANonzeroReservedByte(final int reserved) {
        final byte[] reply = ipv4Reply();
        reply[2] = (byte) reserved;

        assertConnectionFailure(reply);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 5, 255})
    void rejectsAnUnsupportedAddressType(final int addressType) {
        final byte[] reply = ipv4Reply();
        reply[3] = (byte) addressType;

        assertConnectionFailure(reply);
    }

    @ParameterizedTest
    @CsvSource({
            "1, General SOCKS server failure",
            "2, Connection not allowed by ruleset",
            "3, Network unreachable",
            "4, Host unreachable",
            "5, Connection refused",
            "6, TTL expired",
            "7, Command not supported",
            "8, Address type not supported",
            "255, Unknown reply"
    })
    void retainsProxyRejectionMeaning(final int status, final String message) {
        final byte[] reply = ipv4Reply();
        reply[1] = (byte) status;

        assertEquals(message, assertConnectionFailure(reply).getMessage());
    }

    @ParameterizedTest(name = "{0}, fragmented={2}")
    @MethodSource("successfulReplyFragments")
    void consumesOnlyTheCompleteReply(final String name, final byte[] reply, final boolean fragmented) throws Exception {
        final byte[] followedBySmtp = Arrays.copyOf(reply, reply.length + 3);
        System.arraycopy(new byte[] {'2', '2', '0'}, 0, followedBySmtp, reply.length, 3);
        final ReplySocket socket = new ReplySocket(followedBySmtp, fragmented);

        SocksCommandSender.send(socket, "smtp.example.com", 25);

        assertArrayEquals(new byte[] {'2', '2', '0'}, socket.reply.readAllBytes(), name);
    }

    @Test
    void retainsAnUnderlyingReadFailure() {
        final IOException original = new IOException("proxy read failed");
        final Socket socket = new ReplySocket(new byte[0], false) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() throws IOException { throw original; }
                };
            }
        };

        assertSame(original, assertThrows(IOException.class, () -> SocksCommandSender.send(socket, "smtp.example.com", 25)));
    }

    @Test
    void sendsAnUnresolvedDestinationAsADomainRequest() throws Exception {
        final String host = "smtp.proxy-only.invalid";
        final ReplySocket socket = new ReplySocket(ipv4Reply(), false);

        SocksCommandSender.send(socket, InetSocketAddress.createUnresolved(host, 587));

        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(new byte[] {5, 1, 0, 3, (byte) host.length()});
        expected.write(host.getBytes(UTF_8));
        expected.write(new byte[] {2, 75});
        assertArrayEquals(expected.toByteArray(), socket.request.toByteArray());
    }

    @Test
    @Timeout(10)
    void rejectsATruncatedConnectReplyAfterRealProxyAuthentication() throws Exception {
        final ExecutorService remoteProxy = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Future<Void> exchange = remoteProxy.submit(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    final DataInputStream input = new DataInputStream(peer.getInputStream());
                    final OutputStream output = peer.getOutputStream();
                    assertArrayEquals(new byte[] {5, 2, 0, 2}, input.readNBytes(4));
                    output.write(new byte[] {5, 2});
                    output.flush();
                    assertEquals(1, input.readUnsignedByte());
                    assertEquals("proxy-user", new String(input.readNBytes(input.readUnsignedByte()), UTF_8));
                    assertEquals("proxy-secret", new String(input.readNBytes(input.readUnsignedByte()), UTF_8));
                    output.write(new byte[] {1, 0});
                    output.flush();
                    assertArrayEquals(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, 0, 25}, input.readNBytes(10));
                    output.write(new byte[] {5, 0, 0, 1});
                    output.flush();
                    peer.shutdownOutput();
                }
                return null;
            });

            final Socks5 proxy = new Socks5(new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()));
            proxy.setCredentials(new ProxyCredentials("proxy-user", "proxy-secret"));
            try (Socket rawSocket = new Socket()) {
                assertThrows(EOFException.class, () -> new SocksSocket(proxy, rawSocket,
                        new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 25)));
            }
            exchange.get(5, TimeUnit.SECONDS);
        } finally {
            remoteProxy.shutdownNow();
            assertTrue(remoteProxy.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static SocksException assertConnectionFailure(final byte[] reply) {
        final SocksException failure = assertThrows(SocksException.class,
                () -> SocksCommandSender.send(new ReplySocket(reply, false), "smtp.example.com", 25));
        assertEquals(MailFailureKind.CONNECTION, failure.getKind());
        assertTrue(failure.getSubmissionReceipt().isEmpty());
        return failure;
    }

    private static Stream<Arguments> successfulReplies() {
        return Stream.of(
                Arguments.of("IPv4", ipv4Reply()),
                Arguments.of("domain", domainReply("proxy.example.com")),
                Arguments.of("maximum-length domain", domainReply("a".repeat(255))),
                Arguments.of("IPv6", ipv6Reply()));
    }

    private static Stream<Arguments> successfulReplyFragments() {
        return successfulReplies().flatMap(arguments -> Stream.of(
                Arguments.of(arguments.get()[0], arguments.get()[1], false),
                Arguments.of(arguments.get()[0], arguments.get()[1], true)));
    }

    private static byte[] ipv4Reply() {
        return new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 4, 56};
    }

    private static byte[] domainReply(final String host) {
        final byte[] bytes = host.getBytes(UTF_8);
        final byte[] reply = new byte[bytes.length + 7];
        reply[0] = 5;
        reply[3] = 3;
        reply[4] = (byte) bytes.length;
        System.arraycopy(bytes, 0, reply, 5, bytes.length);
        reply[reply.length - 2] = 4;
        reply[reply.length - 1] = 56;
        return reply;
    }

    private static byte[] ipv6Reply() {
        final byte[] reply = new byte[22];
        reply[0] = 5;
        reply[3] = 4;
        reply[19] = 1;
        reply[20] = 4;
        reply[21] = 56;
        return reply;
    }

    private static class ReplySocket extends Socket {
        private final InputStream reply;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();

        private ReplySocket(final byte[] bytes, final boolean fragmented) {
            reply = new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(final byte[] buffer, final int offset, final int length) {
                    return super.read(buffer, offset, fragmented ? Math.min(length, 1) : length);
                }
            };
        }

        @Override public InputStream getInputStream() { return reply; }
        @Override public OutputStream getOutputStream() { return request; }
    }
}
