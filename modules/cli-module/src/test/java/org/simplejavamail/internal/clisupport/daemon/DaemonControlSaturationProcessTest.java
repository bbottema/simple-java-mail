package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Closeable;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual CLI processes keep slow SMTP commands in flight while authenticated management uses the same endpoint. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Tag("cli-process-pressure")
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
            } catch (Exception | AssertionError failure) {
                try {
                    final StringBuilder snapshot = new StringBuilder("Owned process snapshots at saturation failure:");
                    snapshot.append("\ndaemon: ").append(daemon.snapshot());
                    for (int index = 0; index < commands.size(); index++) {
                        snapshot.append("\nclient ").append(index).append(": ").append(commands.get(index).snapshot());
                    }
                    failure.addSuppressed(new AssertionError(snapshot.toString()));
                } catch (RuntimeException | Error diagnosticFailure) {
                    failure.addSuppressed(diagnosticFailure);
                }
                throw failure;
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
        return new ChildProcess(new ProcessBuilder(command).redirectErrorStream(true).start());
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
                "--mailer:withConnectTimeout", "5000", "--mailer:withReadTimeout", "90000",
                "--mailer:withProperty", "mail.host", "localhost", "--mailer:withSmtpClientHostname", "localhost"};
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

    private static final class ChildProcess implements AutoCloseable {
        private final Process process;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final Thread drainer;
        private final AtomicBoolean captureFailureReported = new AtomicBoolean();
        private volatile Throwable captureFailure;

        private ChildProcess(final Process process) {
            this.process = process;
            Thread capturing = null;
            try {
                capturing = new Thread(this::drain, "sjm-test-cli-output-" + process.pid());
                capturing.setDaemon(true);
                capturing.start();
            } catch (RuntimeException | Error failure) {
                retireFailedCaptureSetup(capturing, failure);
                throw failure;
            }
            drainer = capturing;
        }

        private void retireFailedCaptureSetup(final Thread capturing, final Throwable failure) {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean interrupted = false;
            try {
                process.destroyForcibly();
                assertThat(process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                        .as("Owned CLI process must terminate after output capture cannot start").isTrue();
            } catch (InterruptedException cleanup) {
                interrupted = true;
                addFailure(failure, cleanup);
            } catch (RuntimeException | Error cleanup) {
                addFailure(failure, cleanup);
            } finally {
                addFailure(failure, finishCapture(capturing, deadline));
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }

        private void drain() {
            try (InputStream input = process.getInputStream()) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    captured.write(buffer, 0, count);
                }
            } catch (IOException | RuntimeException | Error failure) {
                captureFailure = failure;
            }
        }

        private Invocation await(final Duration timeout) throws Exception {
            final long deadline = System.nanoTime() + timeout.toNanos();
            final boolean exited = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
            assertThat(exited).as(output()).isTrue();
            awaitOutput(deadline);
            return new Invocation(process.exitValue(), output());
        }

        private String output() throws IOException {
            checkCaptureFailure();
            // A live snapshot may end between UTF-8 bytes; diagnostics must not manufacture a decoding failure.
            return captured.toString(StandardCharsets.UTF_8);
        }

        /** Captures a bounded output tail without waiting for process completion. */
        private String snapshot() {
            final String state;
            if (process.isAlive()) {
                state = "alive";
            } else {
                state = "exit=" + process.exitValue();
            }
            final String text = captured.toString(StandardCharsets.UTF_8);
            final int limit = 2048;
            final String tail = text.length() <= limit ? text : "[earlier output omitted]\n" + text.substring(text.length() - limit);
            return "pid=" + process.pid() + " " + state + " output=" + tail
                    + (captureFailure == null ? "" : " captureFailure=" + captureFailure.getClass().getName());
        }

        private void awaitOutput(final long deadline) throws InterruptedException, IOException {
            awaitOutput(drainer, deadline);
        }

        private void awaitOutput(final Thread capturing, final long deadline) throws InterruptedException, IOException {
            final long remaining = deadline - System.nanoTime();
            if (capturing != null) {
                if (capturing.isAlive() && remaining > 0) { TimeUnit.NANOSECONDS.timedJoin(capturing, remaining); }
                assertThat(capturing.isAlive()).as("Owned CLI output must drain within its process operation deadline").isFalse();
            }
            checkCaptureFailure();
        }

        private void checkCaptureFailure() throws IOException {
            final Throwable failure = captureFailure;
            if (failure == null || !captureFailureReported.compareAndSet(false, true)) { return; }
            if (failure instanceof IOException io) { throw io; }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }

        @Override public void close() throws InterruptedException, IOException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Throwable primary = null;
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                            .as("Owned CLI process must terminate").isTrue();
                }
                awaitOutput(deadline);
            } catch (InterruptedException | IOException | RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                final Throwable cleanup = finishCapture(drainer, deadline);
                if (primary != null) { addFailure(primary, cleanup); }
                else if (cleanup instanceof InterruptedException interrupted) { throw interrupted; }
                else if (cleanup instanceof IOException io) { throw io; }
                else if (cleanup instanceof RuntimeException runtime) { throw runtime; }
                else if (cleanup instanceof Error error) { throw error; }
            }
        }

        private Throwable finishCapture(final Thread capturing, final long deadline) {
            Throwable failure = null;
            for (Closeable stream : new Closeable[] {process.getOutputStream(), process.getErrorStream(), process.getInputStream()}) {
                try { stream.close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure == null) { failure = cleanup; }
                    else { addFailure(failure, cleanup); }
                }
            }
            try {
                awaitOutput(capturing, deadline);
            } catch (InterruptedException cleanup) {
                Thread.currentThread().interrupt();
                if (failure == null) { failure = cleanup; }
                else { addFailure(failure, cleanup); }
            } catch (IOException | RuntimeException | Error cleanup) {
                if (failure == null) { failure = cleanup; }
                else { addFailure(failure, cleanup); }
            }
            return failure;
        }

        private static void addFailure(final Throwable primary, final Throwable cleanup) {
            if (cleanup != null && primary != cleanup) { primary.addSuppressed(cleanup); }
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
            }).server(SMTPServer.port(0).hostName("localhost"));
            server.start();
        }

        private int port() { return server.getServer().getPortAllocated(); }

        @Override public void close() {
            releaseSends.countDown();
            server.stop();
        }
    }
}
