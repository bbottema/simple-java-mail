package org.simplejavamail.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.AdditionalAnswers.delegatesTo;

@Timeout(20)
class OtelActivationTest {
    @Test
    void propertiesEnableEveryFactoryMailerWithoutMutatingAnotherFactoryOrTheSdk() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.telemetry.enabled", "true");
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.smtp.host", "localhost"))
                .withProperties("telemetry activation", properties)
                .load());
        properties.setProperty("simplejavamail.telemetry.enabled", "false");
        final SimpleJavaMail other = factory(Map.of());
        try (TelemetryTestSdk sdk = new TelemetryTestSdk(); MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::get).thenReturn(sdk.sdk);
            try (Mailer first = configured.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
                 Mailer second = configured.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
                 Mailer disabled = other.mailerBuilder().buildMailer()) {
                first.sync().sendMail(email(configured));
                second.async().sendMail(email(configured)).getCompletion().get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(first.getOperationalConfig().isMailSendTelemetryEnabled()).isTrue();
                assertThat(second.getOperationalConfig().isMailSendTelemetryEnabled()).isTrue();
                assertThat(disabled.getOperationalConfig().isMailSendTelemetryEnabled()).isFalse();
                assertThat(sdk.sends()).hasSize(2);
                global.verify(GlobalOpenTelemetry::get, times(2));
            }
            final var stillUsable = sdk.sdk.getTracer("application").spanBuilder("after closing Mailers").startSpan();
            stillUsable.end();
            assertThat(sdk.namedSpans("after closing Mailers")).hasSize(1);
        }
    }

    @Test
    void explicitDisableAndResetDoNotLoadOrBindTheModule() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("simplejavamail.telemetry.enabled", true));
        try (MockedStatic<ModuleLoader> loader = mockStatic(ModuleLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class);
             Mailer disabled = mail.mailerBuilder().withMailSendTelemetryEnabled(false).buildMailer();
             Mailer reset = mail.mailerBuilder().resetMailSendTelemetryEnabled().buildMailer()) {
            assertThat(disabled.getOperationalConfig().isMailSendTelemetryEnabled()).isFalse();
            assertThat(reset.getOperationalConfig().isMailSendTelemetryEnabled()).isEqualTo(MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_ENABLED);
            loader.verify(ModuleLoader::loadOpenTelemetryModule, org.mockito.Mockito.never());
            global.verifyNoInteractions();
        }
    }

    @Test
    void javaConfigurationEnablesTelemetryAndClearReturnsToTheCapturedActivation() throws Exception {
        final SimpleJavaMail enabled = factory(Map.of("simplejavamail.telemetry.enabled", true));
        final SimpleJavaMail disabled = factory(Map.of("simplejavamail.telemetry.enabled", false));
        try (TelemetryTestSdk selected = new TelemetryTestSdk(); TelemetryTestSdk inherited = new TelemetryTestSdk();
             MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::get).thenReturn(inherited.sdk);
            final MailSendTelemetry registration = OtelAdapter.wrap(selected.sdk);
            try (Mailer direct = disabled.mailerBuilder().withMailSendTelemetry(registration).withTransportModeLoggingOnly(true).buildMailer();
                 Mailer cleared = enabled.mailerBuilder().withMailSendTelemetry(registration).clearMailSendTelemetry()
                         .withTransportModeLoggingOnly(true).buildMailer();
                 Mailer stopped = disabled.mailerBuilder().withMailSendTelemetry(registration).withMailSendTelemetryEnabled(false).buildMailer()) {
                direct.sync().sendMail(email(disabled));
                cleared.sync().sendMail(email(enabled));
                assertThat(direct.getOperationalConfig().isMailSendTelemetryEnabled()).isTrue();
                assertThat(stopped.getOperationalConfig().isMailSendTelemetryEnabled()).isFalse();
                assertThat(selected.sends()).hasSize(1);
                assertThat(inherited.sends()).hasSize(1);
                global.verify(GlobalOpenTelemetry::get, times(1));
            }
            final var builder = disabled.mailerBuilder().withMailSendTelemetry(registration);
            assertThat(builder.clearMailSendTelemetry().isMailSendTelemetryEnabled()).isFalse();
        }
    }

    @Test
    void activationLocksApplyToJavaPropertiesClearingAndReset() throws Exception {
        final SimpleJavaMail disabled = factory(Map.of("simplejavamail.locked.telemetry.enabled", false));
        assertThatIllegalArgumentException().isThrownBy(() -> disabled.mailerBuilder().withMailSendTelemetryEnabled(true).buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        assertThatIllegalArgumentException().isThrownBy(() -> disabled.mailerBuilder()
                .withMailSendTelemetry(OtelAdapter.wrap(OpenTelemetry.noop())).buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        final SimpleJavaMail enabled = factory(Map.of("simplejavamail.telemetry.enabled", false,
                "simplejavamail.locked.telemetry.enabled", true));
        assertThatIllegalArgumentException().isThrownBy(() -> enabled.mailerBuilder().resetMailSendTelemetryEnabled().buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        try (MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::get).thenReturn(OpenTelemetry.noop());
            try (Mailer inherited = enabled.mailerBuilder().clearMailSendTelemetry().buildMailer();
                 Mailer equal = enabled.mailerBuilder().withMailSendTelemetryEnabled(true).buildMailer()) {
                assertThat(inherited.getOperationalConfig().isMailSendTelemetryEnabled()).isTrue();
                assertThat(equal.getOperationalConfig().isMailSendTelemetryEnabled()).isTrue();
            }
        }
    }

    @Test
    void customOpenTelemetryImplementationsNeedNoSjmLifecycleContract() throws Exception {
        final SimpleJavaMail mail = factory(Map.of());
        try (TelemetryTestSdk sdk = new TelemetryTestSdk(); MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            final OpenTelemetry supplied = mock(OpenTelemetry.class, delegatesTo(sdk.sdk));
            final MailSendTelemetry configuration = OtelAdapter.wrap(supplied);
            try (Mailer mailer = mail.mailerBuilder().withMailSendTelemetry(configuration).withTransportModeLoggingOnly(true).buildMailer()) {
                mailer.sync().sendMail(email(mail));
                assertThat(sdk.sends()).hasSize(1);
                global.verifyNoInteractions();
            }
            assertThat(ModuleLoader.loadOpenTelemetryModule()).isSameAs(ModuleLoader.loadOpenTelemetryModule());
            assertThat(ModuleLoader.loadOpenTelemetryModule().getClass().getDeclaredFields()).isEmpty();
        }
    }

    private static SimpleJavaMail factory(final Map<String, ?> values) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.smtp.host", "localhost"))
                .withMap(values)
                .load());
    }

    private static org.simplejavamail.api.email.Email email(final SimpleJavaMail mail) {
        return mail.emailBuilder().startingBlank().from("sender@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("receiver@example.test").withType(jakarta.mail.Message.RecipientType.TO).build())
                .buildEmail();
    }
}
