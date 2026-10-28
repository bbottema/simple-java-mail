package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonHelpSelectorOrderTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @MethodSource("helpAndLaterSelectors")
    void genuineHelpIgnoresLaterDaemonSelectors(final String help, final String[] laterSelectors) {
        final List<String> arguments = new ArrayList<>(List.of("send", "-d", help));
        arguments.addAll(List.of(laterSelectors));

        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(arguments.toArray(new String[0]), workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.LOCAL);
        assertThat(request.commandArguments()).containsExactly("send", help);
    }

    static Stream<Arguments> helpAndLaterSelectors() {
        return Stream.of("--help", "-h", "--email:withSubject--help").flatMap(help -> Stream.of(
                new String[] {"--daemon=typo"},
                new String[] {"--daemon-instance=../bad"},
                new String[] {"--daemon-instance", "../bad"},
                new String[] {"--daemon-instance"},
                new String[] {"--daemon=require"},
                new String[] {"--no-daemon"})
                .map(selectors -> Arguments.of(help, selectors)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "--email:withSubject--help"})
    void invalidSelectorsBeforeHelpKeepTheirDiagnostics(final String help) {
        assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
                new String[] {"send", "--daemon=typo", help}, workingDirectory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daemon");
        assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
                new String[] {"send", "--daemon-instance=../bad", help}, workingDirectory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance");
        assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
                new String[] {"send", "-d", "--daemon=require", help}, workingDirectory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting");
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual--help", "--not-an-option--help", "--email:notAnOption--help"})
    void literalHelpLookalikesDoNotHideInvalidSelectors(final String value) {
        assertThatThrownBy(() -> DaemonBootstrapRequest.parse(
                new String[] {"send", "--email:withSubject", value, "--daemon=typo"}, workingDirectory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daemon");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "--email:withSubject--help"})
    void endOfOptionsPreventsHelpFromChangingTheSelectedRoute(final String value) {
        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
                new String[] {"send", "--daemon=require", "--", value}, workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
        assertThat(request.commandArguments()).containsExactly("send", "--", value);
    }

    @Test
    void endOfOptionsKeepsLaterSelectorSpellingsLiteral() {
        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
                new String[] {"send", "--daemon=require", "--", "--help", "--daemon=typo", "--daemon-instance"},
                workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
        assertThat(request.commandArguments()).containsExactly("send", "--", "--help", "--daemon=typo", "--daemon-instance");
    }

    @Test
    void argumentFileHelpUsesTheSameCutoffAfterBoundedExpansion() throws Exception {
        Files.writeString(workingDirectory.resolve("help.args"),
                "send\n--daemon=require\n--email:withSubject--help\n--daemon-instance\n");

        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(
                new String[] {"@help.args"}, workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.LOCAL);
        assertThat(request.commandArguments()).containsExactly("send", "--email:withSubject--help");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "--email:withSubject--help"})
    void publishedEntrypointShowsHelpWithoutReadingContentOrStartingDaemon(final String help) throws Exception {
        final Path state = workingDirectory.resolve("daemon-state");
        final Path output = workingDirectory.resolve("help-output.txt");
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString(),
                "-Xmx128m", "-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + state,
                "-cp", System.getProperty("java.class.path"), "org.simplejavamail.cli.SimpleJavaMail",
                "send", "--daemon=acquire", "--email:withPlainTextFromFile", "missing.txt",
                "--mailer:withSMTPServer", "smtp.example.test", "not-a-port", help, "--daemon=typo"));
        final Process process = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        final boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        final String text = Files.readString(output, StandardCharsets.UTF_8);

        assertThat(exited).as(text).isTrue();
        assertThat(process.exitValue()).as(text).isZero();
        assertThat(text).contains("Usage", "--email:withSubject");
        assertThat(text).doesNotContain("Unable to read", "Unknown daemon execution mode");
        assertThat(Files.exists(state)).isFalse();
    }
}
