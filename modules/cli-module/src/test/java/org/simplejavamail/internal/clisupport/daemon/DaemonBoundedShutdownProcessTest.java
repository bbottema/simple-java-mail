package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A real SMTP read must not retain the standalone daemon after its complete stop budget expires. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DaemonBoundedShutdownProcessTest {
    private static final String INSTANCE = "bounded-stop";

    @TempDir Path stateRoot;

    @Test
    void stalledSmtpDoesNotOutliveProductionStopDeadline() throws Exception {
        try (HeldSmtp smtp = new HeldSmtp(); ChildProcess daemon = startDaemon()) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, sendArguments(smtp.port()))) {
                assertThat(smtp.recipientReceived.await(35, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
                final long stopStarted = System.nanoTime();
                try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                assertThat(daemon.process.waitFor(65, TimeUnit.SECONDS))
                        .as("SMTP remains held; the daemon must enforce its own 60-second process deadline. %s", daemon.output())
                        .isTrue();
                assertThat(daemon.process.exitValue()).as(daemon.output()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isLessThan(Duration.ofSeconds(65));
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
                assertThat(smtp.recipientReceived.getCount()).isZero();
            }
        }
    }

    @ParameterizedTest(name = "withoutBatch={0}")
    @ValueSource(booleans = {false, true})
    void heldSmtpExitsWithoutReleaseAndTheSameInstanceCanRecover(final boolean withoutBatch) throws Exception {
        try (HeldSmtp smtp = new HeldSmtp(); ChildProcess daemon = startFixture("smtp", true, withoutBatch)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, sendArguments(smtp.port()))) {
                assertThat(smtp.recipientReceived.await(35, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
                stopAndAwait(daemon, discovery);
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
                assertThat(new DaemonStateStore(paths()).read().sessionId()).isEqualTo(discovery.sessionId());
            }
        }
        try (ChildProcess replacement = startFixture("smtp", true, withoutBatch)) {
            stopAndAwait(replacement, awaitReady(replacement));
        }
    }

    @ParameterizedTest(name = "forceTcp={0}")
    @ValueSource(booleans = {true, false})
    void uncooperativeCommandsRetainUncertainResultsUntilProcessExit(final boolean forceTcp) throws Exception {
        try (ChildProcess daemon = startFixture("command", forceTcp, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            assumeTrue(forceTcp || discovery.endpoint().kind() == DaemonEndpoint.Kind.UNIX,
                    "The runtime selected its supported TCP fallback");
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"))) {
                awaitMarker("command-entered");
                stopAndAwait(daemon, discovery);
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        }
    }

    @Test
    void blockedMailerCleanupCannotExtendTheProcessBudget() throws Exception {
        try (ChildProcess daemon = startFixture("close", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
            }
            awaitMarker("close-entered");
            assertExited(daemon);
        }
    }

    @Test
    void watchdogStaysArmedAfterServerCleanupWhenAnotherJvmHookBlocks() throws Exception {
        try (ChildProcess daemon = startFixture("hook", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            final long stopStarted = System.nanoTime();
            try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
            }
            awaitMarker("hook-entered");
            assertExited(daemon);
            assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isGreaterThan(Duration.ofSeconds(2));
            assertThat(Files.exists(paths().discoveryFile())).isFalse();
        }
    }

    @Test
    void aLaterJvmShutdownCannotCancelAnAlreadyAcceptedStopsWatchdog() throws Exception {
        try (ChildProcess daemon = startFixture("late-jvm", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"))) {
                awaitMarker("command-entered");
                try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                Files.writeString(stateRoot.resolve("trigger-jvm-shutdown"), "exit");
                awaitMarker("hook-entered");
                assertExited(daemon);
            }
        }
    }

    @Test
    void authenticatedStopOnAnAcceptedChannelStillBoundsFailedRuntimeCleanup() throws Exception {
        try (ChildProcess daemon = startFixture("failed-accept", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"));
                 SocketChannel control = LocalDaemonTransport.connect(discovery.endpoint())) {
                failListenerAfterAccepting(control);
                Thread.sleep(500);
                try (PendingReply stop = requestOn(control, discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                assertExited(daemon);
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        }
    }

    @Test
    void invalidStopDuringFailedRuntimeCleanupCannotGrantHaltAuthority() throws Exception {
        try (ChildProcess daemon = startFixture("failed-accept", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"));
                 SocketChannel control = LocalDaemonTransport.connect(discovery.endpoint())) {
                failListenerAfterAccepting(control);
                final UUID requestId = UUID.randomUUID();
                try (DaemonIoDeadline ignored = DaemonIoDeadline.after(control, Duration.ofSeconds(1))) {
                    DaemonProtocol.writeRequest(control, new DaemonRequest(DaemonOperation.STOP, discovery.sessionId(), requestId,
                            Instant.now(), stateRoot, List.of()), new byte[32]);
                    assertThatThrownBy(() -> DaemonProtocol.readResponse(control, discovery.sessionId(), requestId,
                            discovery.authenticationSecret())).isInstanceOf(IOException.class);
                }
                Thread.sleep(3500);
                assertThat(daemon.process.isAlive()).as(daemon.output()).isTrue();
                Files.writeString(stateRoot.resolve("release-command"), "release");
                assertThat(daemon.process.waitFor(10, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
                assertThat(daemon.process.exitValue()).as(daemon.output()).isEqualTo(CliExitCode.DAEMON_START_FAILED.code());
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        }
    }

    private void failListenerAfterAccepting(final SocketChannel control) throws Exception {
        awaitMarker("command-entered");
        final int port = ((InetSocketAddress) control.getLocalAddress()).getPort();
        Files.writeString(stateRoot.resolve("fail-listener"), Integer.toString(port));
        awaitMarker("runtime-failed");
    }

    @Test
    void embeddedExecutionRetainsTheInstanceLockInsteadOfHaltingItsJvm() throws Exception {
        try (ChildProcess daemon = startFixture("embedded", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"))) {
                awaitMarker("command-entered");
                try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                Thread.sleep(3500);
                assertThat(daemon.process.isAlive()).as(daemon.output()).isTrue();
                try (DaemonStateStore.LockHandle competing = new DaemonStateStore(paths()).tryLock()) {
                    assertThat(competing).isNull();
                }
                assertThat(new DaemonStateStore(paths()).read().sessionId()).isEqualTo(discovery.sessionId());
                Files.writeString(stateRoot.resolve("release-command"), "release");
                assertExited(daemon);
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        }
    }

    @Test
    void disconnectAndDuplicateStopCannotRestartTheBudget() throws Exception {
        try (ChildProcess daemon = startFixture("command", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"));
                 SocketChannel duplicate = LocalDaemonTransport.connect(discovery.endpoint())) {
                awaitMarker("command-entered");
                final long firstStop = System.nanoTime();
                request(discovery, DaemonOperation.STOP, List.of()).close();
                Thread.sleep(2000);
                try (PendingReply stop = requestOn(duplicate, discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                assertExited(daemon);
                assertThat(Duration.ofNanos(System.nanoTime() - firstStop)).isLessThan(Duration.ofMillis(4500));
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        }
    }

    @Test
    void aCommandFinishingDuringGraceKeepsItsSuccessfulResult() throws Exception {
        try (ChildProcess daemon = startFixture("command", true, false)) {
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, List.of("held"))) {
                awaitMarker("command-entered");
                try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
                    assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                Files.writeString(stateRoot.resolve("release-command"), "release");
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                assertExited(daemon);
            }
        }
    }

    @Test
    void refusedDuplicateStartupCannotArmASuccessfulStopWatchdog() throws Exception {
        try (ChildProcess original = startFixture("smtp", true, false)) {
            final DaemonDiscovery discovery = awaitReady(original);
            try (ChildProcess duplicate = startFixture("duplicate-hook", true, false)) {
                awaitMarker("duplicate-hook-exit-code");
                assertThat(Files.readString(stateRoot.resolve("duplicate-hook-exit-code")))
                        .isEqualTo(Integer.toString(CliExitCode.DAEMON_REFUSED.code()));
                Thread.sleep(3500);
                assertThat(duplicate.process.isAlive()).as(duplicate.output()).isTrue();
            }
            stopAndAwait(original, discovery);
        }
    }

    @Test
    void failedStartupCannotArmASuccessfulStopWatchdog() throws Exception {
        try (ChildProcess daemon = startFixture("failure-hook", true, false)) {
            awaitMarker("failure-hook-exit-code");
            assertThat(Files.readString(stateRoot.resolve("failure-hook-exit-code")))
                    .isEqualTo(Integer.toString(CliExitCode.DAEMON_START_FAILED.code()));
            Thread.sleep(3500);
            assertThat(daemon.process.isAlive()).as(daemon.output()).isTrue();
        }
    }

    @Test
    void signalShutdownKeepsItsConventionalExitStatus() throws Exception {
        assumeFalse(DaemonPaths.isWindows(), "Process.destroy does not deliver Unix SIGTERM on Windows");
        try (ChildProcess daemon = startFixture("smtp", true, false)) {
            awaitReady(daemon);
            daemon.process.destroy();
            assertThat(daemon.process.waitFor(10, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
            assertThat(daemon.process.exitValue()).isEqualTo(143);
        }
    }

    @Test
    void restartWaitsForTheHeldDaemonToExitBeforePublishingItsReplacement() throws Exception {
        ProcessHandle replacement = null;
        long originalPid = -1;
        try (HeldSmtp smtp = new HeldSmtp(); ChildProcess daemon = startFixture("smtp", true, false)) {
            originalPid = daemon.process.pid();
            final DaemonDiscovery discovery = awaitReady(daemon);
            try (PendingReply send = request(discovery, DaemonOperation.EXECUTE, sendArguments(smtp.port()));
                 ChildProcess restart = restartAfterRecipient(smtp)) {
                assertThat(daemon.process.waitFor(10, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
                assertThat(restart.process.waitFor(35, TimeUnit.SECONDS)).as(restart.output()).isTrue();
                final String restartOutput = restart.process.exitValue() == 0 ? restart.output() : retainRestartDiagnostics(restart);
                assertThat(restart.process.exitValue()).as(restartOutput).isZero();
                final DaemonDiscovery current = new DaemonStateStore(paths()).read();
                assertThat(current.pid()).isNotEqualTo(discovery.pid());
                replacement = ProcessHandle.of(current.pid()).orElseThrow();
                assertThat(replacement.isAlive()).isTrue();
                try (PendingReply ready = request(current, DaemonOperation.READY, List.of())) {
                    assertThat(ready.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
                }
                try (ChildProcess stop = startProcess("org.simplejavamail.cli.SimpleJavaMail",
                        List.of("daemon", "stop", "--daemon-instance=" + INSTANCE), true, false)) {
                    assertThat(stop.process.waitFor(20, TimeUnit.SECONDS)).as(stop.output()).isTrue();
                    assertThat(stop.process.exitValue()).as(stop.output()).isZero();
                }
                replacement.onExit().get(5, TimeUnit.SECONDS);
                assertThat(send.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
            }
        } finally {
            if (replacement == null) { replacement = findOwnedReplacement(originalPid); }
            if (replacement != null && replacement.isAlive()) {
                replacement.destroyForcibly();
                replacement.onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private ProcessHandle findOwnedReplacement(final long originalPid) throws IOException {
        final DaemonDiscovery discovery = new DaemonStateStore(paths()).read();
        if (discovery == null || discovery.pid() == originalPid) { return null; }
        final ProcessHandle candidate = ProcessHandle.of(discovery.pid()).orElse(null);
        if (candidate == null || !candidate.isAlive()) { return null; }
        final Instant started = candidate.info().startInstant().orElse(null);
        return started != null && Math.abs(started.toEpochMilli() - discovery.processStartMillis()) <= 2000 ? candidate : null;
    }

    private String retainRestartDiagnostics(final ChildProcess restart) throws IOException {
        final Path startupLog = paths().startupLogFile();
        if (!Files.isRegularFile(startupLog)) { return restart.output(); }
        final Path retained = Files.createTempFile(Path.of("target", "daemon-shutdown-process-logs"), "restart-startup-", ".log");
        Files.copy(startupLog, retained, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return restart.output() + System.lineSeparator() + Files.readString(retained);
    }

    private ChildProcess restartAfterRecipient(final HeldSmtp smtp) throws Exception {
        assertThat(smtp.recipientReceived.await(35, TimeUnit.SECONDS)).isTrue();
        return startProcess("org.simplejavamail.cli.SimpleJavaMail",
                List.of("daemon", "restart", "--daemon-instance=" + INSTANCE), true, false);
    }

    private void awaitMarker(final String marker) throws Exception {
        final long limit = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!Files.exists(stateRoot.resolve(marker)) && System.nanoTime() < limit) { Thread.sleep(25); }
        assertThat(stateRoot.resolve(marker)).exists();
    }

    private void stopAndAwait(final ChildProcess daemon, final DaemonDiscovery discovery) throws Exception {
        try (PendingReply stop = request(discovery, DaemonOperation.STOP, List.of())) {
            assertThat(stop.await(Duration.ofSeconds(5)).category()).isEqualTo(CliExitCode.SUCCESS);
        }
        assertExited(daemon);
    }

    private static void assertExited(final ChildProcess daemon) throws Exception {
        assertThat(daemon.process.waitFor(10, TimeUnit.SECONDS)).as(daemon.output()).isTrue();
        assertThat(daemon.process.exitValue()).as(daemon.output()).isZero();
    }

    private List<String> sendArguments(final int port) {
        return List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "recipient@example.test", "--email:withSubject", "Held SMTP",
                "--email:withPlainText", "This server never replies to RCPT.",
                "--mailer:withConnectionConflictPolicy", "ALLOW_EXPLICIT_OVERRIDES",
                "--mailer:withSMTPServer", "127.0.0.1", Integer.toString(port),
                "--mailer:withConnectTimeout", "5000", "--mailer:withReadTimeout", "0");
    }

    private ChildProcess startDaemon() throws IOException {
        return startProcess("org.simplejavamail.cli.SimpleJavaMail", List.of("daemon", "run", "--daemon-instance=" + INSTANCE), true, false);
    }

    private ChildProcess startFixture(final String scenario, final boolean forceTcp, final boolean withoutBatch) throws IOException {
        return startProcess(DaemonShutdownTestProcess.class.getName(), List.of(scenario, INSTANCE), forceTcp, withoutBatch);
    }

    private ChildProcess startProcess(final String mainClass, final List<String> commandArguments,
            final boolean forceTcp, final boolean withoutBatch) throws IOException {
        final Path outputDirectory = Files.createDirectories(Path.of("target", "daemon-shutdown-process-logs"));
        final Path log = Files.createTempFile(outputDirectory, "daemon-", ".log");
        final List<String> arguments = new ArrayList<>();
        arguments.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
        arguments.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
        if (forceTcp) { arguments.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true"); }
        arguments.add("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        arguments.add("-Dsimplejavamail.cli.daemon.console-child=true");
        arguments.add("-cp");
        arguments.add(classPath(withoutBatch));
        arguments.add(mainClass);
        arguments.addAll(commandArguments);
        return new ChildProcess(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start(), log);
    }

    private static String classPath(final boolean withoutBatch) {
        return Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !withoutBatch || !entry.replace('\\', '/').contains("/modules/batch-module/target/")
                        && !(Path.of(entry).getFileName().toString().startsWith("batch-module-") && entry.endsWith(".jar")))
                .collect(Collectors.joining(File.pathSeparator));
    }

    private DaemonDiscovery awaitReady(final ChildProcess daemon) throws Exception {
        final DaemonStateStore store = new DaemonStateStore(paths());
        final long limit = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (System.nanoTime() < limit) {
            assertThat(daemon.process.isAlive()).as(daemon.output()).isTrue();
            final DaemonDiscovery discovery = store.read();
            if (discovery != null && discovery.pid() == daemon.process.pid()) {
                try (PendingReply ready = request(discovery, DaemonOperation.READY, List.of())) {
                    if (ready.await(Duration.ofSeconds(5)).category() == CliExitCode.SUCCESS) { return discovery; }
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The daemon did not become ready: " + daemon.output());
    }

    private DaemonPaths paths() {
        final String previous = System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY);
        try {
            System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, stateRoot.toString());
            return DaemonPaths.forInstance(INSTANCE);
        } finally {
            if (previous == null) { System.clearProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY); }
            else { System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, previous); }
        }
    }

    private PendingReply request(final DaemonDiscovery discovery, final DaemonOperation operation,
            final List<String> arguments) throws IOException {
        final SocketChannel channel = LocalDaemonTransport.connect(discovery.endpoint());
        return requestOn(channel, discovery, operation, arguments);
    }

    private PendingReply requestOn(final SocketChannel channel, final DaemonDiscovery discovery, final DaemonOperation operation,
            final List<String> arguments) throws IOException {
        final UUID requestId = UUID.randomUUID();
        try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(5))) {
            DaemonProtocol.writeRequest(channel, new DaemonRequest(operation, discovery.sessionId(), requestId,
                    Instant.now(), stateRoot, arguments), discovery.authenticationSecret());
            return new PendingReply(channel, discovery, requestId);
        } catch (IOException | RuntimeException | Error failure) {
            channel.close();
            throw failure;
        }
    }

    private record PendingReply(SocketChannel channel, DaemonDiscovery discovery, UUID requestId) implements AutoCloseable {
        private DaemonResponse await(final Duration timeout) throws IOException {
            try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, timeout)) {
                return DaemonProtocol.readResponse(channel, discovery.sessionId(), requestId, discovery.authenticationSecret());
            }
        }
        @Override public void close() throws IOException { channel.close(); }
    }

    private record ChildProcess(Process process, Path log) implements AutoCloseable {
        private String output() throws IOException { return Files.readString(log); }
        @Override public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned child must not escape its test").isTrue();
            }
        }
    }

    /** Owns only test sockets; no release action exists before the daemon's termination assertion. */
    private static final class HeldSmtp implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private final CountDownLatch recipientReceived = new CountDownLatch(1);
        private final Thread worker;
        private volatile Socket connection;

        private HeldSmtp() throws IOException {
            worker = new Thread(this::serve, "held-shutdown-smtp");
            worker.setDaemon(true);
            worker.start();
        }

        private int port() { return listener.getLocalPort(); }

        private void serve() {
            try (Socket socket = listener.accept()) {
                connection = socket;
                final BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                final BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                reply(output, "220 localhost shutdown fixture");
                String command;
                while ((command = input.readLine()) != null) {
                    if (command.startsWith("EHLO") || command.startsWith("HELO")) { reply(output, "250 localhost"); }
                    else if (command.startsWith("MAIL FROM")) { reply(output, "250 sender accepted"); }
                    else if (command.startsWith("RCPT TO")) {
                        recipientReceived.countDown();
                        // Wait for process death to close the other endpoint. Never acknowledge this recipient.
                        while (input.read() != -1) { }
                        return;
                    } else { reply(output, "250 OK"); }
                }
            } catch (IOException ignored) {
                // The fixture owner or the terminating daemon closes the connection.
            }
        }

        private static void reply(final BufferedWriter output, final String response) throws IOException {
            output.write(response + "\r\n");
            output.flush();
        }

        @Override public void close() throws Exception {
            listener.close();
            if (connection != null) { connection.close(); }
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
        }
    }
}
