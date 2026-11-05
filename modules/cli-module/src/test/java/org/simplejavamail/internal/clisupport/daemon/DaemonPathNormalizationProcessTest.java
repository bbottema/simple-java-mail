package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual detached startup must agree with the client's derived Unix endpoint; TCP cannot satisfy the Unix cases. */
@Timeout(100)
class DaemonPathNormalizationProcessTest {
    @TempDir Path temporaryDirectory;

    enum Scenario { RELATIVE_PROPERTY, RELATIVE_ENVIRONMENT, DOT_SEGMENTS, ABSOLUTE_CONTROL, CUSTOM_TEMPORARY_ROOT, TCP_CONTROL }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Scenario.class)
    void detachedStartupUsesTheSameDerivedLocationAsItsClient(final Scenario scenario) throws Exception {
        final String instance = "paths-" + UUID.randomUUID().toString().substring(0, 8);
        final Path workingDirectory = Files.createDirectories(temporaryDirectory.resolve("working-" + "a".repeat(45)));
        final Path stateRoot = workingDirectory.resolve("state").toAbsolutePath().normalize();
        final String configuredRoot = switch (scenario) {
            case ABSOLUTE_CONTROL, CUSTOM_TEMPORARY_ROOT -> stateRoot.toString();
            case DOT_SEGMENTS -> Path.of("missing", "..", "state").toString();
            default -> Path.of(".", "state").toString();
        };
        final Path selectedTemporaryRoot = scenario == Scenario.CUSTOM_TEMPORARY_ROOT
                ? shortTemporaryRoot() : Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        final Path recordDirectory = Path.of("target", "daemon-path-regression", instance).toAbsolutePath();
        Files.createDirectories(recordDirectory);
        final Map<String, String> facts = new LinkedHashMap<>();
        final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        facts.put("scenario", scenario.name());
        facts.put("javaVersion", System.getProperty("java.version"));
        facts.put("productVersion", DaemonVersion.PRODUCT_VERSION);
        facts.put("productMajor", Integer.toString(DaemonVersion.PRODUCT_MAJOR));
        facts.put("runId", System.getProperty("sjm.configuration.contract.run", "unrecorded"));
        facts.put("configuredRoot", configuredRoot);
        facts.put("normalizedRoot", stateRoot.toString());
        facts.put("temporaryRoot", selectedTemporaryRoot.toString());
        facts.put("workingDirectory", workingDirectory.toString());
        facts.put("forceTcp", Boolean.toString(scenario == Scenario.TCP_CONTROL));
        assertThat(stateRoot.resolve("v" + DaemonVersion.PRODUCT_MAJOR).resolve(instance)
                .resolve("sjm-" + "a".repeat(20) + ".sock").toString().getBytes(StandardCharsets.UTF_8).length)
                .as("the central regression must trigger short-path fallback").isGreaterThan(96);
        try {
            final Invocation start = invoke(scenario, workingDirectory, configuredRoot, selectedTemporaryRoot,
                    recordDirectory.resolve("start.log"), descendants, "start", instance);
            facts.put("startExitCode", Integer.toString(start.exitCode()));
            assertThat(start.exitCode()).as(start.output()).isZero();

            final Invocation status = invoke(scenario, workingDirectory, configuredRoot, selectedTemporaryRoot,
                    recordDirectory.resolve("status.log"), descendants, "status", instance);
            facts.put("statusExitCode", Integer.toString(status.exitCode()));
            assertThat(status.exitCode()).as(status.output()).isZero();
            final String transport = value(status.output(), "transport");
            facts.put("transport", transport);
            facts.put("pid", value(status.output(), "pid"));
            assertThat(transport).isEqualTo(scenario == Scenario.TCP_CONTROL ? "TCP" : "UNIX");
            assertThat(status.output()).contains("state=READY");
            assertThat(Files.exists(workingDirectory.resolve("missing"))).isFalse();
        } finally {
            final Invocation stop = invoke(scenario, workingDirectory, stateRoot.toString(), selectedTemporaryRoot,
                    recordDirectory.resolve("stop.log"), descendants, "stop", instance);
            facts.put("stopExitCode", Integer.toString(stop.exitCode()));
            final List<String> forcedCleanup = new ArrayList<>();
            for (ProcessHandle descendant : descendants) {
                if (descendant.isAlive()) {
                    try { descendant.onExit().get(5, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException timeout) {
                        forcedCleanup.add(Long.toString(descendant.pid()));
                        descendant.destroyForcibly();
                        descendant.onExit().get(5, TimeUnit.SECONDS);
                    }
                }
            }
            facts.put("trackedDescendants", descendants.stream().map(handle -> Long.toString(handle.pid())).sorted()
                    .collect(Collectors.joining(",")));
            facts.put("liveDescendantsAfterStop", Long.toString(descendants.stream().filter(ProcessHandle::isAlive).count()));
            facts.put("forcedCleanupDescendants", String.join(",", forcedCleanup));
            Files.writeString(recordDirectory.resolve("facts.properties"), facts.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("\n")) + "\n");
            assertThat(stop.exitCode()).as(stop.output()).isIn(CliExitCode.SUCCESS.code(), CliExitCode.DAEMON_ABSENT.code());
            assertThat(descendants).noneMatch(ProcessHandle::isAlive);
            if (scenario == Scenario.CUSTOM_TEMPORARY_ROOT) deleteEmptyTemporaryRoot(selectedTemporaryRoot);
        }
    }

    private static Invocation invoke(final Scenario scenario, final Path workingDirectory, final String stateRoot,
            final Path temporaryRoot, final Path output, final Set<ProcessHandle> descendants,
            final String action, final String instance) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
        if (scenario != Scenario.RELATIVE_ENVIRONMENT) command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
        command.add("-Djava.io.tmpdir=" + temporaryRoot);
        command.add("-Dsimplejavamail.cli.daemon.console-child=true");
        if (scenario == Scenario.TCP_CONTROL) command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true");
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add("org.simplejavamail.cli.SimpleJavaMail");
        command.add("daemon");
        command.add(action);
        command.add("--daemon-instance=" + instance);
        final ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("SJM_DAEMON_STATE_DIR", stateRoot);
        final Process process = builder.start();
        final long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            process.descendants().forEach(descendants::add);
            process.waitFor(50, TimeUnit.MILLISECONDS);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        final String text = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(process.isAlive()).as(text).isFalse();
        return new Invocation(process.exitValue(), text);
    }

    private static String absoluteClassPath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator));
    }

    private static String value(final String output, final String key) {
        return output.lines().filter(line -> line.startsWith(key + "=")).map(line -> line.substring(key.length() + 1))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + key + " in status: " + output));
    }

    private static Path shortTemporaryRoot() throws IOException {
        // macOS's default temporary root can itself be near the Unix-socket path limit.
        final Path base = DaemonPaths.isWindows() ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of("/tmp");
        final Path root = base.resolve("d" + UUID.randomUUID().toString().substring(0, 8));
        return Files.createDirectory(root).toAbsolutePath().normalize();
    }

    private static void deleteEmptyTemporaryRoot(final Path root) throws IOException {
        try (var directories = Files.list(root)) {
            for (Path directory : directories.toList()) Files.delete(directory);
        }
        Files.delete(root);
    }

    private record Invocation(int exitCode, String output) { }
}
