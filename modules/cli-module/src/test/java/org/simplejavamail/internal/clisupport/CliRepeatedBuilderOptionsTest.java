package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CliRepeatedBuilderOptionsTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"to", "cc", "bcc"})
    void repeatedRecipientOptionsKeepBothAddresses(final String recipientOption) {
        final Email email = send("--email:" + recipientOption, "alice@example.test",
                "--email:" + recipientOption, "bob@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress)
                .containsExactly("alice@example.test", "bob@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getName).containsExactly(null, null);
    }

    @Test
    void repeatedRecipientsKeepEachOptionalNameAndTheSharedMailboxListInterpretation() {
        final Email email = send("--email:to", "First recipient", "alice@example.test",
                "--email:to", "alice@example.test",
                "--email:to", "\"Support @ Acme, Europe\" <support@example.test>;Team: audit@example.test,backup@example.test;");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly(
                "alice@example.test", "alice@example.test", "support@example.test", "audit@example.test", "backup@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getName)
                .containsExactly("First recipient", null, "Support @ Acme, Europe", null, null);
    }

    @Test
    void repeatedHeadersRetainAllValues() {
        final Email email = send("--email:to", "receiver@example.test",
                "--email:withHeader", "X-Trace", "first=value",
                "--email:withHeader", "X-Trace", "second=value");
        assertThat(email.getHeaders().get("X-Trace")).containsExactly("first=value", "second=value");
    }

    @Test
    void repeatedAttachmentsKeepEachOptionalFilenameAndRequestRelativeSource() throws Exception {
        Files.writeString(workingDirectory.resolve("first.txt"), "first attachment");
        Files.writeString(workingDirectory.resolve("second.txt"), "second attachment");
        final Email email = send("--email:to", "receiver@example.test",
                "--email:withDescribedAttachment", "first.txt",
                "--email:withDescribedAttachment", "renamed.txt", "second.txt", "second description");
        assertThat(email.getAttachments()).hasSize(2);
        assertThat(email.getAttachments().get(0).getDescription()).isNull();
        assertThat(email.getAttachments().get(0).readAllData()).isEqualTo("first attachment");
        assertThat(email.getAttachments().get(1).getName()).isEqualTo("renamed.txt");
        assertThat(email.getAttachments().get(1).getDescription()).isEqualTo("second description");
        assertThat(email.getAttachments().get(1).readAllData()).isEqualTo("second attachment");
    }

    @Test
    void repeatedClearAndAddCallsFollowTheirActualInterleavedOrder() {
        final Email email = send("--email:to", "discarded@example.test", "--email:clearRecipients",
                "--email:to", "also-discarded@example.test", "--email:clearRecipients",
                "--email:to", "retained@example.test",
                "--email:withPlainText", "discarded", "--email:clearPlainText",
                "--email:appendText", "also discarded", "--email:clearPlainText", "--email:appendText", "retained=value");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly("retained@example.test");
        assertThat(email.getPlainText()).isEqualTo("retained=value");
    }

    @Test
    void repeatedReplacementCallsKeepTheLastValue() {
        final Email email = send("--email:to", "receiver@example.test",
                "--email:withSubject", "first", "--email:withSubject", "last=value");
        assertThat(email.getSubject()).isEqualTo("last=value");
    }

    @Test
    void argumentFileOccurrencesStayInOrderWithSurroundingOptions() throws Exception {
        final Path argumentFile = workingDirectory.resolve("recipients.args");
        Files.writeString(argumentFile, "--email:to\nalice@example.test\n--email:to\n'Bob Example'\nbob@example.test\n");
        final Email email = send("--email:to", "before@example.test", "@" + argumentFile,
                "--email:to", "after@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly(
                "before@example.test", "alice@example.test", "bob@example.test", "after@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getName).containsExactly(null, null, "Bob Example", null);
    }

    @Test
    void aMissingValueOnARepeatedOptionStillFailsBeforeAcquiringAMailer() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank",
                    "--email:from", "sender@example.test", "--email:to", "alice@example.test", "--email:to");
            assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stderr()).contains("--email:to");
            verifyNoInteractions(provider);
        }
    }

    @Test
    void concurrentRequestsKeepTheirOwnRepeatedOptionOccurrences() throws Exception {
        final ExecutorService workers = Executors.newFixedThreadPool(3);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Email>> submitted = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                final int request = index;
                submitted.add(workers.submit(() -> {
                    assertThat(start.await(10, SECONDS)).isTrue();
                    return send("--email:to", "first-" + request + "@example.test",
                            "--email:to", "last-" + request + "@example.test",
                            "--email:withSubject", "discarded", "--email:withSubject", "request-" + request);
                }));
            }
            start.countDown();
            for (int index = 0; index < submitted.size(); index++) {
                final Email email = submitted.get(index).get(30, SECONDS);
                assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly(
                        "first-" + index + "@example.test", "last-" + index + "@example.test");
                assertThat(email.getSubject()).isEqualTo("request-" + index);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private Email send(final String... options) {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final List<String> arguments = new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test"));
        arguments.addAll(Arrays.asList(options));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
            final CliExecutionResult result = execute(environment, arguments.toArray(new String[0]));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            verify(lease).close();
            return email.getValue();
        }
    }

    private CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, workingDirectory, UUID.randomUUID(), environment, null);
    }
}
