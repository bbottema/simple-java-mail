package org.simplejavamail.mailer;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the public owned-Session route against a real IPv6-only anonymous SOCKS peer. */
@Timeout(30)
class AnonymousSocksIpv6Test {
    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]"})
    void aTypedIpv6ProxyKeepsItsWholeAddress(final String host) throws Exception {
        try (ProxyPeer proxy = new ProxyPeer("::1");
             Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                     .withSMTPServer("127.0.0.1", 2525)
                     .withTransportStrategy(TransportStrategy.SMTP)
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("sender.example.test")
                             .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(10)).build())
                     .withProxyConfig(SocksProxyConfig.builder().withHost(host).withPort(proxy.port()).build())
                     .buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo(host);
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo(host);
            assertThat(mailer.getSession().getProvider("smtp").getClassName())
                    .isEqualTo("org.simplejavamail.internal.mailprovider.angus.ManagedAngusTransport");
            mailer.sync().testConnection();
            proxy.connected.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]"})
    void thePrivateProbeRetainsTheTypedIpv6Route(final String host) throws Exception {
        try (ProxyPeer proxy = new ProxyPeer("::1");
             Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                     .withSMTPServer("127.0.0.1", 2525).withTransportStrategy(TransportStrategy.SMTP)
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("sender.example.test")
                             .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(10)).build())
                     .withProxyConfig(SocksProxyConfig.builder().withHost(host).withPort(proxy.port()).build()).buildMailer()) {
            assertThat(mailer.sync().probeConnection().isSuccessful()).isTrue();
            proxy.finished.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "127.0.0.1:bad:port"})
    void rawMultiColonInputsKeepProviderInterpretationAndInspection(final String rawHost) throws Exception {
        final String interpretedHost = rawHost.startsWith(":") ? InetAddress.getLoopbackAddress().getHostAddress() : "127.0.0.1";
        try (ProxyPeer proxy = new ProxyPeer(interpretedHost);
             Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                     .withSMTPServer("127.0.0.1", 2525).withTransportStrategy(TransportStrategy.SMTP)
                     .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("sender.example.test")
                             .withConnectTimeout(Duration.ofSeconds(10)).withReadTimeout(Duration.ofSeconds(10)).build())
                     .withProperty("mail.smtp.socks.host", rawHost).withProperty("mail.smtp.socks.port", proxy.port()).buildMailer()) {
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo(interpretedHost);
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(proxy.port());
            mailer.sync().testConnection();
            proxy.connected.get(5, TimeUnit.SECONDS);
        }
    }

    private static final class ProxyPeer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CompletableFuture<Void> connected = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile Socket accepted;

        private ProxyPeer(final String bindHost) throws Exception {
            listener.bind(new InetSocketAddress(InetAddress.getByName(bindHost), 0));
            listener.setSoTimeout(15000);
            worker.execute(() -> {
                try (Socket socket = listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(15000);
                    final InputStream input = socket.getInputStream();
                    final OutputStream output = socket.getOutputStream();
                    assertThat(readByte(input)).isEqualTo(5);
                    assertThat(input.readNBytes(readByte(input))).contains((byte) 0);
                    output.write(new byte[] {5, 0});
                    output.flush();
                    assertThat(input.readNBytes(4)).containsExactly(5, 1, 0, 1);
                    assertThat(input.readNBytes(4)).containsExactly(127, 0, 0, 1);
                    assertThat((readByte(input) << 8) | readByte(input)).isEqualTo(2525);
                    output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 25});
                    writeLine(output, "220 proxy.example.test ESMTP");
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(input, US_ASCII));
                    assertThat(reader.readLine()).startsWith("EHLO ");
                    writeLine(output, "250 proxy.example.test");
                    connected.complete(null);
                    String command;
                    while ((command = reader.readLine()) != null && !command.equals("QUIT")) {
                        assertThat(command).isIn("RSET", "NOOP");
                        writeLine(output, "250 OK");
                    }
                    assertThat(command).isEqualTo("QUIT");
                    writeLine(output, "221 Bye");
                    finished.complete(null);
                } catch (Throwable failure) {
                    connected.completeExceptionally(failure);
                    finished.completeExceptionally(failure);
                }
            });
        }

        private int port() { return listener.getLocalPort(); }

        private static int readByte(final InputStream input) throws Exception {
            final int value = input.read();
            if (value < 0) { throw new EOFException("Truncated SOCKS request"); }
            return value;
        }

        private static void writeLine(final OutputStream output, final String line) throws Exception {
            output.write((line + "\r\n").getBytes(US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            try {
                if (connected.isDone() && !connected.isCompletedExceptionally()) {
                    finished.get(5, TimeUnit.SECONDS);
                }
            } finally {
                listener.close();
                if (accepted != null) { accepted.close(); }
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
