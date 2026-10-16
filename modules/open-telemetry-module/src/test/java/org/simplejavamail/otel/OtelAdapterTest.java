package org.simplejavamail.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.telemetry.otel.OtelTelemetry;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class OtelAdapterTest {
    @TempDir Path output;

    @Test
    void acceptedInvocationRemainsAcceptedWhenTheLogicalSendFailsDuringCleanup() {
        try (TelemetryTestSdk sdk = new TelemetryTestSdk()) {
            final var runtime = ModuleLoader.loadOpenTelemetryModule().bind(OtelAdapter.wrap(sdk.sdk), true);
            final var send = runtime.captureContext().beginSend(UUID.randomUUID());
            final var child = send.beginSubmission(false);
            child.complete(true, MailSubmissionStatus.ACCEPTED, null, "relay\\n.example.test", 587);
            send.complete(MailSendTelemetryReport.builder()
                    .successful(false)
                    .submissionStatus(MailSubmissionStatus.ACCEPTED)
                    .failure(new IllegalStateException("private cleanup detail"))
                    .elapsed(Duration.ofMillis(50))
                    .build());
            assertThat(sdk.submissions().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(sdk.sends().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(sdk.sends().get(0).getAttributes().get(AttributeKey.stringKey("simplejavamail.submission.status")))
                    .isEqualTo("accepted");
            assertThat(sdk.sends().get(0).getEvents()).isEmpty();
            assertThat(sdk.sum("simplejavamail.mail.send.active")).isZero();
        }
    }

    @Test
    void wrappingRetainsTheInstanceWithoutStartingRuntimeWorkOrExposingIt() {
        final OpenTelemetry supplied = mock(OpenTelemetry.class);
        try (var global = mockStatic(GlobalOpenTelemetry.class); var loader = mockStatic(ModuleLoader.class)) {
            final MailSendTelemetry handle = OtelAdapter.wrap(supplied);
            assertThat(((OtelTelemetry) handle).getOpenTelemetry()).isSameAs(supplied);
            assertThat(handle.toString()).isEqualTo("OtelTelemetry()");
            verifyNoInteractions(supplied);
            global.verifyNoInteractions();
            loader.verifyNoInteractions();
        }
        assertThatNullPointerException().isThrownBy(() -> OtelAdapter.wrap(null)).withMessage("openTelemetry");
    }

    @Test
    void thePublicAdapterAcceptsOnlyTheForeignTypeAndReturnsOnlyTheCoreContract() throws Exception {
        assertThat(OtelAdapter.class.getConstructors()).isEmpty();
        assertThat(OtelAdapter.class.getMethod("wrap", OpenTelemetry.class).getReturnType()).isEqualTo(MailSendTelemetry.class);
        assertThat(compiles("org.simplejavamail.otel.OtelAdapter.wrap(io.opentelemetry.api.OpenTelemetry.noop())")).isTrue();
        assertThat(compiles("org.simplejavamail.otel.OtelAdapter.wrap(new Object())")).isFalse();
        assertThat(compiles("org.simplejavamail.otel.OtelAdapter.wrap(\"not an OpenTelemetry instance\")")).isFalse();
        assertThat(compiles("org.simplejavamail.otel.OtelAdapter.wrap(io.opentelemetry.api.OpenTelemetry.noop()).getOpenTelemetry()"))
                .isFalse();
    }

    @Test
    void anUnrecognizedHandleDoesNotSilentlySelectTheGlobalInstance() {
        try (var global = mockStatic(GlobalOpenTelemetry.class)) {
            assertThatIllegalArgumentException().isThrownBy(() -> ModuleLoader.loadOpenTelemetryModule().bind(new MailSendTelemetry() { }, false))
                    .withMessageContaining("OtelAdapter.wrap(openTelemetry)");
            global.verifyNoInteractions();
        }
    }

    @Test
    void twoFactoriesBindOneRegistrationWithoutCrossTalkAndRetainProvenance() throws Exception {
        final MailSendTelemetry shared = OtelAdapter.wrap(OpenTelemetry.noop());
        final Properties source = properties("simplejavamail.telemetry.smtp.endpointattributes", "true");
        final SimpleJavaMail configured = factory(source);
        source.setProperty("simplejavamail.telemetry.smtp.endpointattributes", "false");
        try (Mailer enabled = configured.mailerBuilder().withMailSendTelemetry(shared).buildMailer();
             Mailer disabled = factory(new Properties()).mailerBuilder().withMailSendTelemetry(shared).buildMailer();
             Mailer explicitlyDisabled = configured.mailerBuilder().withMailSendTelemetry(shared)
                     .withMailSendTelemetrySmtpEndpointAttributes(false).buildMailer();
             Mailer cleared = configured.mailerBuilder()
                     .withMailSendTelemetry(shared).withMailSendTelemetrySmtpEndpointAttributes(false)
                     .clearMailSendTelemetrySmtpEndpointAttributes().buildMailer()) {
            assertThat(enabled.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
            assertThat(disabled.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isFalse();
            assertThat(explicitlyDisabled.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isFalse();
            assertThat(cleared.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
            assertThat(((OtelTelemetry) shared).getOpenTelemetry()).isSameAs(OpenTelemetry.noop());
        }
        assertThat(configured.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.DIAGNOSTICS_AND_VALIDATION)).anySatisfy(entry -> {
            assertThat(entry.getPropertyName()).isEqualTo("simplejavamail.telemetry.smtp.endpointattributes");
            assertThat(entry.getSourceName()).isEqualTo("telemetry test");
            assertThat(entry.isRedacted()).isFalse();
        });
    }

    @Test
    void resetIsExplicitButClearInheritsAndLocksDoNotRequireActivation() throws Exception {
        final SimpleJavaMail mail = factory(properties("simplejavamail.locked.telemetry.smtp.endpointattributes", "true"));
        final MailSendTelemetry registration = OtelAdapter.wrap(OpenTelemetry.noop());
        try (Mailer inactive = mail.mailerBuilder().buildMailer();
             Mailer equal = mail.mailerBuilder().withMailSendTelemetry(registration).withMailSendTelemetrySmtpEndpointAttributes(true).buildMailer();
             Mailer cleared = mail.mailerBuilder().withMailSendTelemetry(registration).clearMailSendTelemetry().buildMailer()) {
            assertThat(inactive.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
            assertThat(equal.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
            assertThat(cleared.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> mail.mailerBuilder()
                .withMailSendTelemetry(registration).resetMailSendTelemetrySmtpEndpointAttributes().buildMailer())
                .withMessageContaining("simplejavamail.locked.telemetry.smtp.endpointattributes");
        final var builder = mail.mailerBuilder().withMailSendTelemetry(registration);
        assertThat(builder.getMailSendTelemetry()).isSameAs(registration);
        assertThat(builder.clearMailSendTelemetry().getMailSendTelemetry()).isNull();
    }

    @Test
    void replacingOrClearingTheHandlePreservesTheMailersEndpointChoice() throws Exception {
        final var builder = factory(properties("simplejavamail.telemetry.smtp.endpointattributes", "true")).mailerBuilder()
                .withMailSendTelemetrySmtpEndpointAttributes(false);
        assertThat(builder.isMailSendTelemetrySmtpEndpointAttributesEnabled()).isFalse();
        try (Mailer mailer = builder.withMailSendTelemetry(OtelAdapter.wrap(OpenTelemetry.noop()))
                .clearMailSendTelemetry().withMailSendTelemetry(OtelAdapter.wrap(OpenTelemetry.noop())).buildMailer()) {
            assertThat(mailer.getOperationalConfig().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isFalse();
        }
        assertThat(builder.clearMailSendTelemetrySmtpEndpointAttributes().isMailSendTelemetrySmtpEndpointAttributesEnabled()).isTrue();
        assertThat(builder.resetMailSendTelemetrySmtpEndpointAttributes().isMailSendTelemetrySmtpEndpointAttributesEnabled())
                .isEqualTo(MailerGenericBuilder.DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES);
    }

    private boolean compiles(final String expression) throws Exception {
        final Path source = output.resolve("AdapterConsumer.java");
        Files.writeString(source, "class AdapterConsumer { Object handle = " + expression + "; }");
        final var compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            return compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, files.getJavaFileObjects(source)).call();
        }
    }

    static SimpleJavaMail factory(final Properties source) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.smtp.host", "localhost"))
                .withProperties("telemetry test", source)
                .load());
    }

    private static Properties properties(final String key, final String value) {
        final Properties source = new Properties();
        source.setProperty(key, value);
        return source;
    }
}
