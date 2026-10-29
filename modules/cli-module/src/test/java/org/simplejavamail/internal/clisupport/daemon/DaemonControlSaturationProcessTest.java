package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.subethamail.wiser.Wiser;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual CLI processes keep slow SMTP commands in flight while authenticated management uses the same endpoint. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DaemonControlSaturationProcessTest {
    private static final String INSTANCE = "control-saturation";
    private static final int CLI_COMMANDS = 8;
    private static final int QUEUED_REPLIES = 32;

    @TempDir Path stateRoot;

    @ParameterizedTest(name = "forceTcp={0}")
    @ValueSource(booleans = {true, false})
    void statusAndStopRemainResponsiveWithEightSlowSmtpCommands(final boolean forceTcp) throws Exception {
        exerciseSaturation(forceTcp, false);
    }

    @ParameterizedTest(name = "forceTcp={0}")
    @ValueSource(booleans = {true, false})
    void fullExecutionReplyCapacityRejectsOverflowAndStopRefusesUnadmittedCommands(final boolean forceTcp) throws Exception {
        exerciseSaturation(forceTcp, true);
    }

    private void exerciseSaturation(final boolean forceTcp, final boolean fillReplyQueue) throws Exception {
        final List<ChildProcess> commands = new ArrayList<>();
        final List<PendingReply> queued = new ArrayList<>();
        try (HeldSmtp smtp = new HeldSmtp();
             ChildProcess daemon = startProcess(forceTcp, "daemon", "run", "--daemon-instance=" + INSTANCE)) {
            try {
                final DaemonDiscovery discovery = awaitReady(daemon);
                assumeTrue(forceTcp || discovery.endpoint().kind() == DaemonEndpoint.Kind.UNIX,
                        "The local runtime selected its supported TCP fallback instead of Unix-domain sockets");
                for (int index = 0; index < CLI_COMMANDS; index++) {
                    commands.add(startProcess(forceTcp, routedSendArguments(smtp.port(), "admitted-" + index)));
                }
                assertThat(smtp.fourSending.await(35, TimeUnit.SECONDS))
                        .as("All four application workers must be held inside SMTP submission").isTrue();
                awaitStatus(forceTcp, false);
                assertThat(commands).allSatisfy(command -> assertThat(command.process.isAlive()).isTrue());

                if (fillReplyQueue) {
                    for (int index = 0; index < QUEUED_REPLIES; index++) {
                        queued.add(sendRequest(discovery, DaemonOperation.EXECUTE,
                                List.of(sendArguments(smtp.port(), "unadmitted-" + index))));
                    }
                    final Invocation saturated = awaitStatus(forceTcp, true);
                    assertThat(statusValue(saturated.output(), "recentRequests")).isEqualTo(CLI_COMMANDS + QUEUED_REPLIES);
                    try (PendingReply overflow = sendRequest(discovery, DaemonOperation.EXECUTE,
                            List.of(sendArguments(smtp.port(), "overflow")))) {
                        assertThat(overflow.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                    }
                    final Invocation stillSaturated = invoke(forceTcp, "daemon", "status", "--daemon-instance=" + INSTANCE);
                    assertSuccess(stillSaturated);
                    assertThat(statusValue(stillSaturated.output(), "recentRequests")).isEqualTo(CLI_COMMANDS + QUEUED_REPLIES);
                    assertThat(smtp.recipientCalls).hasValue(4);
                }

                // The regular CLI stop waits for process exit. Inspect its authenticated acknowledgement separately
                // so command draining cannot masquerade as responsive STOP admission.
                try (PendingReply stop = sendRequest(discovery, DaemonOperation.STOP, List.of())) {
                    final DaemonResponse response = stop.await(Duration.ofSeconds(5));
                    assertThat(response.category()).isEqualTo(CliExitCode.SUCCESS);
                    assertThat(response.stdout()).contains("Daemon stopping.");
                }
                assertThat(smtp.releaseSends.getCount()).isEqualTo(1);
                assertThat(daemon.process.isAlive()).isTrue();
                smtp.releaseSends.countDown();

                for (ChildProcess command : commands) {
                    assertSuccess(command.await(Duration.ofSeconds(30)));
                }
                for (PendingReply reply : queued) {
                    assertThat(reply.await(Duration.ofSeconds(10)).category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                }
                assertSuccess(daemon.await(Duration.ofSeconds(35)));
                assertThat(smtp.recipientCalls).hasValue(CLI_COMMANDS);
                assertThat(smtp.server.getMessages()).hasSize(CLI_COMMANDS);
            } finally {
                smtp.releaseSends.countDown();
                for (PendingReply reply : queued) { reply.close(); }
                for (ChildProcess command : commands) { command.close(); }
            }
        }
    }

    private Invocation awaitStatus(final boolean forceTcp, final boolean replyQueueFull) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        Invocation last;
        do {
            last = invoke(forceTcp, "daemon", "status", "--daemon-instance=" + INSTANCE);
            assertSuccess(last);
            final boolean applicationSaturated = statusValue(last.output(), "activeRequests") == 4
                    && statusValue(last.output(), "queuedRequests") >= 4;
            if (applicationSaturated && (!replyQueueFull
                    || statusValue(last.output(), "activeReplies") == CLI_COMMANDS
                    && statusValue(last.output(), "queuedReplies") == QUEUED_REPLIES)) {
                return last;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Daemon did not expose the expected saturated state: " + last.output());
    }

    private DaemonDiscovery awaitReady(final ChildProcess daemon) throws Exception {
        final DaemonStateStore store = new DaemonStateStore(paths());
        final long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(daemon.process.isAlive()).as(daemon.output()).isTrue();
            final DaemonDiscovery discovery = store.read();
            if (discovery != null) {
                try (PendingReply ready = sendRequest(discovery, DaemonOperation.READY, List.of())) {
                    if (ready.await(Duration.ofSeconds(5)).category() == CliExitCode.SUCCESS) {
                        return discovery;
                    }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Daemon did not become ready: " + daemon.output());
    }

    private DaemonPaths paths() {
        final String previous = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
        try {
            System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, stateRoot.toString());
            return DaemonPaths.forInstance(INSTANCE);
        } finally {
            if (previous == null) {
                System.clearProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, previous);
            }
        }
    }

    private PendingReply sendRequest(final DaemonDiscovery discovery, final DaemonOperation operation,
            final List<String> arguments) throws IOException {
        final SocketChannel channel = LocalDaemonTransport.connect(discovery.endpoint());
        final UUID requestId = UUID.randomUUID();
        try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(5))) {
            DaemonProtocol.writeRequest(channel, new DaemonRequest(operation, discovery.sessionId(), requestId, Instant.now(),
                    Path.of("").toAbsolutePath(), arguments), discovery.authenticationSecret());
            return new PendingReply(channel, discovery, requestId);
        } catch (IOException | RuntimeException | Error failure) {
            channel.close();
            throw failure;
        }
    }

    private Invocation invoke(final boolean forceTcp, final String... arguments) throws Exception {
        try (ChildProcess process = startProcess(forceTcp, arguments)) {
            return process.await(Duration.ofSeconds(20));
        }
    }

    private ChildProcess startProcess(final boolean forceTcp, final String... arguments) throws IOException {
        final Path output = Files.createTempFile(stateRoot, "cli-", ".log");
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
        command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
        command.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        command.add("-Dsimplejavamail.cli.daemon.console-child=true");
        if (forceTcp) { command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true"); }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.simplejavamail.cli.SimpleJavaMail");
        command.addAll(List.of(arguments));
        return new ChildProcess(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start(), output);
    }

    private static String[] routedSendArguments(final int port, final String subject) {
        final List<String> arguments = new ArrayList<>(List.of(sendArguments(port, subject)));
        arguments.add("--daemon=require");
        arguments.add("--daemon-instance=" + INSTANCE);
        return arguments.toArray(new String[0]);
    }

    private static String[] sendArguments(final int port, final String subject) {
        return new String[]{"send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "recipient@example.test", "--email:withSubject", subject, "--email:withPlainText", "Held SMTP body",
                "--mailer:withConnectionConflictPolicy", "ALLOW_EXPLICIT_OVERRIDES",
                "--mailer:withSMTPServer", "127.0.0.1", Integer.toString(port),
                "--mailer:withConnectionPoolCoreSize", "0", "--mailer:withConnectionPoolMaxSize", "4",
                "--mailer:withConnectTimeout", "5000", "--mailer:withReadTimeout", "90000"};
    }

    private static int statusValue(final String status, final String key) {
        return status.lines().filter(line -> line.startsWith(key + "="))
                .mapToInt(line -> Integer.parseInt(line.substring(key.length() + 1))).findFirst()
                .orElseThrow(() -> new AssertionError("Missing status value " + key + ": " + status));
    }

    private static void assertSuccess(final Invocation invocation) {
        assertThat(invocation.exitCode()).as(invocation.output()).isZero();
    }

    private record Invocation(int exitCode, String output) { }

    private record PendingReply(SocketChannel channel, DaemonDiscovery discovery, UUID requestId) implements AutoCloseable {
        private DaemonResponse await(final Duration timeout) throws IOException {
            try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, timeout)) {
                return DaemonProtocol.readResponse(channel, discovery.sessionId(), requestId, discovery.authenticationSecret());
            }
        }

        @Override public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Close every owned client even when a failed assertion or another disconnect starts cleanup.
            }
        }
    }

    private record ChildProcess(Process process, Path log) implements AutoCloseable {
        private Invocation await(final Duration timeout) throws Exception {
            final boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(exited).as(output()).isTrue();
            return new Invocation(process.exitValue(), output());
        }

        private String output() throws IOException { return Files.readString(log, StandardCharsets.UTF_8); }

        @Override public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned CLI process must terminate").isTrue();
            }
        }
    }

    private static final class HeldSmtp implements AutoCloseable {
        private final CountDownLatch fourSending = new CountDownLatch(4);
        private final CountDownLatch releaseSends = new CountDownLatch(1);
        private final AtomicInteger recipientCalls = new AtomicInteger();
        private final Wiser server;

        private HeldSmtp() {
            server = Wiser.accepter((sender, recipient) -> {
                recipientCalls.incrementAndGet();
                fourSending.countDown();
                try {
                    if (!releaseSends.await(90, TimeUnit.SECONDS)) {
                        throw new AssertionError("The test did not release its SMTP submissions");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return true;
            }).port(0);
            server.start();
        }

        private int port() { return server.getServer().getPortAllocated(); }

        @Override public void close() {
            releaseSends.countDown();
            server.stop();
        }
    }
}
