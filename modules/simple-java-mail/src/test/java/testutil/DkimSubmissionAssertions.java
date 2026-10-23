package testutil;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.dkimsupport.SignatureAssertions;
import org.simplejavamail.internal.mailprovider.angus.AngusMailTransportAdapter;
import testutil.smtp.ScriptedSmtpServer;
import java.io.ByteArrayOutputStream;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicReference;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

public final class DkimSubmissionAssertions {
    private DkimSubmissionAssertions() { }

    /** The actual Angus DATA representation must equal the finalized protected representation. */
    public static void assertPreserved(final PreparedMail prepared, final Session session, final PublicKey key) throws Exception {
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        prepared.getMimeMessage().writeTo(expected);
        SignatureAssertions.assertValidSignature(expected.toByteArray(), key);
        final AtomicReference<byte[]> captured = new AtomicReference<>();
        try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, ISO_8859_1, peer -> {
            peer.reply("220 localhost DKIM peer");
            assertThat(peer.readLine()).startsWith("EHLO ");
            peer.reply("250-localhost\r\n250-8BITMIME\r\n250 DSN");
            assertThat(peer.readLine()).startsWith("MAIL FROM:");
            peer.reply("250 sender accepted");
            String command = peer.readLine();
            while (command.startsWith("RCPT TO:")) {
                peer.reply("250 recipient accepted");
                command = peer.readLine();
            }
            assertThat(command).isEqualTo("DATA");
            peer.reply("354 content");
            final ByteArrayOutputStream content = new ByteArrayOutputStream();
            String line;
            while (!(line = peer.readLine()).equals(".")) {
                if (line.startsWith("..")) { line = line.substring(1); }
                content.write(line.getBytes(ISO_8859_1));
                content.write('\r');
                content.write('\n');
            }
            captured.set(content.toByteArray());
            peer.reply("250 accepted");
            while ((command = peer.readLine()) != null) {
                if (command.equals("QUIT")) { peer.reply("221 bye"); return; }
                assertThat(command).isEqualTo("RSET");
                peer.reply("250 reset");
            }
        })) {
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect("localhost", server.port(), null, null);
                new AngusMailTransportAdapter().sendMessage(transport, prepared);
            }
        }
        final byte[] originalBytes = expected.toByteArray();
        // SMTP terminates a final partial line with CRLF before the DATA terminator.
        // Existing bytes must remain intact; this framing is already included by DKIM canonicalization.
        final boolean completeLine = originalBytes.length >= 2 && originalBytes[originalBytes.length - 2] == '\r'
                && originalBytes[originalBytes.length - 1] == '\n';
        final byte[] expectedBytes = completeLine ? originalBytes : java.util.Arrays.copyOf(originalBytes, originalBytes.length + 2);
        if (!completeLine) { expectedBytes[originalBytes.length] = '\r'; expectedBytes[originalBytes.length + 1] = '\n'; }
        final byte[] capturedBytes = captured.get();
        int difference = 0;
        while (difference < Math.min(expectedBytes.length, capturedBytes.length)
                && expectedBytes[difference] == capturedBytes[difference]) { difference++; }
        if (difference != expectedBytes.length || difference != capturedBytes.length) {
            java.nio.file.Files.write(java.nio.file.Paths.get("target/dkim-expected.eml"), expectedBytes);
            java.nio.file.Files.write(java.nio.file.Paths.get("target/dkim-captured.eml"), capturedBytes);
            throw new AssertionError("SMTP bytes differ at " + difference + "; expected length " + expectedBytes.length
                    + ", captured length " + capturedBytes.length);
        }
        SignatureAssertions.assertValidSignature(captured.get(), key);
        final ByteArrayOutputStream repeated = new ByteArrayOutputStream();
        prepared.getMimeMessage().saveChanges();
        prepared.getMimeMessage().writeTo(repeated);
        assertThat(repeated.toByteArray()).containsExactly(expected.toByteArray());
    }
}
