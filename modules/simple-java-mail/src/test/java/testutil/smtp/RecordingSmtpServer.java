package testutil.smtp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

/** A loopback relay with separate ledgers for connections, commands and complete DATA transactions. */
public final class RecordingSmtpServer implements AutoCloseable {

    @FunctionalInterface
    public interface Conversation {
        void run(SmtpConversation peer, Connection connection) throws Exception;
    }

    private final AtomicInteger nextConnection = new AtomicInteger();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final ScriptedSmtpServer server;

    public RecordingSmtpServer() throws Exception {
        this((peer, connection) -> {
            connection.greet(peer, "250 localhost");
            connection.acceptMessages(peer);
        });
    }

    public RecordingSmtpServer(final Conversation conversation) throws Exception {
        // The one-byte charset preserves DATA exactly, including UTF-8 bytes, for the ledger's inverse conversion.
        server = new ScriptedSmtpServer(ISO_8859_1, peer -> {
            peer.setReadTimeoutMillis(30000);
            final Connection connection = new Connection(nextConnection.incrementAndGet());
            connections.add(connection);
            conversation.run(peer, connection);
        });
    }

    public int port() {
        return server.port();
    }

    public int connectionCount() {
        return connections.size();
    }

    public List<Connection> connections() {
        return List.copyOf(connections);
    }

    public List<String> commands() {
        final List<String> commands = new ArrayList<>();
        connections.forEach(connection -> commands.addAll(connection.commands));
        return commands;
    }

    public List<byte[]> messages() {
        final List<byte[]> messages = new ArrayList<>();
        connections.forEach(connection -> connection.messages.forEach(message -> messages.add(message.clone())));
        return messages;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

    public static final class Connection {
        private final int number;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<byte[]> messages = new CopyOnWriteArrayList<>();

        private Connection(final int number) {
            this.number = number;
        }

        public int number() {
            return number;
        }

        public String readCommand(final SmtpConversation peer) throws Exception {
            final String command = peer.readLine();
            if (command != null) {
                commands.add(command);
            }
            return command;
        }

        public void expect(final SmtpConversation peer, final String command) throws Exception {
            assertThat(readCommand(peer)).isEqualTo(command);
        }

        public void greet(final SmtpConversation peer, final String extensions) throws Exception {
            peer.reply(SmtpConversation.GREETING);
            expect(peer, "EHLO probe.example.test");
            peer.reply(extensions);
        }

        public void acceptMessages(final SmtpConversation peer) throws Exception {
            acceptMessages(peer, "250 queued");
        }

        /** A null final reply models the server losing the socket after receiving the complete message. */
        public void acceptMessages(final SmtpConversation peer, final String finalReply) throws Exception {
            acceptMessages(peer, ignored -> finalReply);
        }

        public void acceptMessages(final SmtpConversation peer, final IntFunction<String> responseAfterMessage) throws Exception {
            String command;
            while ((command = readCommand(peer)) != null) {
                if (command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:") || command.equals("RSET") || command.equals("NOOP")) {
                    peer.reply("250 ok");
                } else if (command.equals("DATA")) {
                    peer.reply("354 send content");
                    receiveMessage(peer);
                    final String finalReply = responseAfterMessage.apply(messages.size());
                    if (finalReply == null) {
                        peer.close();
                        return;
                    }
                    peer.reply(finalReply);
                } else if (command.equals("QUIT")) {
                    peer.reply("221 bye");
                    return;
                } else {
                    throw new AssertionError("Unexpected SMTP command: " + command);
                }
            }
        }

        public void receiveMessage(final SmtpConversation peer) throws Exception {
            final ByteArrayOutputStream content = new ByteArrayOutputStream();
            String line;
            while ((line = peer.readLine()) != null && !line.equals(".")) {
                final String unstuffed = line.startsWith("..") ? line.substring(1) : line;
                content.write((unstuffed + "\r\n").getBytes(ISO_8859_1));
            }
            assertThat(line).as("DATA terminator received").isEqualTo(".");
            messages.add(content.toByteArray());
        }
    }
}
