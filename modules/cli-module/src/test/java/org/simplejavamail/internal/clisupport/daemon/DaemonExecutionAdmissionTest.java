package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Authenticated server admission stays separate from reply observers and retained application results. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DaemonExecutionAdmissionTest {
    @TempDir Path stateRoot;

    @Test
    void duplicateAndChangedPayloadKeepOneExecutionAfterTheOriginalClientDisconnects() throws Exception {
        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            executions.incrementAndGet();
            awaitRelease(release);
            return CliExecutionResult.success("original result", "");
        }, release::countDown)) {
            final UUID id = UUID.randomUUID();
            final PendingReply original = fixture.execute(id, "original");
            fixture.awaitStatus(status -> value(status, "activeRequests") == 1);
            try (PendingReply duplicate = fixture.execute(id, "original");
                 PendingReply changed = fixture.execute(id, "changed")) {
                final DaemonResponse refused = changed.await();
                assertThat(refused.category()).isEqualTo(CliExitCode.DAEMON_REFUSED);
                assertThat(refused.stderr()).contains("different content");
                fixture.awaitStatus(status -> value(status, "activeReplies") == 2);
                assertThat(executions).hasValue(1);

                original.close();
                release.countDown();

                final DaemonResponse observed = duplicate.await();
                assertThat(observed.category()).isEqualTo(CliExitCode.SUCCESS);
                assertThat(observed.stdout()).isEqualTo("original result");
            }
            try (PendingReply replay = fixture.execute(id, "original")) {
                assertThat(replay.await().stdout()).isEqualTo("original result");
            }
            assertThat(executions).hasValue(1);
        }
    }

    @Test
    void unexpectedReplyEnqueueRejectionRetiresOnlyNewOwnersAndReturnsEveryReservation() throws Exception {
        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            executions.incrementAndGet();
            awaitRelease(release);
            return CliExecutionResult.success("accepted original", "");
        }, release::countDown)) {
            final UUID id = UUID.randomUUID();
            try (PendingReply original = fixture.execute(id, "original")) {
                fixture.awaitStatus(status -> value(status, "activeRequests") == 1 && value(status, "activeReplies") == 1);
                final Semaphore capacity = privateField(fixture.server, "replyCapacity", Semaphore.class);
                final RequestLedger retained = privateField(fixture.server, "ledger", RequestLedger.class);
                final RequestLedger.Registration accepted = retained.register(fixture.request(id, "original"));
                assertThat(accepted.newlyRegistered()).isFalse();
                assertThat(capacity.availablePermits()).isEqualTo(39);

                // Inject a shutdown race after READY, leaving normal admission open while the reply executor rejects enqueue.
                privateField(fixture.server, "executionReplies", ThreadPoolExecutor.class).shutdown();
                for (int index = 0; index < 8; index++) {
                    final UUID newId = UUID.randomUUID();
                    final String newArgument = "rejected-new-" + index;
                    try (PendingReply duplicate = fixture.execute(id, "original");
                         PendingReply fresh = fixture.execute(newId, newArgument)) {
                        assertThat(duplicate.await().category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                        assertThat(fresh.await().category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                    }
                    final RequestLedger.Registration refused = retained.register(fixture.request(newId, newArgument));
                    assertThat(refused.newlyRegistered()).isFalse();
                    assertThat(refused.result().isDone()).isTrue();
                    assertThat(refused.result().getNow(null).category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                    assertThat(accepted.result().isDone()).isFalse();
                    assertThat(executions).hasValue(1);
                    assertThat(capacity.availablePermits()).isEqualTo(39);
                }

                release.countDown();
                final DaemonResponse originalResult = original.await();
                assertThat(originalResult.category()).isEqualTo(CliExitCode.SUCCESS);
                assertThat(originalResult.stdout()).isEqualTo("accepted original");
                fixture.awaitStatus(status -> value(status, "activeReplies") == 0);
                assertThat(capacity.availablePermits()).isEqualTo(40);
                assertThat(executions).hasValue(1);
            }
        }
    }

    @Test
    void stopRefusesQueuedNewOwnersWithoutPoisoningQueuedOrCapacityRefusedDuplicates() throws Exception {
        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            executions.incrementAndGet();
            awaitRelease(release);
            return CliExecutionResult.success(request.arguments().get(0), "");
        }, release::countDown)) {
            final List<PendingReply> accepted = new ArrayList<>();
            final UUID originalId = UUID.randomUUID();
            accepted.add(fixture.execute(originalId, "original"));
            for (int index = 1; index < 8; index++) {
                accepted.add(fixture.execute(UUID.randomUUID(), "accepted-" + index));
            }
            fixture.awaitStatus(status -> value(status, "activeReplies") == 8
                    && value(status, "activeRequests") == 4 && value(status, "queuedRequests") == 4);

            final PendingReply queuedDuplicate = fixture.execute(originalId, "original");
            final List<PendingReply> unadmitted = new ArrayList<>();
            for (int index = 0; index < 31; index++) {
                unadmitted.add(fixture.execute(UUID.randomUUID(), "unadmitted-" + index));
            }
            fixture.awaitStatus(status -> value(status, "activeReplies") == 8
                    && value(status, "queuedReplies") == 32 && value(status, "recentRequests") == 39);

            try (PendingReply capacityDuplicate = fixture.execute(originalId, "original");
                 PendingReply capacityNew = fixture.execute(UUID.randomUUID(), "overflow")) {
                assertThat(capacityDuplicate.await().category()).isEqualTo(CliExitCode.DAEMON_BUSY);
                assertThat(capacityNew.await().category()).isEqualTo(CliExitCode.DAEMON_BUSY);
            }
            assertThat(value(fixture.status(), "recentRequests")).isEqualTo(39);
            assertThat(executions).hasValue(4);

            assertThat(fixture.stop().category()).isEqualTo(CliExitCode.SUCCESS);
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown();

            for (PendingReply reply : accepted) {
                assertThat(reply.await().category()).isEqualTo(CliExitCode.SUCCESS);
            }
            final DaemonResponse duplicateResult = queuedDuplicate.await();
            assertThat(duplicateResult.category()).isEqualTo(CliExitCode.SUCCESS);
            assertThat(duplicateResult.stdout()).isEqualTo("original");
            for (PendingReply reply : unadmitted) {
                assertThat(reply.await().category()).isEqualTo(CliExitCode.DAEMON_BUSY);
            }
            assertThat(executions).hasValue(8);
            assertThat(fixture.awaitStopped()).isEqualTo(CliExitCode.SUCCESS.code());
        }
    }

    @Test
    void simultaneousStopAndAuthenticatedHandoffsKeepOneTerminalResultPerIdentity() throws Exception {
        final Map<UUID, AtomicInteger> executions = new ConcurrentHashMap<>();
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            executions.computeIfAbsent(request.requestId(), ignored -> new AtomicInteger()).incrementAndGet();
            return CliExecutionResult.success(request.requestId().toString(), "");
        }, () -> { })) {
            final List<DaemonRequest> requests = new ArrayList<>();
            requests.add(new DaemonRequest(DaemonOperation.STOP, fixture.discovery.sessionId(), UUID.randomUUID(),
                    Instant.now(), fixture.workingDirectory, List.of()));
            final List<UUID> identities = new ArrayList<>();
            for (int index = 0; index < 8; index++) { identities.add(UUID.randomUUID()); }
            for (int index = 0; index < 16; index++) {
                requests.add(fixture.request(identities.get(index % 8), "racing-" + (index % 8)));
            }
            final List<PendingReply> replies = new ArrayList<>();
            for (DaemonRequest request : requests) { replies.add(fixture.prepare(request)); }
            fixture.awaitAcceptedChannels(replies);

            final ExecutorService writers = Executors.newFixedThreadPool(requests.size());
            final CountDownLatch ready = new CountDownLatch(requests.size());
            final CountDownLatch start = new CountDownLatch(1);
            try {
                final List<Future<?>> written = new ArrayList<>();
                for (int index = 0; index < requests.size(); index++) {
                    final int selected = index;
                    written.add(writers.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Frame writers were not released"); }
                        fixture.writePrepared(replies.get(selected), requests.get(selected));
                        return null;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                final Object gate = privateField(fixture.server, "admissionGate", Object.class);
                synchronized (gate) {
                    start.countDown();
                    for (Future<?> frame : written) { frame.get(5, TimeUnit.SECONDS); }
                    awaitAdmissionContenders(gate);
                }
                assertThat(replies.get(0).await().category()).isEqualTo(CliExitCode.SUCCESS);
                final Set<UUID> successful = new HashSet<>();
                for (int index = 1; index < replies.size(); index++) {
                    final DaemonResponse result = replies.get(index).await();
                    assertThat(result.category()).isIn(CliExitCode.SUCCESS, CliExitCode.DAEMON_BUSY);
                    if (result.category() == CliExitCode.SUCCESS) {
                        successful.add(requests.get(index).requestId());
                        assertThat(result.stdout()).isEqualTo(requests.get(index).requestId().toString());
                    }
                }
                assertThat(fixture.awaitStopped()).isEqualTo(CliExitCode.SUCCESS.code());
                assertThat(executions.keySet()).containsExactlyInAnyOrderElementsOf(successful);
                assertThat(executions.values()).allSatisfy(count -> assertThat(count).hasValue(1));
                assertThat(privateField(fixture.server, "replyCapacity", Semaphore.class).availablePermits()).isEqualTo(40);
                assertThat(privateField(fixture.server, "openConnections", Set.class)).isEmpty();
            } finally {
                start.countDown();
                writers.shutdownNow();
                assertThat(writers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void queuedDuplicateRetainsItsCapturedResultAcrossLedgerOutputPressure() throws Exception {
        final String output = "result-" + "x".repeat(199_993);
        final AtomicInteger originalExecutions = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            final String command = request.arguments().get(0);
            if (command.equals("original")) { originalExecutions.incrementAndGet(); }
            if (command.startsWith("held-")) { awaitRelease(release); }
            return CliExecutionResult.success(output, "");
        }, release::countDown)) {
            final UUID id = UUID.randomUUID();
            try (PendingReply original = fixture.execute(id, "original")) {
                assertThat(original.await().stdout()).hasSize(200_000);
            }
            // 41 results fit under the ledger's 8 MiB limit; one further large completion replaces the oldest output with a tombstone.
            for (int index = 0; index < 40; index++) {
                try (PendingReply prelude = fixture.execute(UUID.randomUUID(), "prelude-" + index)) {
                    assertThat(prelude.await().category()).isEqualTo(CliExitCode.SUCCESS);
                }
            }

            final List<PendingReply> held = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                held.add(fixture.execute(UUID.randomUUID(), "held-" + index));
            }
            fixture.awaitStatus(status -> value(status, "activeReplies") == 8
                    && value(status, "activeRequests") == 4 && value(status, "queuedRequests") == 4);
            try (PendingReply queuedDuplicate = fixture.execute(id, "original")) {
                fixture.awaitStatus(status -> value(status, "queuedReplies") == 1);
                release.countDown();
                for (PendingReply reply : held) {
                    assertThat(reply.await().category()).isEqualTo(CliExitCode.SUCCESS);
                }
                final DaemonResponse captured = queuedDuplicate.await();
                assertThat(captured.category()).isEqualTo(CliExitCode.SUCCESS);
                assertThat(captured.stdout()).hasSize(200_000);
            }
            try (PendingReply laterObserver = fixture.execute(id, "original")) {
                final DaemonResponse retiredOutput = laterObserver.await();
                assertThat(retiredOutput.category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
                assertThat(retiredOutput.stderr()).contains("not executed again");
            }
            assertThat(originalExecutions).hasValue(1);
        }
    }

    @Test
    void shutdownFencesAnIncompleteResultEvenWhenTheApplicationExecutorTerminates() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            entered.countDown();
            throw new AssertionError("fixture application failure before a result is returned");
        }, () -> { })) {
            try (PendingReply original = fixture.execute(UUID.randomUUID(), "fails-without-result")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                fixture.awaitStatus(status -> value(status, "activeRequests") == 0
                        && value(status, "activeReplies") == 1);
                assertThat(fixture.stop().category()).isEqualTo(CliExitCode.SUCCESS);

                final DaemonResponse result = original.await();
                assertThat(result.category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
                assertThat(result.stderr()).contains("outcome is unknown");
            }
            assertThat(fixture.awaitStopped()).isEqualTo(CliExitCode.SUCCESS.code());
        }
    }

    @Test
    void failedAuthenticationSessionAndFreshnessNeverReachApplicationAdmission() throws Exception {
        final AtomicInteger executions = new AtomicInteger();
        try (ServerFixture fixture = new ServerFixture(stateRoot, request -> {
            executions.incrementAndGet();
            return CliExecutionResult.success("valid", "");
        }, () -> { })) {
            final DaemonRequest ordinary = fixture.request(UUID.randomUUID(), "invalid-auth");
            try (PendingReply invalidMac = fixture.open(ordinary, new byte[32])) {
                assertThatThrownBy(invalidMac::await).isInstanceOf(IOException.class);
            }
            final DaemonRequest wrongSession = new DaemonRequest(DaemonOperation.EXECUTE, UUID.randomUUID(), UUID.randomUUID(),
                    Instant.now(), fixture.workingDirectory, List.of("invalid-session"));
            try (PendingReply invalidSession = fixture.open(wrongSession, fixture.discovery.authenticationSecret())) {
                assertThatThrownBy(invalidSession::await).isInstanceOf(IOException.class);
            }
            final DaemonRequest stale = new DaemonRequest(DaemonOperation.EXECUTE, fixture.discovery.sessionId(), UUID.randomUUID(),
                    Instant.now().minus(DaemonProtocol.FRESHNESS).minusSeconds(1), fixture.workingDirectory, List.of("stale"));
            try (PendingReply invalidFreshness = fixture.open(stale, fixture.discovery.authenticationSecret())) {
                assertThatThrownBy(invalidFreshness::await).isInstanceOf(IOException.class);
            }
            assertThat(executions).hasValue(0);
            assertThat(value(fixture.status(), "recentRequests")).isZero();
            try (PendingReply valid = fixture.execute(UUID.randomUUID(), "valid")) {
                assertThat(valid.await().category()).isEqualTo(CliExitCode.SUCCESS);
            }
            assertThat(executions).hasValue(1);
        }
    }

    private static int value(final String status, final String key) {
        return status.lines().filter(line -> line.startsWith(key + "="))
                .mapToInt(line -> Integer.parseInt(line.substring(key.length() + 1))).findFirst().orElse(-1);
    }

    private static <T> T privateField(final DaemonServer server, final String name, final Class<T> type) throws ReflectiveOperationException {
        final Field field = DaemonServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(server));
    }

    private static void awaitAdmissionContenders(final Object gate) throws InterruptedException {
        final int identity = System.identityHashCode(gate);
        final long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < limit) {
            boolean stop = false;
            boolean execute = false;
            for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
                if (thread.getThreadState() != Thread.State.BLOCKED || thread.getLockInfo() == null
                        || thread.getLockInfo().getIdentityHashCode() != identity) { continue; }
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(DaemonServer.class.getName())) {
                        stop |= frame.getMethodName().equals("requestStop");
                        execute |= frame.getMethodName().equals("transferExecutionRequest");
                    }
                }
            }
            if (stop && execute) { return; }
            Thread.sleep(5);
        }
        throw new AssertionError("STOP and EXECUTE did not both reach the held admission gate");
    }

    private static void awaitRelease(final CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("The test did not release application work"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Application fixture was interrupted", interrupted);
        }
    }

    private static final class ServerFixture implements AutoCloseable {
        private static final String INSTANCE = "execution-admission";
        private static final List<String> PROPERTY_NAMES = List.of(DaemonPaths.STATE_DIRECTORY_PROPERTY,
                DaemonPaths.FORCE_TCP_PROPERTY, "simplejavamail.cli.daemon.log-file", "log4j.configurationFile");
        private final Map<String, String> savedProperties = new LinkedHashMap<>();
        private final ExecutorService serverThread = Executors.newSingleThreadExecutor();
        private final ConcurrentLinkedQueue<PendingReply> observers = new ConcurrentLinkedQueue<>();
        private final Path workingDirectory;
        private final Runnable releaseWork;
        private final DaemonServer server;
        private final Future<Integer> serverResult;
        private DaemonDiscovery discovery;

        private ServerFixture(final Path root, final Function<DaemonRequest, CliExecutionResult> commandExecutor,
                final Runnable releaseWork) throws Exception {
            this.workingDirectory = root.toAbsolutePath().normalize();
            this.releaseWork = releaseWork;
            PROPERTY_NAMES.forEach(name -> savedProperties.put(name, System.getProperty(name)));
            // A cold in-process logger must not retain a daemon file appender inside this fixture's temporary directory.
            System.setProperty("log4j.configurationFile", DaemonExecutionAdmissionTest.class.getResource("/log4j2.xml").toExternalForm());
            System.setProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY, workingDirectory.toString());
            System.setProperty(DaemonPaths.FORCE_TCP_PROPERTY, "true");
            server = new DaemonServer(INSTANCE, commandExecutor);
            serverResult = serverThread.submit(server::run);
            try {
                final DaemonStateStore stateStore = new DaemonStateStore(DaemonPaths.forInstance(INSTANCE));
                final long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (System.nanoTime() < limit) {
                    if (serverResult.isDone()) { throw new IllegalStateException("Daemon startup returned " + serverResult.get()); }
                    discovery = stateStore.read();
                    if (discovery != null) {
                        try (PendingReply ready = control(DaemonOperation.READY)) {
                            if (ready.await().category() == CliExitCode.SUCCESS) { return; }
                        }
                    }
                    Thread.sleep(10);
                }
                throw new IllegalStateException("Daemon fixture did not become ready");
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        private DaemonRequest request(final UUID id, final String argument) {
            return new DaemonRequest(DaemonOperation.EXECUTE, discovery.sessionId(), id, Instant.now(), workingDirectory, List.of(argument));
        }

        private PendingReply execute(final UUID id, final String argument) throws IOException {
            return open(request(id, argument), discovery.authenticationSecret());
        }

        private PendingReply control(final DaemonOperation operation) throws IOException {
            return open(new DaemonRequest(operation, discovery.sessionId(), UUID.randomUUID(), Instant.now(), workingDirectory, List.of()),
                    discovery.authenticationSecret());
        }

        private PendingReply open(final DaemonRequest request, final byte[] secret) throws IOException {
            final SocketChannel channel = LocalDaemonTransport.connect(discovery.endpoint());
            try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(5))) {
                DaemonProtocol.writeRequest(channel, request, secret);
                final PendingReply reply = new PendingReply(channel, discovery, request.requestId());
                observers.add(reply);
                return reply;
            } catch (IOException | RuntimeException | Error failure) {
                channel.close();
                throw failure;
            }
        }

        private PendingReply prepare(final DaemonRequest request) throws IOException {
            final PendingReply reply = new PendingReply(LocalDaemonTransport.connect(discovery.endpoint()), discovery, request.requestId());
            observers.add(reply);
            return reply;
        }

        private void writePrepared(final PendingReply reply, final DaemonRequest request) throws IOException {
            try (DaemonIoDeadline ignored = DaemonIoDeadline.after(reply.channel(), Duration.ofSeconds(5))) {
                DaemonProtocol.writeRequest(reply.channel(), request, discovery.authenticationSecret());
            }
        }

        private void awaitAcceptedChannels(final List<PendingReply> replies) throws Exception {
            final Set<SocketAddress> expected = new HashSet<>();
            for (PendingReply reply : replies) { expected.add(reply.channel().getLocalAddress()); }
            final Set<?> tracked = privateField(server, "openConnections", Set.class);
            final long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < limit) {
                final Set<SocketAddress> accepted = new HashSet<>();
                for (Object connection : tracked) {
                    try { accepted.add(((SocketChannel) connection).getRemoteAddress()); }
                    catch (IOException ignored) { /* Another completed control channel may retire during this snapshot. */ }
                }
                if (accepted.containsAll(expected)) { return; }
                Thread.sleep(5);
            }
            throw new AssertionError("The daemon did not accept every prepared race channel");
        }

        private String status() throws IOException {
            try (PendingReply reply = control(DaemonOperation.STATUS)) {
                final DaemonResponse response = reply.await();
                assertThat(response.category()).isEqualTo(CliExitCode.SUCCESS);
                return response.stdout();
            }
        }

        private void awaitStatus(final Predicate<String> condition) throws Exception {
            final long limit = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            String last;
            do {
                last = status();
                if (condition.test(last)) { return; }
                Thread.sleep(10);
            } while (System.nanoTime() < limit);
            throw new AssertionError("Daemon did not reach the expected status:\n" + last);
        }

        private DaemonResponse stop() throws IOException {
            try (PendingReply reply = control(DaemonOperation.STOP)) { return reply.await(); }
        }

        private int awaitStopped() throws Exception {
            return serverResult.get(10, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            releaseWork.run();
            try {
                if (!serverResult.isDone() && discovery != null) {
                    try { stop(); } catch (IOException ignored) { /* Cleanup still waits for or retires the owned server thread. */ }
                }
                serverResult.get(12, TimeUnit.SECONDS);
            } finally {
                for (PendingReply observer : observers) {
                    try { observer.close(); } catch (IOException ignored) { /* Retire the other observers as well. */ }
                }
                serverThread.shutdownNow();
                serverThread.awaitTermination(2, TimeUnit.SECONDS);
                savedProperties.forEach((name, value) -> {
                    if (value == null) { System.clearProperty(name); } else { System.setProperty(name, value); }
                });
            }
        }
    }

    private record PendingReply(SocketChannel channel, DaemonDiscovery discovery, UUID requestId) implements AutoCloseable {
        private DaemonResponse await() throws IOException {
            try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(10))) {
                return DaemonProtocol.readResponse(channel, discovery.sessionId(), requestId, discovery.authenticationSecret());
            }
        }

        @Override public void close() throws IOException { channel.close(); }
    }
}
