package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

class MailSendTelemetryActivationTest {
    @Test
    void requestingAMissingModuleFailsBeforeCreatingMailerResources() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", true)).load());
        final var failure = new IllegalStateException("OpenTelemetry module unavailable: add open-telemetry-module");
        try (var modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::loadOpenTelemetryModule).thenThrow(failure);
            assertThatThrownBy(() -> mail.mailerBuilder().buildMailer()).isSameAs(failure);
            try (Mailer disabled = mail.mailerBuilder().withMailSendTelemetryEnabled(false).buildMailer()) {
                assertThat(disabled.getOperationalConfig().isMailSendTelemetryEnabled()).isFalse();
            }
        }
    }

    @Test
    void disabledTelemetryDoesNotDiscoverItsModuleAndActivationHasAPropertyDiagnostic() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", false)).load());
        try (var modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS); Mailer mailer = mail.mailerBuilder().buildMailer()) {
            assertThat(mailer.getOperationalConfig().isMailSendTelemetryEnabled()).isFalse();
            modules.verify(ModuleLoader::loadOpenTelemetryModule, never());
        }
        assertThat(mail.getConfig().getDiagnostics().getProperties(org.simplejavamail.config.ConfigDiagnosticGroup.DIAGNOSTICS_AND_VALIDATION))
                .anySatisfy(entry -> assertThat(entry.getPropertyName()).isEqualTo("simplejavamail.telemetry.enabled"));
    }

    @Test
    void unconfiguredMainModuleDoesNotLinkOpenTelemetryClasses() {
        assertThatThrownBy(() -> Class.forName("io.opentelemetry.api.OpenTelemetry")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(ModuleLoader::loadOpenTelemetryModule).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OpenTelemetry").hasMessageContaining("open-telemetry-module");
    }
}
