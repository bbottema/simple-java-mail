package org.simplejavamail.mailer;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.SmtpConversation;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** A real RFC1929 challenge observes the bytes sent through the Mailer's authentication bridge. */
@Timeout(20)
class AuthenticatedSocksCredentialBoundaryTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameableCredentials")
    void theMailerPreservesCredentialBytesAtTheProtocolBoundaries(final String boundary, final String username, final String password)
            throws Exception {
        try (ChallengingProxy proxy = new ChallengingProxy(username.getBytes(UTF_8), password.getBytes(UTF_8));
             Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                     .withSMTPServer("127.0.0.1", 25)
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test")
                             .withSessionTimeout(Duration.ofSeconds(5)).build())
                     .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build())
                     .withProxyConfig(SocksProxyConfig.builder().withHost("127.0.0.1").withPort(proxy.port())
                             .withUsername(username).withPassword(password).build()).buildMailer()) {
            mailer.sync().testConnection();
        }
    }

    private static Stream<Arguments> frameableCredentials() {
        return Stream.of(
                Arguments.of("one byte per member", "u", "p"),
                Arguments.of("255 ASCII bytes per member", "u".repeat(255), "p".repeat(255)),
                Arguments.of("255 UTF-8 bytes per member", "ü".repeat(127) + "x", "€".repeat(85)));
    }

    private static final class ChallengingProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final CompletableFuture<Void> conversation;

        private ChallengingProxy(final byte[] username, final byte[] password) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            conversation = CompletableFuture.runAsync(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    final DataInputStream request = new DataInputStream(socket.getInputStream());
                    final OutputStream reply = socket.getOutputStream();
                    assertThat(request.readUnsignedByte()).isEqualTo(5);
                    assertThat(request.readNBytes(request.readUnsignedByte())).contains((byte) 2);
                    reply.write(new byte[] {5, 2});
                    reply.flush();

                    assertThat(request.readUnsignedByte()).isEqualTo(1);
                    assertThat(request.readNBytes(request.readUnsignedByte())).isEqualTo(username);
                    assertThat(request.readNBytes(request.readUnsignedByte())).isEqualTo(password);
                    reply.write(new byte[] {1, 0});
                    reply.flush();

                    assertThat(request.readNBytes(4)).containsExactly(5, 1, 0, 1);
                    assertThat(request.readNBytes(4)).containsExactly(127, 0, 0, 1);
                    assertThat(request.readUnsignedShort()).isEqualTo(25);
                    reply.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                    reply.flush();

                    try (SmtpConversation smtp = new SmtpConversation(socket)) {
                        smtp.greet("250 localhost");
                        smtp.quit();
                    }
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
        }

        private int port() { return listener.getLocalPort(); }

        @Override public void close() throws Exception {
            listener.close();
            conversation.get(10, TimeUnit.SECONDS);
        }
    }
}
