package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonSelectorValuesTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"--daemon=off", "--daemon=require", "--daemon=off disables persistence",
            "--daemon-instance=../literal", "--daemon-instance", "-d", "--daemon", "--no-daemon"})
    void messageValuesKeepTheSelectedModeAndInstance(final String value) {
        final String[] command = {"send", "--daemon=require", "--daemon-instance=selected",
                "--email:withSubject", value, "--email:withPlainText", value,
                "--email:withHeader", "X-Example", value, "--email:withHeader", "X-Example", "retained"};

        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(command, workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.DAEMON_COMMAND);
        assertThat(request.mode()).isEqualTo(DaemonExecutionMode.REQUIRE);
        assertThat(request.instance()).isEqualTo("selected");
        assertThat(request.commandArguments()).containsExactly("send", "--email:withSubject", value,
                "--email:withPlainText", value, "--email:withHeader", "X-Example", value,
                "--email:withHeader", "X-Example", "retained");
    }

    @Test
    void theFirstRequiredHeaderArgumentCanAlsoSpellASelector() {
        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(new String[] {"send", "--daemon=require",
                "--email:withHeader", "--no-daemon", "header value", "--email:withSubject", "retained"}, workingDirectory);

        assertThat(request.mode()).isEqualTo(DaemonExecutionMode.REQUIRE);
        assertThat(request.commandArguments()).containsExactly("send", "--email:withHeader", "--no-daemon", "header value",
                "--email:withSubject", "retained");
    }

    @Test
    void repeatedSpellingCanBeAValueAndThenAGenuineSelector() {
        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(new String[] {"send",
                "--email:withSubject", "--daemon=require", "--daemon=require", "--daemon-instance=selected"}, workingDirectory);

        assertThat(request.mode()).isEqualTo(DaemonExecutionMode.REQUIRE);
        assertThat(request.instance()).isEqualTo("selected");
        assertThat(request.commandArguments()).containsExactly("send", "--email:withSubject", "--daemon=require");
    }

    @Test
    void genuineHelpAfterLiteralSelectorsStillCutsOffLaterMalformedSelectors() {
        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(new String[] {"send", "--daemon=require",
                "--email:withSubject", "--daemon=off", "--email:withSubject--help", "--daemon=typo"}, workingDirectory);

        assertThat(request.route()).isEqualTo(DaemonBootstrapRequest.Route.LOCAL);
        assertThat(request.commandArguments()).containsExactly("send", "--email:withSubject", "--daemon=off",
                "--email:withSubject--help");
    }

    @Test
    void argumentFileValuesKeepTheirOwnershipAfterExpansion() throws Exception {
        Files.writeString(workingDirectory.resolve("values.args"), "send --daemon=require --daemon-instance=selected\n"
                + "--email:withSubject '--daemon=off disables persistence'\n"
                + "--email:withHeader X-Example '--daemon-instance=../literal'\n");

        final DaemonBootstrapRequest request = DaemonBootstrapRequest.parse(new String[] {"@values.args"}, workingDirectory);

        assertThat(request.mode()).isEqualTo(DaemonExecutionMode.REQUIRE);
        assertThat(request.instance()).isEqualTo("selected");
        assertThat(request.commandArguments()).containsExactly("send", "--email:withSubject", "--daemon=off disables persistence",
                "--email:withHeader", "X-Example", "--daemon-instance=../literal");
    }
}
