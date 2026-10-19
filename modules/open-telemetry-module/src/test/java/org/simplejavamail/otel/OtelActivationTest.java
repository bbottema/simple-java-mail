package org.simplejavamail.otel;

import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
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
                assertThat(first.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                assertThat(second.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                assertThat(disabled.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
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
             Mailer disabled = mail.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(false).build()).buildMailer();
             Mailer reset = mail.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().resetEnabled().build()).buildMailer()) {
            assertThat(disabled.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            assertThat(reset.getOperationalConfig().getTelemetryConfig().getEnabled()).isEqualTo(MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_ENABLED);
            loader.verify(ModuleLoader::loadOpenTelemetryModule, org.mockito.Mockito.never());
            global.verifyNoInteractions();
        }
    }

    @Test
    void explicitActivationAndIntegrationClearingRemainIndependent() throws Exception {
        final SimpleJavaMail enabled = factory(Map.of("simplejavamail.telemetry.enabled", true));
        final SimpleJavaMail disabled = factory(Map.of("simplejavamail.telemetry.enabled", false));
        try (TelemetryTestSdk selected = new TelemetryTestSdk(); TelemetryTestSdk inherited = new TelemetryTestSdk();
             MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::get).thenReturn(inherited.sdk);
            final MailSendTelemetry registration = OtelAdapter.wrap(selected.sdk);
            try (Mailer direct = disabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(registration).withEnabled(true).build()).withTransportModeLoggingOnly(true).buildMailer();
                 Mailer cleared = enabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(registration).withEnabled(true).clearIntegration().build())
                         .withTransportModeLoggingOnly(true).buildMailer();
                 Mailer stopped = disabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(registration).withEnabled(true).withEnabled(false).build()).buildMailer()) {
                direct.sync().sendMail(email(disabled));
                cleared.sync().sendMail(email(enabled));
                assertThat(direct.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                assertThat(stopped.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
                assertThat(selected.sends()).hasSize(1);
                assertThat(inherited.sends()).hasSize(1);
                global.verify(GlobalOpenTelemetry::get, times(1));
            }
            final var builder = disabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(registration).withEnabled(true).build());
            builder.withTelemetryConfig(builder.getTelemetryConfig().toBuilder().clearIntegration().build());
            assertThat(builder.getTelemetryConfig().getEnabled()).isTrue();
            assertThat(builder.getTelemetryConfig().getIntegration()).isNull();
            assertThat(builder.useConfiguredTelemetryConfig().getTelemetryConfig().getEnabled()).isNull();
        }
    }

    @Test
    void activationLocksApplyToJavaPropertiesClearingAndReset() throws Exception {
        final SimpleJavaMail disabled = factory(Map.of("simplejavamail.locked.telemetry.enabled", false));
        assertThatIllegalArgumentException().isThrownBy(() -> disabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(true).build()).buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        assertThatIllegalArgumentException().isThrownBy(() -> disabled.mailerBuilder()
                .withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(OtelAdapter.wrap(OpenTelemetry.noop())).withEnabled(true).build()).buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        final SimpleJavaMail enabled = factory(Map.of("simplejavamail.telemetry.enabled", false,
                "simplejavamail.locked.telemetry.enabled", true));
        assertThatIllegalArgumentException().isThrownBy(() -> enabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().resetEnabled().build()).buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.enabled");
        try (MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            global.when(GlobalOpenTelemetry::get).thenReturn(OpenTelemetry.noop());
            try (Mailer inherited = enabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().clearIntegration().build()).buildMailer();
                 Mailer equal = enabled.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(true).build()).buildMailer()) {
                assertThat(inherited.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                assertThat(equal.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            }
        }
    }

    @Test
    void customOpenTelemetryImplementationsNeedNoSjmLifecycleContract() throws Exception {
        final SimpleJavaMail mail = factory(Map.of());
        try (TelemetryTestSdk sdk = new TelemetryTestSdk(); MockedStatic<GlobalOpenTelemetry> global = mockStatic(GlobalOpenTelemetry.class)) {
            final OpenTelemetry supplied = mock(OpenTelemetry.class, delegatesTo(sdk.sdk));
            final MailSendTelemetry configuration = OtelAdapter.wrap(supplied);
            try (Mailer mailer = mail.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withIntegration(configuration).withEnabled(true).build()).withTransportModeLoggingOnly(true).buildMailer()) {
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
