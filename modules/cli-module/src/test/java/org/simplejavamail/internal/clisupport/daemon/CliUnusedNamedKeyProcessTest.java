package org.simplejavamail.internal.clisupport.daemon;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CliUnusedNamedKeyProcessTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"off", "require"})
    @EnabledOnOs(OS.WINDOWS)
    void unusedLockedKeyDoesNotPreventConnectingInEitherProcessMode(final String mode) throws Exception {
        final String instance = "unused-key-" + mode;
        final Path key = Files.writeString(directory.resolve("unused.pem"), "unopened unused key");
        final Wiser smtp = Wiser.create(SMTPServer.port(0));
        smtp.start();
        boolean daemonStarted = false;
        final Map<String, String> properties = Map.of(
                "simplejavamail.smtp.host", "localhost",
                "simplejavamail.smtp.port", Integer.toString(smtp.getServer().getPortAllocated()),
                "simplejavamail.mailers.available.smtp.host", "localhost",
                "simplejavamail.mailers.available.smtp.port", Integer.toString(smtp.getServer().getPortAllocated()),
                "simplejavamail.mailers.unused.smtp.host", "unused.example.invalid",
                "simplejavamail.mailers.unused.dkim.signing.private_key_file_or_data", key.toString(),
                "simplejavamail.mailers.unused.dkim.signing.selector", "mail",
                "simplejavamail.mailers.unused.dkim.signing.signing_domain", "example.org");
        try (FileChannel ignored = FileChannel.open(key, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            if (mode.equals("require")) {
                assertSuccess(invoke(properties, "daemon", "start", "--daemon-instance=" + instance));
                daemonStarted = true;
            }
            final Map<String, String> clientProperties = daemonStarted ? Map.of() : properties;
            for (int request = 0; request < 2; request++) {
                assertSuccess(invoke(clientProperties, "connect", "--daemon=" + mode, "--daemon-instance=" + instance));
            }
            assertSuccess(invoke(clientProperties, "connect", "--daemon=" + mode, "--daemon-instance=" + instance,
                    "--mailer:withFailoverMailerName", "unused", "--mailer:withFailoverMailerName", "available"));
            final Invocation selected = invoke(clientProperties, "connect", "--daemon=" + mode, "--daemon-instance=" + instance,
                    "--mailer:withFailoverMailerName", "unused");
            assertThat(selected.exitCode()).as(selected.output()).isNotZero();
            assertThat(selected.output()).contains("Unable to derive Mailer identity");
            if (daemonStarted) {
                final Invocation status = invoke(Map.of(), "daemon", "status", "--daemon-instance=" + instance);
                assertSuccess(status);
                assertThat(status.output()).contains("mailers=2");
            }
        } finally {
            try {
                if (daemonStarted) {
                    assertSuccess(invoke(Map.of(), "daemon", "stop", "--daemon-instance=" + instance));
                }
            } finally {
                smtp.stop();
            }
        }
    }

    private Invocation invoke(final Map<String, String> properties, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
        command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + directory.resolve("state"));
        command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true");
        command.add("-Dsimplejavamail.cli.daemon.console-child=true");
        properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), "org.simplejavamail.cli.SimpleJavaMail"));
        command.addAll(List.of(arguments));
        final Path output = Files.createTempFile(directory, "cli-", ".log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("CLI process completed").isTrue();
            return new Invocation(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned CLI process stopped").isTrue();
            }
        }
    }

    private static void assertSuccess(final Invocation invocation) {
        assertThat(invocation.exitCode()).as(invocation.output()).isZero();
    }

    private record Invocation(int exitCode, String output) { }
}
