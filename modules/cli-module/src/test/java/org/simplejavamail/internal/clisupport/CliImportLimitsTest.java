package org.simplejavamail.internal.clisupport;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.valueinterpreters.MessageFilePathToMimeMessageFunction;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliImportLimitsTest {
    @TempDir Path directory;
    private static final Path MESSAGE = Path.of("../simple-java-mail/src/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg")
            .toAbsolutePath().normalize();

    @Test
    void messageArgumentConversionUsesTheSelectedRequestFactory() {
        final SimpleJavaMail unlimited = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final SimpleJavaMail limited = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.conversion.import.maximumpartcount", 2)).load());

        final MimeMessage full = new MessageFilePathToMimeMessageFunction(unlimited, false).convertValue(MESSAGE.toString());
        final MimeMessage partial = new MessageFilePathToMimeMessageFunction(limited, false).convertValue(MESSAGE.toString());

        assertThat(unlimited.converter().fromMimeMessage(full).toEmail().getHTMLText()).isNotBlank();
        assertThat(unlimited.converter().fromMimeMessage(partial).toEmail().getHTMLText()).isNull();
    }

    @Test
    void aRawCapIsRejectedBeforeReadingAnUnsupportedNativeMessageArgument() {
        final SimpleJavaMail limited = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.conversion.import.maximuminputbytes", 128L)).load());

        assertThatThrownBy(() -> new MessageFilePathToMimeMessageFunction(limited, true)
                .convertValue("a-message-file-that-must-not-be-opened.msg"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MimeMessage");
    }

    @Test
    void separateRequestsKeepTheirCapturedImportPoliciesIndependent() {
        final java.util.List<Email> seen = new java.util.ArrayList<>();
        final Mailer mailer = mock(Mailer.class);
        doAnswer(invocation -> { seen.add(invocation.getArgument(0)); return null; }).when(mailer).validate(any(Email.class));
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        for (int execution = 0; execution < 2; execution++) {
            final MailerProvider provider = mock(MailerProvider.class);
            when(provider.tryAcquireExisting(any(CliMailerProfile.class))).thenReturn(Optional.of(lease));
            try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                    "simplejavamail.conversion.import.maximumpartcount", execution == 0 ? 2 : 0)).load(), provider)) {
                final CliExecutionResult result = CliSupport.execute(new String[] {"validate", "--email:replyingTo", MESSAGE.toString(), "false",
                        org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_QUOTING_MARKUP},
                        Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
                assertThat(result.exitCode()).as(result.stderr()).isZero();
            }
        }
        assertThat(seen).hasSize(2);
        // Reply composition can generate HTML from the admitted plain body; only the unlimited source supplies its CID image.
        assertThat(seen.get(0).getEmbeddedImages()).isEmpty();
        assertThat(seen.get(1).getEmbeddedImages()).isNotEmpty();
        assertThat(seen.get(0).getHTMLText()).doesNotContain("cid:");
        assertThat(seen.get(1).getHTMLText()).contains("cid:");
    }

    @Test
    void forwardingRawEmlKeepsItsBorrowedMimeBypassEvenWhenImportBytesAreLimited() throws Exception {
        final Path source = java.nio.file.Files.writeString(directory.resolve("forward.eml"),
                "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Forward source\r\n\r\nForward body");
        final java.util.List<Email> seen = new java.util.ArrayList<>();
        final Mailer mailer = mock(Mailer.class);
        doAnswer(invocation -> { seen.add(invocation.getArgument(0)); return null; }).when(mailer).validate(any(Email.class));
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final MailerProvider provider = mock(MailerProvider.class);
        when(provider.tryAcquireExisting(any(CliMailerProfile.class))).thenReturn(Optional.of(lease));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.conversion.import.maximuminputbytes", 1L)).load(), provider)) {
            final CliExecutionResult result = CliSupport.execute(new String[] {"validate", "--email:forwarding", source.toString()},
                    directory, UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
        }
        assertThat(seen).singleElement().satisfies(email -> assertThat(email.getEmailToForward()).isNotNull());
    }
}
