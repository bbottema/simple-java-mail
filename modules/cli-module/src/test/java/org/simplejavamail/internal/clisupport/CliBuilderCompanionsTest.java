package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.internal.clisupport.CliEmailConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.CliExactEmailConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.CliRegularMailerConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliBuilderCompanionsTest {

    private static final List<String> MAILER_OPTIONS = List.of("clearSMTPServerHost", "clearSMTPServerPort", "clearSMTPServerUsername",
            "clearSMTPServerPassword", "clearCustomSSLFactoryClass", "resetOpportunisticTLS", "resetLegacySmtpContentSupport");

    @Test
    void newScalarCompanionsHaveUniqueZeroArgumentOptionsAndReadableHelp() throws Exception {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        for (String methodName : MAILER_OPTIONS) {
            final Method method = options.stream().filter(option -> option.getName().equals("--mailer:" + methodName))
                    .findFirst().orElseThrow().getSourceMethod();
            assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(method).isCompatible()).as(methodName).isTrue();
            assertThat(BuilderApiToPicocliCommandsMapper.getArgumentsForCliOption(method)).isEmpty();
            assertThat(options).filteredOn(option -> option.getName().equals("--mailer:" + methodName)).singleElement()
                    .satisfies(option -> assertThat(option.getDescription()).anyMatch(description -> !description.isBlank()));
        }
        assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(CliRegularMailerConfigurationBuilder.class.getMethod("isOpportunisticTLS"))
                .isCompatible()).isFalse();
    }

    @Test
    void javaOnlyAndFreshBuilderCompanionsStayOutOfTheGeneratedOptions() throws Exception {
        for (String method : List.of("clearOAuth2AccessTokenProvider", "clearCustomMailer", "useConfiguredDebugConfig", "getDebugConfig",
                "useConfiguredTelemetryConfig", "getTelemetryConfig", "useConfiguredEmailDefaults")) {
            assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(MailerGenericBuilder.class.getMethod(method))
                    .isCompatible()).as(method).isFalse();
        }
        assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(
                CliRegularMailerConfigurationBuilder.class.getMethod("clearCustomSSLFactoryInstance")).isCompatible()).isFalse();
        for (Class<?> builder : List.of(CliEmailConfigurationBuilder.class, CliExactEmailConfigurationBuilder.class)) {
            for (String method : List.of("clearDeliveryStatusNotificationNotifyOptions", "clearDeliveryStatusNotificationReturnOption", "clearEnvelopeId")) {
                assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(builder.getMethod(method)).isCompatible())
                        .as(builder.getSimpleName() + "." + method).isFalse();
            }
        }
        for (String method : List.of("clearEnvelopeRecipients", "clearEnvelopeSender")) {
            assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(ExactEmailBuilder.class.getMethod(method)).isCompatible())
                    .as(method).isFalse();
        }
        assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(
                CliExactEmailConfigurationBuilder.class.getMethod("clearDeliveryStatusNotification")).isCompatible()).isFalse();
    }

    @Test
    void regeneratedGeneralAndIndividualHelpExposeTheCompanions() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            final CliExecutionResult help = execute(environment, "send", "--help");
            assertThat(help.exitCode()).as(help.stderr()).isZero();
            for (String method : MAILER_OPTIONS) {
                final String option = "--mailer:" + method;
                assertThat(help.stdout()).contains(option);
                final CliExecutionResult individual = execute(environment, "send", option + "--help");
                assertThat(individual.exitCode()).as(individual.stderr()).isZero();
                assertThat(individual.stdout()).contains(option);
            }
            assertThat(help.stdout()).doesNotContain("--mailer:clearCustomMailer", "--mailer:clearOAuth2AccessTokenProvider",
                    "--mailer:resetDebugLogging", "--mailer:clearDebugPrinter", "--mailer:clearDebugOutput", "--mailer:getDebugOutput",
                    "--mailer:useConfiguredDebugConfig", "--mailer:getDebugConfig", "--mailer:useConfiguredTelemetryConfig",
                    "--mailer:getTelemetryConfig", "--mailer:useConfiguredEmailDefaults",
                    "--mailer:clearCustomSSLFactoryInstance", "--mailer:getCustomSSLFactoryInstance",
                    "--email:clearEnvelopeRecipients", "--email:clearEnvelopeSender", "--email:clearEnvelopeId");
        }
    }

    @Test
    void generatedCompanionsClearCapturedSmtpFieldsAndResetOnlyTheirBooleanSettings() {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        final Map<String, Object> properties = Map.of("simplejavamail.smtp.host", "relay.example.org", "simplejavamail.smtp.port", 2525,
                "simplejavamail.smtp.username", "user", "simplejavamail.smtp.password", "secret",
                "simplejavamail.opportunistic.tls", false, "simplejavamail.smtp.legacycontentsupport", true,
                "simplejavamail.custom.sslfactory.class", "javax.net.ssl.SSLSocketFactory");
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().withMap(properties).load(), provider)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "receiver@example.test", "--mailer:withTransportModeLoggingOnly", "true",
                    "--mailer:clearSMTPServerHost", "--mailer:clearSMTPServerPort", "--mailer:clearSMTPServerUsername",
                    "--mailer:clearSMTPServerPassword", "--mailer:clearCustomSSLFactoryClass", "--mailer:resetOpportunisticTLS",
                    "--mailer:resetLegacySmtpContentSupport");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer).isNotNull();
            assertThat(mailer.getServerConfig()).isNull();
            assertThat(mailer.getSession().getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(mailer.getOperationalConfig().isLegacySmtpContentSupportEnabled()).isFalse();
            assertThat(mailer.getSession().getProperty("mail.smtp.ssl.socketFactory.class")).isNull();
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
