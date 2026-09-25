package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliLegacySmtpContentSupportTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generatedOptionReachesTheMailerAndOverridesLoadedConfiguration(final boolean enabled) {
        final AtomicReference<Boolean> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer.getOperationalConfig().isLegacySmtpContentSupportEnabled());
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.legacycontentsupport", !enabled)).load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "receiver@example.test", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withLegacySmtpContentSupport", Boolean.toString(enabled));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get()).isEqualTo(enabled);
            assertThat(result.stdout()).isEmpty();
        }
    }

    @Test
    void generatedHelpExplainsTheDeploymentSpecificOptIn() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            final CliExecutionResult help = execute(environment, "send", "--help");
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("--mailer:withLegacySmtpContentSupport");
            final CliExecutionResult optionHelp = execute(environment, "send", "--mailer:withLegacySmtpContentSupport--help");
            assertThat(optionHelp.exitCode()).isZero();
            assertThat(optionHelp.stdout()).contains("SMTPUTF8", "8BITMIME", "delivery route", "José <jose@example.org>", "josé@example.org");
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
