package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliRecipientRejectionHandlingTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void generatedComposedAndExactBooleanOptionsPreserveTrueAndFalse(final boolean exact, final boolean choice) throws Exception {
        final List<Email> sent = new CopyOnWriteArrayList<>();
        try (CliExecutionEnvironment environment = environment(sent)) {
            final List<String> arguments = arguments(exact);
            arguments.add(exact ? "--email:withExactSendingToAcceptedRecipients" : "--email:withSendingToAcceptedRecipients");
            arguments.add(Boolean.toString(choice));
            final CliExecutionResult result = execute(environment, arguments);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(sent).singleElement().satisfies(email -> assertThat(email.getSendingToAcceptedRecipients()).isEqualTo(choice));
        }
    }

    @Test
    void generatedHelpIncludesConcreteContractsButNoClearOptions() {
        try (CliExecutionEnvironment environment = environment(new CopyOnWriteArrayList<>())) {
            final CliExecutionResult help = execute(environment, List.of("send", "--help"));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("--email:withSendingToAcceptedRecipients", "--email:withExactSendingToAcceptedRecipients")
                    .doesNotContain("--email:clearSendingToAcceptedRecipients");
            final CliExecutionResult optionHelp = execute(environment, List.of("send", "--email:withSendingToAcceptedRecipients--help"));
            assertThat(optionHelp.exitCode()).isZero();
            assertThat(optionHelp.stdout()).contains("Alice", "Bob", "Carol", "partial acceptance", "MailSubmissionException", "value set on this builder")
                    .doesNotContain("local choice", "The selected provider adapter must support", "mail.smtp.sendpartial");
            assertThat(execute(environment, List.of("send", "--email:clearSendingToAcceptedRecipients")).exitCode()).isNotZero();
        }
    }

    @Test
    void reusedDaemonExecutionEnvironmentDoesNotLeakChoicesAcrossConcurrentOrLaterRequests() throws Exception {
        final List<Email> sent = new CopyOnWriteArrayList<>();
        try (CliExecutionEnvironment environment = environment(sent)) {
            final List<String> first = arguments(false);
            first.addAll(List.of("--email:withSendingToAcceptedRecipients", "true"));
            final List<String> second = arguments(false);
            second.addAll(List.of("--email:withSendingToAcceptedRecipients", "false"));
            final CompletableFuture<CliExecutionResult> firstResult = CompletableFuture.supplyAsync(() -> execute(environment, first));
            final CompletableFuture<CliExecutionResult> secondResult = CompletableFuture.supplyAsync(() -> execute(environment, second));
            assertThat(firstResult.join().exitCode()).isZero();
            assertThat(secondResult.join().exitCode()).isZero();
            assertThat(sent).extracting(Email::getSendingToAcceptedRecipients).containsExactlyInAnyOrder(true, false);
            assertThat(execute(environment, arguments(false)).exitCode()).isZero();
            assertThat(sent.get(2).getSendingToAcceptedRecipients()).isNull();
        }
    }

    private CliExecutionEnvironment environment(final List<Email> sent) {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        doAnswer(call -> {
            sent.add(call.getArgument(0));
            return null;
        }).when(sync).sendMail(any(Email.class));
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        return new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease);
    }

    private List<String> arguments(final boolean exact) throws Exception {
        if (exact) {
            Files.writeString(workingDirectory.resolve("exact.eml"), "From: sender@example.test\r\nTo: visible@example.test\r\n\r\nbody\r\n");
            return new ArrayList<>(List.of("send", "--email:startingFromExactEml", "exact.eml", "--email:withEnvelopeRecipients", "receiver@example.test"));
        }
        return new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test", "--email:to", "receiver@example.test"));
    }

    private CliExecutionResult execute(final CliExecutionEnvironment environment, final List<String> arguments) {
        return CliSupport.execute(arguments.toArray(new String[0]), workingDirectory, UUID.randomUUID(), environment, null);
    }
}
