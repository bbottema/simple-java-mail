package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CliSmtpsOAuth2Test {
    @Test
    void generatedHelpIncludesTheAdditionalPreset() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult help = execute(environment, "send", "--mailer:withTransportStrategy--help");
            assertThat(help.exitCode()).as(help.stderr()).isZero();
            assertThat(help.stdout()).contains("SMTP_OAUTH2", "SMTPS_OAUTH2");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothCliAndLoadedConfigurationBuildTheImplicitTlsOauthMailer(final boolean fromProperties) {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        final Map<String, Object> properties = new java.util.LinkedHashMap<>(Map.of(
                ConfigLoader.Property.TRANSPORT_MODE_LOGGING_ONLY.key(), true,
                "simplejavamail.smtp.host", "smtp.example.test", "simplejavamail.smtp.username", "user@example.test",
                "simplejavamail.smtp.password", "synthetic-cli-token"));
        if (fromProperties) properties.put(ConfigLoader.Property.TRANSPORT_STRATEGY.key(), "SMTPS_OAUTH2");
        final List<String> arguments = new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "recipient@example.test", "--email:withPlainText", "Synthetic logging-only fixture"));
        if (!fromProperties) arguments.addAll(List.of("--mailer:withTransportStrategy", "SMTPS_OAUTH2"));
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(properties).load(), provider)) {
            final CliExecutionResult result = execute(environment, arguments.toArray(String[]::new));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer.getTransportStrategy()).isSameAs(TransportStrategy.SMTPS_OAUTH2);
            assertThat(mailer.getServerConfig().getPort()).isEqualTo(465);
            assertThat(mailer.getSession().getProperties()).containsEntry("mail.transport.protocol", "smtps")
                    .containsEntry("mail.smtps.auth.mechanisms", "XOAUTH2")
                    .containsEntry(TransportStrategy.OAUTH2_TOKEN_PROPERTY, "synthetic-cli-token")
                    .doesNotContainKey("mail.smtp.auth.mechanisms");
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
