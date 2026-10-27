package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CliArgumentFilesTest {
    @TempDir Path workingDirectory;

    @Test
    void rawExecutionExpandsNestedFilesFromTheExplicitCallerDirectory() throws Exception {
        Files.writeString(workingDirectory.resolve("request.args"), "@nested.args\n--email:appendText ' and outside'\n");
        Files.writeString(workingDirectory.resolve("nested.args"), "--email:withSubject 'Caller subject'\n"
                + "--email:withPlainText 'Caller body'\n--email:withHeader X-Example --mailer:async=false\n");

        final Email email = sendRaw("@request.args", "--email:withHeader", "X-Example", "retained");
        assertThat(email.getSubject()).isEqualTo("Caller subject");
        assertThat(email.getPlainText()).isEqualTo("Caller body and outside");
        assertThat(email.getHeaders().get("X-Example")).containsExactly("--mailer:async=false", "retained");
    }

    @Test
    void rawEscapesLoseExactlyOneAtSignEvenWhenTheTargetIsReadable() throws Exception {
        final Path target = workingDirectory.resolve("literal.args");
        Files.writeString(target, "wrong-expansion");
        final String escaped = "@@" + target;
        final Email email = sendRaw("--email:withSubject", escaped, "--email:withPlainText", escaped,
                "--email:withHeader", "X-Triple", "@" + escaped);

        assertThat(email.getSubject()).isEqualTo("@" + target);
        assertThat(email.getPlainText()).isEqualTo("@" + target);
        assertThat(email.getHeaders().get("X-Triple")).containsExactly(escaped);
    }

    @Test
    void aMissingCallerFileAndExpandedValuesNeverReadTheExecutionDirectory() throws Exception {
        final Path trap = Files.createTempFile(Path.of("").toAbsolutePath(), "sjm-at-file-", ".args");
        try {
            Files.writeString(trap, "wrong-process-directory-expansion");
            final String literal = "@" + trap.getFileName();
            assertThat(sendRaw("--email:withSubject", literal).getSubject()).isEqualTo(literal);
            assertThat(sendExpanded("--email:withSubject", literal).getSubject()).isEqualTo(literal);
            assertThat(sendExpanded("--email:withSubject", "@" + literal).getSubject()).isEqualTo("@" + literal);
        } finally {
            Files.deleteIfExists(trap);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-utf8", "oversized", "recursive", "nesting", "argument-count"})
    void invalidRawArgumentFilesReturnACommandLineErrorBeforeAcquiringAMailer(final String scenario) throws Exception {
        final Path file = workingDirectory.resolve("invalid.args");
        final String diagnostic;
        switch (scenario) {
            case "malformed-utf8":
                Files.write(file, new byte[] {(byte) 0xc3, 0x28});
                diagnostic = "Unable to read argument file";
                break;
            case "oversized":
                Files.write(file, new byte[64 * 1024 + 1]);
                diagnostic = "Argument file exceeds";
                break;
            case "recursive":
                Files.writeString(file, "@invalid.args");
                diagnostic = "Recursive argument file";
                break;
            case "nesting":
                Files.writeString(file, "@nested-1.args");
                for (int depth = 1; depth <= 4; depth++) {
                    Files.writeString(workingDirectory.resolve("nested-" + depth + ".args"),
                            depth == 4 ? "send --help" : "@nested-" + (depth + 1) + ".args");
                }
                diagnostic = "Argument-file nesting exceeds";
                break;
            case "argument-count":
                Files.writeString(file, String.join(" ", Collections.nCopies(513, "value")));
                diagnostic = "Expanded command exceeds";
                break;
            default:
                throw new AssertionError(scenario);
        }

        assertRawFailure(new String[] {"@invalid.args", "--email:withPlainTextFromFile", "missing-body.txt"}, diagnostic);
    }

    @Test
    void directRawArgumentsKeepTheSamePerArgumentByteLimit() {
        assertRawFailure(new String[] {"send", "x".repeat(64 * 1024 + 1)}, "Command argument exceeds");
    }

    @Test
    void theRawOneShotEntryPointExpandsFilesAndClassifiesExpansionErrors() throws Exception {
        final Path arguments = workingDirectory.resolve("help.args");
        Files.writeString(arguments, "send\n--help\n");
        assertThat(CliSupport.runCLIWithExitCode(new String[] {"@" + arguments})).isZero();

        Files.write(arguments, new byte[] {(byte) 0xc3, 0x28});
        assertThat(CliSupport.runCLIWithExitCode(new String[] {"@" + arguments})).isEqualTo(CliExitCode.CLI_ERROR.code());
    }

    private void assertRawFailure(final String[] args, final String diagnostic) {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = CliSupport.execute(args, workingDirectory, UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains(diagnostic).doesNotContain("missing-body.txt");
            verifyNoInteractions(provider);
        }
    }

    private Email sendRaw(final String... options) {
        return send(options, (arguments, environment) ->
                CliSupport.execute(arguments, workingDirectory, UUID.randomUUID(), environment, null));
    }

    private Email sendExpanded(final String... options) {
        return send(options, (arguments, environment) ->
                CliSupport.executeExpandedArguments(arguments, workingDirectory, UUID.randomUUID(), environment, null));
    }

    private Email send(final String[] options, final BiFunction<String[], CliExecutionEnvironment, CliExecutionResult> execution) {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final List<String> arguments = new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "receiver@example.test", "--mailer:withSMTPServer", "smtp.example.test", "25"));
        arguments.addAll(Arrays.asList(options));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
            final CliExecutionResult result = execution.apply(arguments.toArray(new String[0]), environment);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            verify(lease).close();
            return email.getValue();
        }
    }
}
