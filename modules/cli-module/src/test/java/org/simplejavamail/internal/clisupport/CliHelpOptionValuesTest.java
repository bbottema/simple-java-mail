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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CliHelpOptionValuesTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"How to use --help", "manual--help", "--not-an-option--help", "--email:notAnOption--help"})
    void subjectValuesKeepFollowingBodyAndSmtpChoices(final String subject) {
        final Email email = send("--email:withSubject", subject, "--email:withPlainText", "Here are the instructions.");
        assertThat(email.getSubject()).isEqualTo(subject);
        assertThat(email.getPlainText()).isEqualTo("Here are the instructions.");
    }

    @Test
    void bodyAndHeaderValuesKeepFollowingRepeatedOptions() {
        final Email email = send("--email:withPlainText", "Read --help", "--email:appendText", " and continue",
                "--email:withHeader", "X-Guide", "header--help", "--email:withHeader", "X-Guide", "retained",
                "--email:withSubject", "Finished");
        assertThat(email.getPlainText()).isEqualTo("Read --help and continue");
        assertThat(email.getHeaders().get("X-Guide")).containsExactly("header--help", "retained");
        assertThat(email.getSubject()).isEqualTo("Finished");
    }

    @Test
    void aRequestRelativeFileEndingInHelpDoesNotDiscardFollowingOptions() throws Exception {
        Files.writeString(workingDirectory.resolve("body--help"), "File body");
        final Email email = send("--email:withPlainTextFromFile", "body--help", "--email:withSubject", "After the file");
        assertThat(email.getPlainText()).isEqualTo("File body");
        assertThat(email.getSubject()).isEqualTo("After the file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h", "--email:withSubject--help"})
    void genuineHelpIgnoresLaterMalformedArgumentsWithoutExecutingBuilders(final String help) {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:withPlainTextFromFile", "missing.txt",
                    "--mailer:withSMTPServer", "smtp.example.test", "not-a-number", help, "--some-mistyped-option");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("Usage", "--email:withSubject");
            assertThat(result.stderr()).isEmpty();
            verifyNoInteractions(provider);
        }
    }

    @Test
    void invalidInputBeforeHelpKeepsItsParserError() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--some-mistyped-option", "--email:withSubject--help");
            assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stderr()).contains("--some-mistyped-option");
            verifyNoInteractions(provider);
        }
    }

    @Test
    void argumentFileValuesKeepFollowingArgumentsFromInsideAndOutsideTheFile() throws Exception {
        final Path argumentFile = workingDirectory.resolve("help-value.args");
        Files.writeString(argumentFile, "--email:withSubject\n'How to use --help'\n--email:withPlainText\n'Inside the file'\n");
        final Email email = send("@" + argumentFile, "--email:appendText", " and outside");
        assertThat(email.getSubject()).isEqualTo("How to use --help");
        assertThat(email.getPlainText()).isEqualTo("Inside the file and outside");
    }

    @Test
    void aStandaloneRecognizedHelpOptionKeepsItsExistingMeaningEvenAfterAnOptionalValue() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:withSubject",
                    "--email:withPlainText--help", "--some-mistyped-option");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("Usage", "--email:withPlainText");
            verifyNoInteractions(provider);
        }
    }

    @Test
    void endOfOptionsStopsHelpRecognition() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--", "--help", "--later-argument");
            assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stderr()).contains("--help", "--later-argument");
            verifyNoInteractions(provider);
        }
    }

    private Email send(final String... options) {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final AtomicReference<String> host = new AtomicReference<>();
        final AtomicReference<Integer> port = new AtomicReference<>();
        final List<String> arguments = new ArrayList<>(List.of("send", "--email:startingBlank",
                "--email:from", "sender@example.test", "--email:to", "receiver@example.test",
                "--mailer:withSMTPServer", "before.example.test", "25"));
        arguments.addAll(Arrays.asList(options));
        arguments.addAll(List.of("--mailer:withSMTPServer", "after.example.test", "587"));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> {
            try (Mailer configured = factory.get()) {
                host.set(configured.getServerConfig().getHost());
                port.set(configured.getServerConfig().getPort());
            } catch (Exception e) {
                throw new AssertionError("Unable to inspect and close the configured test Mailer", e);
            }
            return lease;
        })) {
            final CliExecutionResult result = execute(environment, arguments.toArray(new String[0]));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            verify(lease).close();
            assertThat(host.get()).isEqualTo("after.example.test");
            assertThat(port.get()).isEqualTo(587);
            return email.getValue();
        }
    }

    private CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, workingDirectory, UUID.randomUUID(), environment, null);
    }
}
