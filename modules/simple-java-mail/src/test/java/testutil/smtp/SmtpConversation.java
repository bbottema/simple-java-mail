package testutil.smtp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.smtp.SmtpTestTls.testKeyStore;
import static testutil.smtp.SmtpTestTls.tlsContext;

/** A scripted peer's conversation, shared by capability, security, content and fault tests. */
public final class SmtpConversation implements AutoCloseable {
    public static final String GREETING = "220 localhost capability test peer";
    public static final String PLAIN_EHLO = "250-localhost\r\n250-STARTTLS\r\n250-SIZE 1024\r\n250 AUTH LOGIN";
    private final Charset charset;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public SmtpConversation(final Socket socket) throws IOException {
        this(socket, StandardCharsets.US_ASCII);
    }

    public SmtpConversation(final Socket socket, final Charset charset) throws IOException {
        this.charset = charset;
        this.socket = socket;
        socket.setSoTimeout(10000);
        useCurrentSocketStreams();
    }

    public void greet(final String ehloReply) throws IOException {
        reply(GREETING);
        expect("EHLO probe.example.test");
        reply(ehloReply);
    }

    public void startTls(final String ehloReply) throws Exception {
        greet(PLAIN_EHLO);
        expect("STARTTLS");
        reply("220 begin TLS");
        upgradeToTls();
        expect("EHLO probe.example.test");
        reply(ehloReply);
    }

    public void upgradeToTls() throws Exception {
        final SSLSocket encrypted = (SSLSocket) tlsContext(testKeyStore()).getSocketFactory()
                .createSocket(socket, "localhost", socket.getPort(), true);
        socket = encrypted;
        encrypted.setUseClientMode(false);
        // TLS 1.2 makes rejection visible during this fixture's handshake instead of a later application read.
        encrypted.setEnabledProtocols(new String[]{"TLSv1.2"});
        encrypted.startHandshake();
        useCurrentSocketStreams();
    }

    public void authenticate() throws IOException {
        expect("AUTH LOGIN");
        reply("334 VXNlcm5hbWU6");
        assertThat(reader.readLine()).isNotBlank();
        reply("334 UGFzc3dvcmQ6");
        assertThat(reader.readLine()).isNotBlank();
        reply("235 authenticated");
    }

    public void quit() throws IOException {
        expect("QUIT");
        reply("221 bye");
        expectClosed();
    }

    public void expect(final String command) throws IOException {
        assertThat(reader.readLine()).isEqualTo(command);
    }

    public String readLine() throws IOException {
        return reader.readLine();
    }

    public void expectClosed() throws IOException {
        assertThat(reader.readLine()).as("peer must close without sending mail").isNull();
    }

    public void reply(final String response) {
        writer.print(response + "\r\n");
        writer.flush();
        assertThat(writer.checkError()).as("SMTP response written").isFalse();
    }

    public void replyInFragments(final String response, final int fragmentSize) throws IOException {
        final byte[] bytes = (response + "\r\n").getBytes(charset);
        for (int offset = 0; offset < bytes.length; offset += fragmentSize) {
            socket.getOutputStream().write(bytes, offset, Math.min(fragmentSize, bytes.length - offset));
            socket.getOutputStream().flush();
        }
    }

    private void useCurrentSocketStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), charset));
        writer = new PrintWriter(socket.getOutputStream(), false, charset);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
