package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliMailSendRetryTest {
    @Test
    void generatedTypedOptionsConfigurePolicyAndLongMemoryValues() {
        final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
        final MailerProvider provider = captureSelectedConfiguration(selected);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withMailSendRetryAttempts", "3", "--mailer:withMailSendRetryDelay", "PT30S",
                    "--mailer:withMailSendRetryBackoff", "2", "PT5M", "--mailer:withMailSendRetryJitter", "0.2",
                    "--mailer:withMailSendRetryMaximumMessageSize", "33554432", "--mailer:withMailSendRetryMemoryLimit", "4294967296");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout()).isEmpty();
            assertThat(selected.get().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(selected.get().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
            assertThat(selected.get().getMailSendRetryPolicy().getRetryJitter()).isEqualTo(0.2);
            assertThat(selected.get().getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(4294967296L);
        }
    }

    @Test
    void helpExplainsRequiredScheduleAndIncompletePolicyFailsBeforeSending() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            final CliExecutionResult help = execute(environment, "send", "--mailer:withMailSendRetryAttempts--help");
            assertThat(help.exitCode()).as(help.stderr()).isZero();
            assertThat(help.stdout()).contains("attempts(=NUM)", "including the first", "delay");
            final CliExecutionResult invalid = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true", "--mailer:withMailSendRetryAttempts", "3");
            assertThat(invalid.exitCode()).isNotZero();
            assertThat(invalid.stderr()).contains("attempts", "delay");
        }
    }

    @Test
    void policyAndResourceSettingsSeparateDaemonProfiles() {
        final Map<String, Object> first = Map.of("simplejavamail.smtp.retry.attempts", 3, "simplejavamail.smtp.retry.delay", "PT30S");
        final Map<String, Object> second = Map.of("simplejavamail.smtp.retry.attempts", 4, "simplejavamail.smtp.retry.delay", "PT30S");
        final Map<String, Object> differentResources = Map.of("simplejavamail.smtp.retry.attempts", 3, "simplejavamail.smtp.retry.delay", "PT30S",
                "simplejavamail.defaults.mailsend.retry.memorylimit", 268435456L);
        final byte[] key = new byte[32];
        assertThat(CliMailerProfile.create(ConfigLoader.builder().withMap(first).load(), List.of(), key))
                .isNotEqualTo(CliMailerProfile.create(ConfigLoader.builder().withMap(second).load(), List.of(), key));
        assertThat(CliMailerProfile.create(ConfigLoader.builder().withMap(first).load(), List.of(), key))
                .isNotEqualTo(CliMailerProfile.create(ConfigLoader.builder().withMap(differentResources).load(), List.of(), key));
    }

    @Test
    void cliCanClearAnOrdinaryPropertyLoadedPolicy() {
        final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
        final MailerProvider provider = captureSelectedConfiguration(selected);
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.retry.attempts", 3,
                "simplejavamail.smtp.retry.delay", "PT30S")).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true", "--mailer:clearMailSendRetryPolicy");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
        }
    }

    @Test
    void resourceOptionsAfterClearingDoNotRestoreThePropertyLoadedSchedule() {
        final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
        final MailerProvider provider = captureSelectedConfiguration(selected);
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.retry.attempts", 3,
                "simplejavamail.smtp.retry.delay", "PT30S")).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true", "--mailer:clearMailSendRetryPolicy",
                    "--mailer:withMailSendRetryWaitingCapacity", "2", "--mailer:withMailSendRetryMaximumMessageSize", "1024",
                    "--mailer:withMailSendRetryMemoryLimit", "4096");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get().getMailSendRetryPolicy().isRetryEnabled()).isFalse();
            assertThat(selected.get().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(2);
            assertThat(selected.get().getMailSendRetryPolicy().getMaximumMessageSize()).isEqualTo(1024);
            assertThat(selected.get().getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(4096);
        }
    }

    @Test
    void resourceOptionInheritsThePropertyScheduleAndOtherResourceLimits() {
        final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.retry.attempts", 3,
                "simplejavamail.smtp.retry.delay", "PT30S", "simplejavamail.defaults.mailsend.retry.waitingcapacity", 7,
                "simplejavamail.defaults.mailsend.retry.maximummessagesize", 1024L)).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, captureSelectedConfiguration(selected))) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withMailSendRetryMemoryLimit", "4096");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(selected.get().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
            assertThat(selected.get().getMailSendRetryPolicy().getWaitingCapacity()).isEqualTo(7);
            assertThat(selected.get().getMailSendRetryPolicy().getMaximumMessageSize()).isEqualTo(1024);
            assertThat(selected.get().getMailSendRetryPolicy().getMemoryLimit()).isEqualTo(4096);
        }
    }

    @Test
    void cliDelayCompletesAPropertyConfiguredAttemptCount() {
        final AtomicReference<OperationalConfig> selected = new AtomicReference<>();
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.retry.attempts", 3)).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, captureSelectedConfiguration(selected))) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "recipient@example.org", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withMailSendRetryDelay", "PT30S");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get().getMailSendRetryPolicy().getMaximumAttempts()).isEqualTo(3);
            assertThat(selected.get().getMailSendRetryPolicy().getRetryDelay()).isEqualTo(Duration.ofSeconds(30));
        }
    }

    private static MailerProvider captureSelectedConfiguration(final AtomicReference<OperationalConfig> selected) {
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        return (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer.getOperationalConfig());
            return mailer;
        });
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
