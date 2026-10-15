package testutil.memory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Owns exactly one sink JVM, its bounded control requests, and its termination. The client heap never contains accepted messages. */
final class MemorySmtpProcess implements AutoCloseable {
    private final Process process;
    private final BufferedReader responses;
    private final BufferedWriter commands;
    private final ExecutorService reader;
    final int port;

    MemorySmtpProcess(final MemoryPressureOptions options, final String name, final String mode) throws Exception {
        process = new ProcessBuilder(invocation(options, mode))
                .redirectError(options.output.resolve(name + "-server-errors.log").toFile()).start();
        responses = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "memory-smtp-control");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final String ready = readResponse();
            if (ready == null || !ready.startsWith("PORT=")) {
                throw new IOException("The discard-only server did not publish its loopback port. See " + name + "-server-errors.log.");
            }
            port = Integer.parseInt(ready.substring("PORT=".length()));
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static List<String> invocation(final MemoryPressureOptions options, final String mode) throws IOException {
        final Path quietLogging = options.output.resolve("smtp-log4j2.xml");
        if (!Files.exists(quietLogging)) {
            Files.writeString(quietLogging, "<?xml version=\"1.0\"?><Configuration status=\"OFF\"><Loggers><Root level=\"OFF\"/></Loggers></Configuration>");
        }
        final List<String> command = new ArrayList<>();
        command.add(jdkExecutable("java"));
        command.add("-Xmx128m");
        command.add("-Dlog4j2.configurationFile=" + quietLogging);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MailSendMemoryPressure.class.getName());
        command.add("server");
        command.add(mode);
        command.add(Integer.toString(maximumPhysicalConnections(options)));
        command.add(Integer.toString(maximumPendingRetryKeys(options)));
        return command;
    }

    static int maximumPhysicalConnections(final MemoryPressureOptions options) {
        // Pending retries retain message identities, not live connections. Their allowance must not turn into thousands of server threads.
        return options.concurrency * 2 + 4;
    }

    static int maximumPendingRetryKeys(final MemoryPressureOptions options) {
        return options.inflight + 2;
    }

    long pid() {
        return process.pid();
    }

    void setAvailable(final boolean available) throws Exception {
        final String reply = request("AVAILABLE " + available);
        if (!reply.equals("AVAILABLE=" + available)) {
            throw new IOException("Unexpected server availability acknowledgement: " + reply);
        }
    }

    Statistics statistics() throws Exception {
        final String reply = request("STATS");
        if (!reply.startsWith("STATS=")) {
            throw new IOException("Unexpected discard-server statistics: " + reply);
        }
        final String[] values = reply.substring("STATS=".length()).split(",");
        if (values.length != 6) {
            throw new IOException("Incomplete discard-server statistics: " + reply);
        }
        return new Statistics(Long.parseLong(values[0]), Long.parseLong(values[1]), Long.parseLong(values[2]), Long.parseLong(values[3]),
                Long.parseLong(values[4]), Long.parseLong(values[5]));
    }

    private String request(final String command) throws Exception {
        commands.write(command);
        commands.newLine();
        commands.flush();
        final String response = readResponse();
        if (response == null) {
            throw new IOException("The discard-only server exited before responding to " + command + '.');
        }
        return response;
    }

    private String readResponse() throws Exception {
        final Future<String> response = reader.submit(responses::readLine);
        try {
            return response.get(20, TimeUnit.SECONDS);
        } finally {
            if (!response.isDone()) {
                response.cancel(true);
            }
        }
    }

    static String jdkExecutable(final String name) {
        final String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", name + suffix).toString();
    }

    @Override
    public void close() throws Exception {
        try {
            if (process.isAlive()) {
                try {
                    commands.write("STOP");
                    commands.newLine();
                    commands.flush();
                } catch (IOException alreadyStopped) {
                    // The controller still reaps the child when its input pipe has already closed.
                }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        throw new IOException("The loopback SMTP process could not be terminated: " + process.pid());
                    }
                }
            }
        } finally {
            reader.shutdownNow();
            responses.close();
            commands.close();
            if (!reader.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IOException("The SMTP control reader did not terminate");
            }
        }
        if (process.exitValue() != 0) {
            throw new IOException("The discard-only SMTP process exited with status " + process.exitValue());
        }
    }

    static final class Statistics {
        final long accepted;
        final long refused;
        final long connections;
        final long bytes;
        final long activeConnections;
        final long pendingRetryKeys;

        private Statistics(final long accepted, final long refused, final long connections, final long bytes,
                final long activeConnections, final long pendingRetryKeys) {
            this.accepted = accepted;
            this.refused = refused;
            this.connections = connections;
            this.bytes = bytes;
            this.activeConnections = activeConnections;
            this.pendingRetryKeys = pendingRetryKeys;
        }
    }
}
