package org.simplejavamail.api.mailer.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendTelemetry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class MailerConfigurationValuesTest {
    @Test
    void supplyingAndClearingTelemetryNeverChangesActivationOrTheOriginalSnapshot() {
        final MailSendTelemetry integration = new MailSendTelemetry() { };
        final MailSendTelemetryConfig original = MailSendTelemetryConfig.builder().withEnabled(false)
                .withIntegration(integration).withSmtpEndpointAttributes(true).build();
        final MailSendTelemetryConfig cleared = original.toBuilder().clearIntegration().build();
        assertThat(cleared.getEnabled()).isFalse();
        assertThat(cleared.getIntegration()).isNull();
        assertThat(cleared.getSmtpEndpointAttributes()).isTrue();
        assertThat(original.getIntegration()).isSameAs(integration);
        assertThat(MailSendTelemetryConfig.builder().withIntegration(integration).build().getEnabled()).isNull();
    }

    @Test
    void resetAndUseConfiguredDistinguishLibraryDefaultsFromInheritance() {
        final MailSendTelemetryConfig.Builder telemetry = MailSendTelemetryConfig.builder().withEnabled(true).withSmtpEndpointAttributes(true);
        assertThat(telemetry.resetEnabled().getEnabled()).isFalse();
        assertThat(telemetry.resetSmtpEndpointAttributes().getSmtpEndpointAttributes()).isFalse();
        assertThat(telemetry.useConfiguredEnabled().getEnabled()).isNull();
        assertThat(telemetry.useConfiguredSmtpEndpointAttributes().getSmtpEndpointAttributes()).isNull();
        assertThat(SessionDebugConfig.builder().withEnabled(true).resetEnabled().getEnabled()).isFalse();
        assertThat(SessionDebugConfig.builder().withEnabled(true).useConfiguredEnabled().getEnabled()).isNull();
    }

    @Test
    void debugOutputAndBorrowedPrinterAreExclusiveChoices() {
        final PrintStream printer = new PrintStream(new ByteArrayOutputStream());
        final SessionDebugConfig original = SessionDebugConfig.builder().withEnabled(true).withPrinter(printer).build();
        final SessionDebugConfig changed = original.toBuilder().withOutput(SessionDebugOutput.STDERR).build();
        assertThat(original.getPrinter()).isSameAs(printer);
        assertThat(original.getOutput()).isNull();
        assertThat(changed.getPrinter()).isNull();
        assertThat(changed.getOutput()).isEqualTo(SessionDebugOutput.STDERR);
        assertThat(changed.getEnabled()).isTrue();
        assertThat(changed.toBuilder().clearPrinter().getOutput()).isEqualTo(SessionDebugOutput.STDERR);
        assertThat(changed.toBuilder().resetOutput().getOutput()).isEqualTo(SessionDebugOutput.STDOUT);
        assertThat(original.toBuilder().useConfiguredOutput().getPrinter()).isNull();
        assertThat(original.toBuilder().useConfiguredOutput().getOutput()).isNull();
    }
}
