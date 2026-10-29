package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocksAuthenticationLengthTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("unframeableCredentials")
    void rejectsUnframeableCredentialsBeforeWriting(final String boundary, final String username, final String password,
            final String member) {
        final AuthenticationSocket socket = new AuthenticationSocket();

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(proxy(socket, username, password)))
                .isInstanceOf(SocksException.class).hasMessageContaining(member)
                .hasMessageContaining("255").hasMessageNotContaining("synthetic-secret");
        assertThat(socket.request.size()).as("No credential packet may be written for " + boundary).isZero();
    }

    private static Stream<Arguments> unframeableCredentials() {
        return Stream.of(
                Arguments.of("empty username", "", "synthetic-secret", "username"),
                Arguments.of("empty password", "synthetic-user", "", "password"),
                Arguments.of("256 ASCII username bytes", "u".repeat(256), "synthetic-secret", "username"),
                Arguments.of("256 ASCII password bytes", "synthetic-user", "p".repeat(256), "password"),
                Arguments.of("128 two-byte username characters", "ü".repeat(128), "synthetic-secret", "username"),
                Arguments.of("128 two-byte password characters", "synthetic-user", "ü".repeat(128), "password"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameableCredentials")
    void retainsCompleteCredentialBytesAtTheWireBoundaries(final String boundary, final String username, final String password)
            throws Exception {
        final AuthenticationSocket socket = new AuthenticationSocket();

        SocksAuthenticationHelper.performUserPasswordAuthentication(proxy(socket, username, password));

        final DataInputStream packet = new DataInputStream(new ByteArrayInputStream(socket.request.toByteArray()));
        assertThat(packet.readUnsignedByte()).isEqualTo(1);
        assertThat(packet.readNBytes(packet.readUnsignedByte())).isEqualTo(username.getBytes(UTF_8));
        assertThat(packet.readNBytes(packet.readUnsignedByte())).isEqualTo(password.getBytes(UTF_8));
        assertThat(packet.read()).as("No undeclared bytes follow the credentials").isEqualTo(-1);
    }

    private static Stream<Arguments> frameableCredentials() {
        return Stream.of(
                Arguments.of("one byte per member", "u", "p"),
                Arguments.of("255 ASCII bytes per member", "u".repeat(255), "p".repeat(255)),
                Arguments.of("255 multibyte UTF-8 bytes per member", "ü".repeat(127) + "x", "€".repeat(85)));
    }

    private static Socks5 proxy(final AuthenticationSocket socket, final String username, final String password) {
        final Socks5 proxy = new Socks5(new InetSocketAddress("127.0.0.1", 1080));
        proxy.setCredentials(new ProxyCredentials(username, password));
        proxy.setProxySocket(socket);
        return proxy;
    }

    private static final class AuthenticationSocket extends Socket {
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final InputStream reply = new ByteArrayInputStream(new byte[] {1, 0});

        @Override public InputStream getInputStream() { return reply; }
        @Override public OutputStream getOutputStream() { return request; }
    }
}
