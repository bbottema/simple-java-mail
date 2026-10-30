package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/** Test-only child entry point; short budgets cannot be supplied through the production CLI or properties. */
public final class DaemonShutdownTestProcess {
    private DaemonShutdownTestProcess() { }

    public static void main(final String[] arguments) {
        final String scenario = arguments[0];
        final String instance = arguments[1];
        final Path stateRoot = Path.of(System.getProperty(DaemonPaths.STATE_DIRECTORY_PROPERTY));
        final DaemonShutdown shutdown = scenario.equals("embedded")
                ? DaemonShutdown.observing(Duration.ofSeconds(1), Duration.ofSeconds(3), System::nanoTime, null, null)
                : DaemonShutdown.standalone(Duration.ofSeconds(1), Duration.ofSeconds(3));
        final Function<DaemonRequest, CliExecutionResult> executor = scenario.equals("command") || scenario.equals("embedded")
                || scenario.equals("late-jvm") || scenario.equals("failed-accept")
                ? request -> holdCommand(stateRoot, scenario.equals("late-jvm")) : null;
        final DaemonMailerRegistry registry = scenario.equals("close") ? blockingRegistry(stateRoot) : null;
        if (scenario.equals("hook") || scenario.equals("duplicate-hook") || scenario.equals("failure-hook") || scenario.equals("late-jvm")) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                writeMarker(stateRoot.resolve("hook-entered"));
                awaitForever();
            }, "blocked-test-hook"));
        }
        if (scenario.equals("failure-hook")) {
            try { Files.createDirectories(DaemonPaths.forInstance(instance).discoveryFile()); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        final DaemonServer server = new DaemonServer(instance, executor, shutdown, registry);
        if (scenario.equals("failed-accept")) { failListenerWhenRequested(server, shutdown, stateRoot); }
        final int exitCode = server.run();
        writeValue(stateRoot.resolve(scenario + "-exit-code"), Integer.toString(exitCode));
        System.exit(exitCode);
    }

    private static void failListenerWhenRequested(final DaemonServer server, final DaemonShutdown shutdown, final Path stateRoot) {
        final Thread fault = new Thread(() -> {
            try {
                final Path requested = stateRoot.resolve("fail-listener");
                while (!Files.exists(requested)) { Thread.sleep(10); }
                final int controlPort = Integer.parseInt(Files.readString(requested));
                awaitAcceptedControlAndFailListener(server, controlPort);
                while (shutdown.remainingNanos() == Duration.ofSeconds(3).toNanos()) { Thread.sleep(5); }
                writeMarker(stateRoot.resolve("runtime-failed"));
            } catch (ReflectiveOperationException | IOException | InterruptedException failure) {
                throw new AssertionError("Unable to inject the listener failure", failure);
            }
        }, "fail-test-listener");
        fault.setDaemon(true);
        fault.start();
    }

    @SuppressWarnings("unchecked")
    private static void awaitAcceptedControlAndFailListener(final DaemonServer server, final int controlPort)
            throws ReflectiveOperationException, IOException, InterruptedException {
        // Inject only an I/O failure. Keep the production admission state and STOP validation untouched.
        final Field connectionsField = DaemonServer.class.getDeclaredField("openConnections");
        connectionsField.setAccessible(true);
        final Set<SocketChannel> channels = (Set<SocketChannel>) connectionsField.get(server);
        while (!containsAcceptedPeer(channels, controlPort)) { Thread.sleep(5); }
        final Field transportField = DaemonServer.class.getDeclaredField("transport");
        transportField.setAccessible(true);
        ((LocalDaemonTransport.Server) transportField.get(server)).close();
    }

    private static boolean containsAcceptedPeer(final Set<SocketChannel> channels, final int controlPort) {
        for (SocketChannel channel : channels) {
            try {
                if (channel.getRemoteAddress() instanceof InetSocketAddress peer && peer.getPort() == controlPort) { return true; }
            } catch (IOException ignored) { /* Another accepted connection may finish while the fixture observes ownership. */ }
        }
        return false;
    }

    private static CliExecutionResult holdCommand(final Path stateRoot, final boolean exitWhenSignalled) {
        writeMarker(stateRoot.resolve("command-entered"));
        while (!Files.exists(stateRoot.resolve("release-command"))) {
            if (exitWhenSignalled && Files.exists(stateRoot.resolve("trigger-jvm-shutdown"))) { System.exit(0); }
            try { Thread.sleep(25); }
            catch (InterruptedException ignored) { /* Deliberately non-cooperative command for the child-process boundary. */ }
        }
        return new CliExecutionResult(CliExitCode.SUCCESS, "completed\n", "");
    }

    private static DaemonMailerRegistry blockingRegistry(final Path stateRoot) {
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ZERO);
        final Mailer mailer = (Mailer) Proxy.newProxyInstance(Mailer.class.getClassLoader(), new Class<?>[]{Mailer.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        writeMarker(stateRoot.resolve("close-entered"));
                        awaitForever();
                    }
                    return null;
                });
        final CliMailerProfile profile = CliMailerProfile.create(ConfigLoader.builder().load(), List.of(), new byte[32]);
        try (MailerProvider.Lease ignored = registry.acquire(profile, () -> mailer)) { }
        return registry;
    }

    private static void writeMarker(final Path marker) {
        writeValue(marker, "entered");
    }

    private static void writeValue(final Path marker, final String value) {
        try { Files.writeString(marker, value); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void awaitForever() {
        final CountDownLatch neverReleased = new CountDownLatch(1);
        boolean waiting = true;
        while (waiting) {
            try { neverReleased.await(); waiting = false; }
            catch (InterruptedException ignored) { /* The owned test process is the final cancellation boundary. */ }
        }
    }
}
