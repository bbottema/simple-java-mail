package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CliRemovedProxyBridgeOptionTest {
    @ParameterizedTest
    @ValueSource(strings = {"--mailer:withProxyBridgePort", "--mailer:withProxyBridgePort=0",
            "--mailer:withProxyBridgePort=private-invalid-port", "--mailer:withProxyBridgePort--help"})
    void removedPortOptionsExplainMigrationBeforeAcquiringAMailer(final String removedOption) {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            for (String command : new String[] { "send", "connect", "probe" }) {
                final CliExecutionResult result = execute(environment, command, removedOption);
                assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
                assertThat(result.stdout()).isEmpty();
                assertThat(result.stderr()).contains("--mailer:withProxyBridgePort was removed", "Remove this option", "connects directly")
                        .doesNotContain("private-invalid-port");
            }
            verifyNoInteractions(provider);
        }
    }

    @Test
    void actualHelpDoesNotAdvertiseTheRemovedOptionAndIgnoresLaterArguments() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--help", "--mailer:withProxyBridgePort=0");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).contains("Usage", "--mailer:withProxy")
                    .doesNotContain("--mailer:withProxyBridgePort");
            assertThat(result.stderr()).isEmpty();
            verifyNoInteractions(provider);
        }
    }

    @Test
    void anOptionShapedSubjectDoesNotReplaceAnUnrelatedParserError() {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:withSubject", "--mailer:withProxyBridgePort=0",
                    "--some-mistyped-option");
            assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
            assertThat(result.stderr()).contains("--some-mistyped-option").doesNotContain("withProxyBridgePort was removed");
            verifyNoInteractions(provider);
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
