package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption;
import org.simplejavamail.api.internal.clisupport.model.Cli;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CliEnvelopeIdTest {

    @TempDir Path workingDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void composedAndExactOptionsReachTheSendUnencoded(final boolean exact) throws Exception {
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        final MailerProvider provider = (profile, factory) -> lease;
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = arguments(exact);
            arguments.add(exact ? "--email:fixingExactEnvelopeId" : "--email:fixingEnvelopeId");
            arguments.add("order +42=");
            arguments.add(exact ? "--email:withEnvelopeDsnNotifyOptions" : "--email:withDeliveryStatusNotificationNotifyOptions");
            arguments.add("failure,delay");
            arguments.add(exact ? "--email:withEnvelopeDsnReturnOption" : "--email:withDeliveryStatusNotificationReturnOption");
            arguments.add("HDRS");
            final CliExecutionResult result = execute(environment, arguments);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).isEmpty();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            assertThat(email.getValue().getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("order +42=");
            assertThat(email.getValue().getDeliveryStatusNotification().getNotifyOptions()).containsExactly(NotifyOption.FAILURE, NotifyOption.DELAY);
            assertThat(email.getValue().getDeliveryStatusNotification().getReturnOption()).isEqualTo(ReturnOption.HEADERS_ONLY);
            verify(lease).close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidIdentifiersFailBeforeAcquiringAMailer(final boolean exact) throws Exception {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = arguments(exact);
            arguments.add(exact ? "--email:fixingExactEnvelopeId" : "--email:fixingEnvelopeId");
            arguments.add("not\na-valid-identifier");
            final CliExecutionResult result = execute(environment, arguments);
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            if (exact) {
                assertThat(result.stderr()).contains("printable ASCII");
            } else {
                // Reflective builder calls deliberately hide their argument and nested exception text.
                assertThat(result.stderr()).contains("--email:fixingEnvelopeId", "<redacted>");
            }
            assertThat(result.stderr()).doesNotContain("not\na-valid-identifier");
            verifyNoInteractions(provider);
        }
    }

    @Test
    void generatedHelpDescribesBothOptions() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", "--help"));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("--email:fixingEnvelopeId", "--email:fixingExactEnvelopeId");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--email:fixingEnvelopeId--help", "--email:fixingExactEnvelopeId--help"})
    void optionHelpDescribesTheEncodedIdentifierLimit(final String option) {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", option));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("94", "ENVID");
        }
    }

    @Test
    void recipientPoliciesRemainJavaOnlyWithoutHidingExistingSharedDsnOptions() throws Exception {
        assertThat(ExactEmailBuilder.class.getMethod("withEnvelopeRecipients", Recipient[].class).getAnnotation(Cli.ExcludeApi.class)).isNotNull();
        assertThat(ExactEmailBuilder.class.getMethod("withDeliveryStatusNotificationNotifyOptions", NotifyOption[].class)
                .getAnnotation(Cli.ExcludeApi.class)).isNull();
        assertThat(ExactEmailBuilder.class.getMethod("withDeliveryStatusNotificationReturnOption", ReturnOption.class)
                .getAnnotation(Cli.ExcludeApi.class)).isNull();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", "--help"));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("--email:withEnvelopeRecipients", "--email:withDeliveryStatusNotificationNotifyOptions")
                    .doesNotContain("--recipient:", "--email:withFixedDeliveryStatusNotificationNotifyOptions");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "false|NotifyOptions|NEVER,FAILURE", "true|NotifyOptions|unknown",
            "false|ReturnOption|unknown", "true|ReturnOption|unknown"
    })
    void invalidDsnValuesFailDuringCliConversionBeforeAcquiringAMailer(final boolean exact, final String setting, final String value) throws Exception {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = arguments(exact);
            arguments.add((exact ? "--email:withEnvelopeDsn" : "--email:withDeliveryStatusNotification") + setting);
            arguments.add(value);
            final CliExecutionResult result = execute(environment, arguments);
            assertThat(result.exitCode()).as(result.stderr()).isNotZero();
            assertThat(result.stderr()).contains("Unable to parse a command-line value");
            verifyNoInteractions(provider);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnOptionStillRequiresACliValueEvenThoughJavaAcceptsNull(final boolean exact) throws Exception {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = arguments(exact);
            final String option = exact ? "--email:withEnvelopeDsnReturnOption" : "--email:withDeliveryStatusNotificationReturnOption";
            arguments.add(option);
            final CliExecutionResult result = execute(environment, arguments);
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stderr()).contains(option);
            verifyNoInteractions(provider);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--email:withDeliveryStatusNotificationNotifyOptions--help", "--email:withEnvelopeDsnNotifyOptions--help"})
    void notificationHelpListsAllTextChoices(final String option) {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", option));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("SUCCESS", "FAILURE", "DELAY", "NEVER");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--email:withDeliveryStatusNotificationReturnOption--help", "--email:withEnvelopeDsnReturnOption--help"})
    void returnHelpListsReadableChoicesAndExistingWireAliases(final String option) {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, List.of("send", option));
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("FULL_MESSAGE", "HEADERS_ONLY", "HDRS");
        }
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
