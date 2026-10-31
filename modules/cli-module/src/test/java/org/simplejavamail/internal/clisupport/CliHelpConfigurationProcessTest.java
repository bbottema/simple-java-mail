package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises public CLI startup, rather than supplying an already valid execution environment. */
class CliHelpConfigurationProcessTest {

    @TempDir Path temporaryDirectory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("helpRequests")
    void helpAndVersionDoNotLoadInvalidMailConfiguration(final String description, final String expected,
            final String[] arguments) throws Exception {
        final Invocation invocation = invoke(arguments);
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.SUCCESS.code());
        assertThat(invocation.output()).doesNotContain("Unable to initialize", "No daemon", "does-not-exist.txt");
        if (expected != null) {
            assertThat(invocation.output()).contains(expected);
        }
    }

    static Stream<Arguments> helpRequests() {
        return Stream.of(
                Arguments.of("implicit root help", "Usage", new String[0]),
                Arguments.of("root help", "Usage", new String[]{"--help"}),
                Arguments.of("command help", "Usage", new String[]{"send", "--help"}),
                Arguments.of("option help", "--mailer:withSMTPServer", new String[]{"send", "--mailer:withSMTPServer--help"}),
                Arguments.of("version", "Simple Java Mail", new String[]{"--version"}),
                // The command-scoped version flag has no version text of its own; retain its existing successful completion.
                Arguments.of("command version", null, new String[]{"send", "--version"}),
                Arguments.of("help stays local and ignores later input", "--email:withSubject", new String[]{"send",
                        "--daemon=require", "--daemon-instance=help-config-928", "--email:withSubject--help", "--misspelled-option"}),
                Arguments.of("help does not read message content", "Usage", new String[]{"send",
                        "--email:withPlainTextFromFile", "does-not-exist.txt", "--help"}));
    }

    @Test
    void parserErrorsBeforeHelpDoNotBecomeConfigurationFailures() throws Exception {
        final Invocation invocation = invoke("send", "--misspelled-option", "--email:withSubject--help");
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.CLI_ERROR.code());
        assertThat(invocation.output()).contains("--misspelled-option").doesNotContain("Unable to initialize");
    }

    @Test
    void endOfOptionsDoesNotBecomeHelp() throws Exception {
        final Invocation invocation = invoke("send", "--", "--help");
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.CLI_ERROR.code());
        assertThat(invocation.output()).contains("--help").doesNotContain("Unable to initialize");
    }

    @Test
    void helpShapedMessageValuesStillRequireValidConfiguration() throws Exception {
        final Invocation invocation = invoke("validate", "--email:withSubject", "ordinary--help");
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
        assertThat(invocation.output()).contains("Unable to initialize the CLI configuration.");
    }

    @Test
    void executableCommandsStillLoadMailConfiguration() throws Exception {
        final Invocation invocation = invoke("validate", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "recipient@example.test", "--email:withPlainText", "body");
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
        assertThat(invocation.output()).contains("Unable to initialize the CLI configuration.");
    }

    @Test
    void executableCommandsValidateConfigurationBeforeReadingContent() throws Exception {
        final Invocation invocation = invoke("validate", "--email:withPlainTextFromFile", "does-not-exist.txt");
        assertThat(invocation.exitCode()).as(invocation.output()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
        assertThat(invocation.output()).contains("Unable to initialize the CLI configuration.").doesNotContain("does-not-exist.txt");
    }

    private Invocation invoke(final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        final String java = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", java).toString());
        command.add("-Dsimplejavamail.smtp.port=oops");
        command.add("-Dsimplejavamail.cli.daemon=off");
        command.add("-Dsimplejavamail.cli.daemon.state-dir=" + temporaryDirectory.resolve("daemon-state"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.simplejavamail.cli.SimpleJavaMail");
        command.addAll(Arrays.asList(arguments));
        final Path output = Files.createTempFile(temporaryDirectory, "cli-help-config-", ".log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            final boolean exited = process.waitFor(20, TimeUnit.SECONDS);
            assertThat(exited).as(Files.readString(output)).isTrue();
            return new Invocation(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(temporaryDirectory.resolve("daemon-state")).doesNotExist();
        }
    }

    private record Invocation(int exitCode, String output) { }
}
