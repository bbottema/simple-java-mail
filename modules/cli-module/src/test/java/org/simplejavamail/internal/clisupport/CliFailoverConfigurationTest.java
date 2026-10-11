package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliFailoverConfigurationTest {

    @Test
    void generatedNamedSelectionAndPolicyOptionsReachTheNormalBuilder() {
        final AtomicReference<FailoverPolicy> policy = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            policy.set(mailer.getOperationalConfig().getFailoverPolicy());
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.mailers.secondary.smtp.host", "secondary.example.test")).load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "receiver@example.test", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withFailoverMailerName", "secondary", "--mailer:withFailoverPrimaryConnectionAttempts", "2",
                    "--mailer:withFailoverPrimaryRetryDelay", "PT1S", "--mailer:withFailoverPrimaryRecheckInterval", "PT10S",
                    "--mailer:withFailoverPrimaryRecheckBackoff", "2", "PT1M", "--mailer:withFailoverOnAuthenticationFailure", "true");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(policy.get().getPrimaryConnectionAttempts()).isEqualTo(2);
            assertThat(policy.get().getPrimaryConnectionRetryDelay()).isEqualTo(Duration.ofSeconds(1));
            assertThat(policy.get().getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(policy.get().getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(policy.get().isFailoverOnAuthenticationFailure()).isTrue();
            assertThat(policy.get().isFailoverOnCertificateVerificationFailure()).isFalse();
            assertThat(result.stdout()).isEmpty();
        }
    }

    @Test
    void generatedPolicyOptionsCannotDisplaceLockedValues() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.failover.mailer", "secondary",
                "simplejavamail.locked.failover.primary.connectionattempts", "1",
                "simplejavamail.mailers.secondary.smtp.host", "secondary.example.test")).load(), new OneShotMailerProvider())) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "receiver@example.test", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withFailoverPrimaryConnectionAttempts", "2");
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("simplejavamail.locked.failover.primary.connectionattempts");
        }
    }

    @Test
    void generatedHelpExplainsTheConnectionOnlyBoundary() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            final CliExecutionResult help = execute(environment, "send", "--mailer:withFailoverMailerName--help");
            assertThat(help.exitCode()).isZero();
            assertThat(help.stdout()).contains("simplejavamail.mailers.secondary", "connection failure", "never to replay");
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
