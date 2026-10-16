package org.simplejavamail.otel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

/** Small loopback peer for this optional module: no main-module test classes or real mail services are needed. */
final class TelemetrySmtpServer implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        final Thread thread = new Thread(task, "telemetry-smtp-peer");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final List<String> messages = new CopyOnWriteArrayList<>();
    final List<List<String>> commandsPerConnection = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger submissions = new AtomicInteger();
    private final IntFunction<String> replies;
    private final boolean rejectRecipient;
    private volatile boolean clientAbortExpected;

    TelemetrySmtpServer() throws IOException {
        this(ignored -> "250 queued", false);
    }

    TelemetrySmtpServer(final IntFunction<String> replies, final boolean rejectRecipient) throws IOException {
        this.replies = replies;
        this.rejectRecipient = rejectRecipient;
        workers.execute(this::acceptConnections);
    }

    int port() {
        return listener.getLocalPort();
    }

    void expectClientAbort() {
        clientAbortExpected = true;
    }

    private void acceptConnections() {
        while (!listener.isClosed()) {
            try {
                final Socket socket = listener.accept();
                sockets.add(socket);
                connections.incrementAndGet();
                workers.execute(() -> converse(socket));
            } catch (IOException failure) {
                if (!listener.isClosed()) {
                    failures.add(failure);
                }
            }
        }
    }

    private void converse(final Socket socket) {
        final List<String> commands = new CopyOnWriteArrayList<>();
        commandsPerConnection.add(commands);
        try (Socket owned = socket;
             BufferedReader input = new BufferedReader(new InputStreamReader(owned.getInputStream(), ISO_8859_1));
             PrintWriter output = new PrintWriter(owned.getOutputStream(), false, ISO_8859_1)) {
            owned.setSoTimeout(15000);
            reply(output, "220 loopback");
            String command;
            while ((command = input.readLine()) != null) {
                commands.add(command);
                if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                    reply(output, "250 localhost");
                } else if (command.startsWith("RCPT TO:") && rejectRecipient && command.contains("rejected@")) {
                    reply(output, "550 unavailable");
                } else if (command.equals("DATA")) {
                    reply(output, "354 continue");
                    messages.add(readMessage(input));
                    final String response = replies.apply(submissions.incrementAndGet());
                    if (response == null) {
                        return;
                    }
                    reply(output, response);
                } else if (command.equals("QUIT")) {
                    reply(output, "221 bye");
                    return;
                } else {
                    reply(output, "250 ok");
                }
            }
        } catch (IOException failure) {
            // A deadline deliberately aborts the client socket. Windows can report that as a reset rather than a clean EOF.
            final boolean expectedAbort = clientAbortExpected && failure instanceof SocketException;
            if (!listener.isClosed() && !expectedAbort) {
                failures.add(failure);
            }
        }
    }

    private static String readMessage(final BufferedReader input) throws IOException {
        final StringBuilder message = new StringBuilder();
        String line;
        while ((line = input.readLine()) != null && !line.equals(".")) {
            message.append(line).append("\r\n");
        }
        return message.toString();
    }

    private static void reply(final PrintWriter output, final String text) {
        output.print(text + "\r\n");
        output.flush();
    }

    @Override
    public void close() throws Exception {
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
    }
}
