package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;

/** Real Angus sending through the authenticated bridge, including a genuine RFC1929 challenge. */
@Timeout(30)
class AuthenticatedSocksAddressingTest {

    @Test
    void preservesAHostnameKnownOnlyToTheRemoteProxy() throws Exception {
        try (ProxySmtpPeer proxy = new ProxySmtpPeer("127.0.0.1");
             Mailer mailer = mailer("smtp.proxy-only.invalid", proxy)) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.destination.get(5, TimeUnit.SECONDS)).isEqualTo("smtp.proxy-only.invalid");
            assertThat(proxy.addressType).isEqualTo(3);
            assertThat(proxy.destinationPort).isEqualTo(2525);
        }
    }

    @Test
    void preservesANumericIpv6DestinationThroughAnIpv4Proxy() throws Exception {
        try (ProxySmtpPeer proxy = new ProxySmtpPeer("127.0.0.1");
             Mailer mailer = mailer("2001:db8::25", proxy)) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.destination.get(5, TimeUnit.SECONDS)).isEqualTo(InetAddress.getByName("2001:db8::25").getHostAddress());
            assertThat(proxy.addressType).isEqualTo(4);
        }
    }

    @Test
    void reachesAnIpv4DestinationThroughAnIpv6ProxyConnection() throws Exception {
        try (ProxySmtpPeer proxy = new ProxySmtpPeer("::1");
             Mailer mailer = mailer("127.0.0.1", proxy)) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(proxy.destination.get(5, TimeUnit.SECONDS)).isEqualTo("127.0.0.1");
            assertThat(proxy.addressType).isEqualTo(1);
        }
    }

    @Test
    void theGeneratedLoopbackRouteWorksWhenTheJvmPrefersIpv6() throws Exception {
        try (ProxySmtpPeer proxy = new ProxySmtpPeer("127.0.0.1");
             Mailer mailer = mailer("127.0.0.1", proxy)) {
            assertThat(mailer.sync().sendMail(email()).getStatus()).isEqualTo(ACCEPTED);
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("127.0.0.1");
            assertThat(proxy.destination.get(5, TimeUnit.SECONDS)).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void aTruncatedConnectReplyFailsTheSendAndClosesItsRemotePeer() throws Exception {
        try (ProxySmtpPeer proxy = new ProxySmtpPeer("127.0.0.1", true);
             Mailer mailer = mailer("127.0.0.1", proxy)) {
            assertThatThrownBy(() -> mailer.sync().sendMail(email())).isInstanceOf(MailException.class);
            assertThat(proxy.destination.get(5, TimeUnit.SECONDS)).isEqualTo("127.0.0.1");
            proxy.finished.get(5, TimeUnit.SECONDS);
        }
    }

    private static Mailer mailer(final String smtpHost, final ProxySmtpPeer proxy) {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
                .withSMTPServer(smtpHost, 2525)
                .withConnectionConfig(ConnectionConfig.builder()
                        .withSmtpClientHostname("sender.example.test")
                        .withConnectTimeout(Duration.ofSeconds(5))
                        .withReadTimeout(Duration.ofSeconds(5)).build())
                .withProxyConfig(SocksProxyConfig.builder()
                        .withHost(proxy.host).withPort(proxy.listener.getLocalPort())
                        .withUsername("proxy-user").withPassword("proxy-secret").build())
                .buildMailer();
    }

    private static Email email() {
        return SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.test").to("recipient@example.test")
                .withPlainText("SOCKS address preservation").buildEmail();
    }

    private static final class ProxySmtpPeer implements AutoCloseable {
        private final String host;
        private final ServerSocket listener;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "addressing proxy fixture");
            thread.setDaemon(true);
            return thread;
        });
        private final CompletableFuture<String> destination = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile Socket accepted;
        private volatile int addressType;
        private volatile int destinationPort;

        private ProxySmtpPeer(final String host) throws IOException {
            this(host, false);
        }

        private ProxySmtpPeer(final String host, final boolean truncatedReply) throws IOException {
            this.host = host;
            listener = new ServerSocket(0, 1, InetAddress.getByName(host));
            worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(10000);
                    final InputStream input = socket.getInputStream();
                    final OutputStream output = socket.getOutputStream();
                    authenticate(input, output);
                    readDestination(input);
                    if (truncatedReply) {
                        output.write(new byte[] {5, 0, 0, 1});
                        output.flush();
                        socket.shutdownOutput();
                        assertThat(input.read()).as("the failed setup's exact remote socket closes").isEqualTo(-1);
                        finished.complete(null);
                        return;
                    }
                    replyConnected(socket, output);
                    serveSmtp(input, output);
                    finished.complete(null);
                } catch (Throwable failure) {
                    destination.completeExceptionally(failure);
                    finished.completeExceptionally(failure);
                }
            });
        }

        private static void authenticate(final InputStream input, final OutputStream output) throws IOException {
            assertThat(readByte(input)).isEqualTo(5);
            final byte[] methods = input.readNBytes(readByte(input));
            assertThat(methods).contains((byte) 2);
            output.write(new byte[] {5, 2});
            output.flush();
            assertThat(readByte(input)).isEqualTo(1);
            assertThat(new String(input.readNBytes(readByte(input)), UTF_8)).isEqualTo("proxy-user");
            assertThat(new String(input.readNBytes(readByte(input)), UTF_8)).isEqualTo("proxy-secret");
            output.write(new byte[] {1, 0});
            output.flush();
        }

        private void readDestination(final InputStream input) throws IOException {
            assertThat(input.readNBytes(3)).containsExactly(5, 1, 0);
            addressType = readByte(input);
            final String address;
            if (addressType == 3) {
                address = new String(input.readNBytes(readByte(input)), UTF_8);
            } else {
                assertThat(addressType).isIn(1, 4);
                address = InetAddress.getByAddress(input.readNBytes(addressType == 1 ? 4 : 16)).getHostAddress();
            }
            destinationPort = (readByte(input) << 8) | readByte(input);
            destination.complete(address);
        }

        private static void replyConnected(final Socket socket, final OutputStream output) throws IOException {
            final byte[] address = socket.getLocalAddress().getAddress();
            output.write(new byte[] {5, 0, 0, (byte) (address.length == 4 ? 1 : 4)});
            output.write(address);
            output.write(new byte[] {0, 25});
            output.flush();
        }

        private static void serveSmtp(final InputStream input, final OutputStream output) throws IOException {
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input, US_ASCII));
            writeLine(output, "220 proxy-fixture.example.test ESMTP");
            String line;
            while ((line = reader.readLine()) != null) {
                final String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO ") || command.startsWith("HELO ")
                        || command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")
                        || command.equals("RSET") || command.equals("NOOP")) {
                    writeLine(output, "250 OK");
                } else if (command.equals("DATA")) {
                    writeLine(output, "354 Send message");
                    while ((line = reader.readLine()) != null && !line.equals(".")) {
                        // Consume the complete MIME message through the real tunnel.
                    }
                    if (line == null) {
                        throw new EOFException("SMTP body was truncated");
                    }
                    writeLine(output, "250 queued");
                } else if (command.equals("QUIT")) {
                    writeLine(output, "221 Bye");
                    return;
                } else {
                    throw new IOException("Unexpected fixture command: " + line);
                }
            }
        }

        private static void writeLine(final OutputStream output, final String line) throws IOException {
            output.write((line + "\r\n").getBytes(US_ASCII));
            output.flush();
        }

        private static int readByte(final InputStream input) throws IOException {
            final int value = input.read();
            if (value < 0) {
                throw new EOFException("Incomplete SOCKS request");
            }
            return value;
        }

        @Override
        public void close() throws Exception {
            listener.close();
            final Socket socket = accepted;
            if (socket != null) {
                socket.close();
            }
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            if (destination.isDone() && !destination.isCompletedExceptionally()) {
                finished.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
