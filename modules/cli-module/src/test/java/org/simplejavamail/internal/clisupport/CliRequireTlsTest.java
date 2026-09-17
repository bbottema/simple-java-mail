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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliRequireTlsTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void composedAndExactFlagsReachTheEmailModel(final boolean exact) throws Exception {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final MailerProvider provider = (profile, factory) -> lease;
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = arguments(exact);
            arguments.add(exact ? "--email:withExactTlsRequiredForOnwardDelivery" : "--email:withTlsRequiredForOnwardDelivery");

            final CliExecutionResult result = execute(environment, arguments);

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            assertThat(email.getValue().isTlsRequiredForOnwardDelivery()).isTrue();
            verify(lease).close();
        }
    }

    @Test
    void generatedHelpExplainsBothFlags() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", "--help"));

            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("--email:withTlsRequiredForOnwardDelivery",
                    "--email:withExactTlsRequiredForOnwardDelivery");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--email:withTlsRequiredForOnwardDelivery--help",
            "--email:withExactTlsRequiredForOnwardDelivery--help"
    })
    void optionHelpDistinguishesOnwardDeliveryFromTheFirstHop(final String option) {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", option));

            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("onward delivery", "REQUIRETLS")
                    .doesNotContain("best effort");
        }
    }

    private List<String> arguments(final boolean exact) throws Exception {
        if (exact) {
            Files.writeString(workingDirectory.resolve("exact.eml"),
                    "From: sender@example.test\r\nTo: visible@example.test\r\n\r\nbody\r\n");
            return new ArrayList<>(List.of("send", "--email:startingFromExactEml", "exact.eml",
                    "--email:withEnvelopeRecipients", "receiver@example.test"));
        }
        return new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "receiver@example.test"));
    }

    private CliExecutionResult execute(final CliExecutionEnvironment environment, final List<String> arguments) {
        return CliSupport.execute(arguments.toArray(new String[0]), workingDirectory, UUID.randomUUID(), environment, null);
    }
}
