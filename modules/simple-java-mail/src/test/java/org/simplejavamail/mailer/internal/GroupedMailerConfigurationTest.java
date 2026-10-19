package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class GroupedMailerConfigurationTest {
    @Test
    void replacingDebugGroupRestoresCapturedFieldsRatherThanKeepingEarlierChoices() throws Exception {
        final PrintStream printer = new PrintStream(new ByteArrayOutputStream());
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.javaxmail.debug", true,
                "simplejavamail.javaxmail.debug.out", "STDERR")).mailerBuilder()
                .withDebugConfig(SessionDebugConfig.builder().withEnabled(false).withPrinter(printer).build());
        try (Mailer original = builder.buildMailer();
             Mailer replacement = builder.withDebugConfig(SessionDebugConfig.builder().withOutput(SessionDebugOutput.STDOUT).build()).buildMailer()) {
            assertThat(original.getSession().getDebug()).isFalse();
            assertThat(original.getSession().getDebugOut()).isSameAs(printer);
            assertThat(builder.getDebugConfig().getEnabled()).isNull();
            assertThat(replacement.getOperationalConfig().getDebugConfig().getEnabled()).isTrue();
            assertThat(replacement.getSession().getDebugOut()).isSameAs(System.out);
        }
        assertThat(builder.useConfiguredDebugConfig().getDebugConfig().getOutput()).isNull();
        try (Mailer inherited = builder.buildMailer()) {
            assertThat(inherited.getSession().getDebugOut()).isSameAs(System.err);
        }
    }

    @Test
    void absencePreservesCallerSessionFlagAndBorrowedPrinter() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final PrintStream printer = new PrintStream(new ByteArrayOutputStream());
        session.setDebug(true);
        session.setDebugOut(printer);
        try (Mailer mailer = factory(Map.of()).mailerBuilder(session).buildMailer()) {
            assertThat(session.getDebug()).isTrue();
            assertThat(session.getDebugOut()).isSameAs(printer);
            assertThat(mailer.getOperationalConfig().getDebugConfig().getEnabled()).isTrue();
            assertThat(mailer.getOperationalConfig().getDebugConfig().getPrinter()).isSameAs(printer);
        }
    }

    @Test
    void absentDebugChoicesDoNotMutateTheSuppliedSessionsOwnDebugPolicy() throws Exception {
        final Session session = spy(Session.getInstance(new Properties()));
        try (Mailer ignored = factory(Map.of()).mailerBuilder(session).buildMailer()) {
            verify(session, never()).setDebug(anyBoolean());
            verify(session, never()).setDebugOut(any(PrintStream.class));
        }
    }

    @Test
    void anIntegrationDoesNotEnableTelemetryAndReplacingItRestoresCapturedPermission() throws Exception {
        final MailSendTelemetry integration = new MailSendTelemetry() { };
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.telemetry.smtp.endpointattributes", true)).mailerBuilder()
                .withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(integration).withSmtpEndpointAttributes(false).build());
        try (Mailer original = builder.buildMailer();
             Mailer replaced = builder.withTelemetryConfig(MailSendTelemetryConfig.builder().build()).buildMailer()) {
            assertThat(original.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            assertThat(original.getOperationalConfig().getTelemetryConfig().getIntegration()).isSameAs(integration);
            assertThat(original.getOperationalConfig().getTelemetryConfig().getSmtpEndpointAttributes()).isFalse();
            assertThat(replaced.getOperationalConfig().getTelemetryConfig().getIntegration()).isNull();
            assertThat(replaced.getOperationalConfig().getTelemetryConfig().getSmtpEndpointAttributes()).isTrue();
            assertThat(builder.getTelemetryConfig().getSmtpEndpointAttributes()).isNull();
        }
    }

    @Test
    void groupedChoicesStillEnforceCapturedLocks() {
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.locked.javaxmail.debug", true)).mailerBuilder()
                .withDebugConfig(SessionDebugConfig.builder().withEnabled(false).build()).buildMailer())
                .hasMessageContaining("locked.javaxmail.debug");
        assertThatThrownBy(() -> factory(Map.of("simplejavamail.locked.telemetry.enabled", false)).mailerBuilder()
                .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(true).build()).buildMailer())
                .hasMessageContaining("locked.telemetry.enabled");
    }

    @Test
    void withoutDefaultsAndUseConfiguredDefaultsHaveDifferentEffects() throws Exception {
        final MailerRegularBuilder<?> builder = factory(Map.of("simplejavamail.defaults.subject", "configured subject")).mailerBuilder();
        try (Mailer without = builder.withoutEmailDefaults().buildMailer();
             Mailer inherited = builder.useConfiguredEmailDefaults().buildMailer()) {
            assertThat(without.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject()).isNull();
            assertThat(inherited.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject()).isEqualTo("configured subject");
        }
    }

    @Test
    void suppressingOrdinaryDefaultsDoesNotRemoveFactoryLocks() throws Exception {
        try (Mailer mailer = factory(Map.of("simplejavamail.locked.defaults.subject", "locked subject"))
                .mailerBuilder().withoutEmailDefaults().buildMailer()) {
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getSubject())
                    .isEqualTo("locked subject");
        }
    }

    private static SimpleJavaMail factory(final Map<String, ?> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.host", "localhost"))
                .withMap(properties).load());
    }
}
