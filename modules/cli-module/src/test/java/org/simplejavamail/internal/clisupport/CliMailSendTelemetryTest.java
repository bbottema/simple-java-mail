package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliMailSendTelemetryTest {
    @Test
    void endpointPermissionAloneDoesNotActivateTelemetry() {
        for (boolean permission : new boolean[] { false, true }) {
            final AtomicReference<Mailer> selected = new AtomicReference<>();
            final OneShotMailerProvider delegate = new OneShotMailerProvider();
            final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
                final Mailer mailer = factory.get();
                selected.set(mailer);
                return mailer;
            });
            try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
                final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                        "--email:to", "receiver@example.org", "--mailer:withTransportModeLoggingOnly", "true",
                        "--mailer:withMailSendTelemetrySmtpEndpointAttributes", Boolean.toString(permission));
                assertThat(result.exitCode()).as(result.stderr()).isZero();
                assertThat(selected.get().getOperationalConfig().getTelemetryConfig().getSmtpEndpointAttributes()).isEqualTo(permission);
                assertThat(selected.get().getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            }
        }
    }

    @Test
    void activationWorksThroughBothTheGeneratedOptionAndCapturedProperties() {
        for (boolean useProperty : new boolean[] {false, true}) {
            final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.telemetry.enabled", useProperty)).load();
            try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, new OneShotMailerProvider())) {
                final List<String> arguments = new java.util.ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.org",
                        "--email:to", "receiver@example.org", "--mailer:withTransportModeLoggingOnly", "true"));
                if (!useProperty) {
                    arguments.addAll(List.of("--mailer:withMailSendTelemetryEnabled", "true"));
                }
                final CliExecutionResult result = execute(environment, arguments.toArray(new String[0]));
                assertThat(result.exitCode()).as(result.stderr()).isZero();
            }
        }
    }

    @Test
    void helpExplainsPermissionNotActivationAndLockedPermissionRejectsConflicts() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.telemetry.smtp.endpointattributes", false)).load(), new OneShotMailerProvider())) {
            final CliExecutionResult help = execute(environment, "send", "--mailer:withMailSendTelemetrySmtpEndpointAttributes--help");
            assertThat(help.exitCode()).as(help.stderr()).isZero();
            assertThat(help.stdout()).contains("does not install an SDK", "metric labels");
            final CliExecutionResult rejected = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.org",
                    "--email:to", "receiver@example.org", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:withMailSendTelemetrySmtpEndpointAttributes", "true");
            assertThat(rejected.exitCode()).isNotZero();
            assertThat(rejected.stderr()).contains("simplejavamail.locked.telemetry.smtp.endpointattributes");
        }
    }

    @Test
    void scalarAndLockChoicesSeparateDaemonProfiles() {
        final byte[] key = new byte[32];
        final var ordinary = ConfigLoader.builder().withMap(Map.of("simplejavamail.telemetry.smtp.endpointattributes", false)).load();
        final var enabled = ConfigLoader.builder().withMap(Map.of("simplejavamail.telemetry.smtp.endpointattributes", true)).load();
        final var locked = ConfigLoader.builder().withMap(Map.of("simplejavamail.locked.telemetry.smtp.endpointattributes", false)).load();
        assertThat(CliMailerProfile.create(ordinary, List.of(), key)).isNotEqualTo(CliMailerProfile.create(enabled, List.of(), key))
                .isNotEqualTo(CliMailerProfile.create(locked, List.of(), key));
        final var activated = ConfigLoader.builder().withMap(Map.of("simplejavamail.telemetry.enabled", true)).load();
        final var activationLocked = ConfigLoader.builder().withMap(Map.of("simplejavamail.locked.telemetry.enabled", true)).load();
        assertThat(CliMailerProfile.create(ordinary, List.of(), key)).isNotEqualTo(CliMailerProfile.create(activated, List.of(), key));
        assertThat(CliMailerProfile.create(activated, List.of(), key)).isNotEqualTo(CliMailerProfile.create(activationLocked, List.of(), key));
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
