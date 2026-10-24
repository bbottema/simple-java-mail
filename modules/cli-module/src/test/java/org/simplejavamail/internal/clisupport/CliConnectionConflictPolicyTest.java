package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliConnectionConflictPolicyTest {

    private static final String POLICY_PROPERTY = "simplejavamail.connection.conflictpolicy";
    private static final String POLICY_OPTION = "--mailer:withConnectionConflictPolicy";
    private static final String RESET_OPTION = "--mailer:resetConnectionConflictPolicy";

    @Test
    void policyUsesThePublicEnumOptionWhileInspectionAndRestorationRemainJavaOnly() throws Exception {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        assertThat(options).filteredOn(option -> option.getName().equals(POLICY_OPTION)).singleElement().satisfies(option -> {
            assertThat(option.getSourceMethod().getDeclaringClass()).isEqualTo(MailerGenericBuilder.class);
            assertThat(option.getSourceMethod().getParameterTypes()).containsExactly(ConnectionConflictPolicy.class);
            assertThat(option.getMandatoryOptionValues()).singleElement().satisfies(value ->
                    assertThat(value.getHelpLabel()).isEqualTo("NAME"));
            assertThat(option.getDescription()).anyMatch(description -> !description.isBlank());
        });
        assertThat(options).filteredOn(option -> option.getName().equals(RESET_OPTION)).singleElement().satisfies(option -> {
            assertThat(option.getSourceMethod().getParameterCount()).isZero();
            assertThat(option.getDescription()).anyMatch(description -> !description.isBlank());
        });
        for (final String method : List.of("getConnectionConflictPolicy", "useConfiguredConnectionConflictPolicy")) {
            assertThat(BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(MailerGenericBuilder.class.getMethod(method))
                    .isCompatible()).as(method).isFalse();
        }
    }

    @Test
    void generatedHelpExplainsTheAdvancedModesAndStrictReset() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            final CliExecutionResult root = execute(environment, "--help");
            assertThat(root.exitCode()).as(root.stderr()).isZero();
            assertThat(root.stdout().replaceAll("\\s+", " ")).contains("Conflicting explicit connection settings are rejected by default", POLICY_OPTION + "--help",
                    "locked settings remain enforced");

            final CliExecutionResult general = execute(environment, "send", "--help");
            assertThat(general.exitCode()).as(general.stderr()).isZero();
            assertThat(general.stdout()).contains(POLICY_OPTION, RESET_OPTION)
                    .doesNotContain("--mailer:getConnectionConflictPolicy", "--mailer:useConfiguredConnectionConflictPolicy");

            final CliExecutionResult policy = execute(environment, "send", POLICY_OPTION + "--help");
            assertThat(policy.exitCode()).as(policy.stderr()).isZero();
            assertThat(policy.stdout()).contains(POLICY_OPTION, "REJECT_CONFLICTS", "ALLOW_EXPLICIT_OVERRIDES");

            final CliExecutionResult reset = execute(environment, "send", RESET_OPTION + "--help");
            assertThat(reset.exitCode()).as(reset.stderr()).isZero();
            assertThat(reset.stdout()).contains(RESET_OPTION, "REJECT_CONFLICTS");
        }
    }

    @Test
    void strictDefaultRejectsCliCustomizationOfAnExplicitCapturedHost() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "configured.example.test", "simplejavamail.smtp.port", 2525)).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, new OneShotMailerProvider())) {
            final CliExecutionResult result = send(environment, "--mailer:withSMTPServer", "selected.example.test", "2525");
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            assertThat(result.stderr()).contains("smtp.host").containsIgnoringCase("conflict");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliberateOverrideUsesTheTypedProxyRegardlessOfRawPropertyOptionOrder(final boolean rawPropertyLast) {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        try (CliExecutionEnvironment environment = environment(ConfigLoader.builder().load(), selected)) {
            final List<String> options = new ArrayList<>(List.of(POLICY_OPTION, "ALLOW_EXPLICIT_OVERRIDES"));
            final List<String> rawProxy = List.of("--mailer:withProperty", "mail.smtp.socks.host", "raw-proxy.example.test");
            final List<String> typedProxy = List.of("--mailer:withProxy", "selected-proxy.example.test", "1081");
            options.addAll(rawPropertyLast ? typedProxy : rawProxy);
            options.addAll(rawPropertyLast ? rawProxy : typedProxy);

            final CliExecutionResult result = send(environment, options.toArray(new String[0]));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer.getOperationalConfig().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("selected-proxy.example.test");
            assertThat(mailer.getProxyConfig().getRemoteProxyPort()).isEqualTo(1081);
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("selected-proxy.example.test");
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("1081");
        }
    }

    @Test
    void capturedPermissivePolicyAllowsCustomizationAndResetRestoresStrictConstruction() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(POLICY_PROPERTY, "ALLOW_EXPLICIT_OVERRIDES",
                "simplejavamail.smtp.host", "configured.example.test", "simplejavamail.smtp.port", 2525)).load();
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        try (CliExecutionEnvironment environment = environment(config, selected)) {
            final CliExecutionResult allowed = send(environment, "--mailer:withSMTPServer", "selected.example.test", "2525");
            assertThat(allowed.exitCode()).as(allowed.stderr()).isZero();
            assertThat(selected.get().getOperationalConfig().getConnectionConflictPolicy())
                    .isEqualTo(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            assertThat(selected.get().getServerConfig().getHost()).isEqualTo("selected.example.test");

            final CliExecutionResult rejected = send(environment, "--mailer:withSMTPServer", "selected.example.test", "2525", RESET_OPTION);
            assertThat(rejected.exitCode()).as(rejected.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            assertThat(rejected.stderr()).contains("smtp.host").containsIgnoringCase("conflict");
        }
    }

    @Test
    void strictModeComparesTheFinalRepeatedCliChoiceInsteadOfSetterHistory() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "configured.example.test", "simplejavamail.smtp.port", 2525)).load();
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        try (CliExecutionEnvironment environment = environment(config, selected)) {
            final CliExecutionResult result = send(environment,
                    "--mailer:withSMTPServer", "discarded.example.test", "2525",
                    "--mailer:withSMTPServer", "configured.example.test", "2525");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(selected.get().getServerConfig().getHost()).isEqualTo("configured.example.test");
            assertThat(selected.get().getOperationalConfig().getConnectionConflictPolicy()).isEqualTo(ConnectionConflictPolicy.REJECT_CONFLICTS);
        }
    }

    @Test
    void permissiveCliSelectionCannotDefeatALockedPolicy() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.connection.conflictpolicy", "REJECT_CONFLICTS")).load();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config, new OneShotMailerProvider())) {
            final CliExecutionResult result = send(environment, POLICY_OPTION, "ALLOW_EXPLICIT_OVERRIDES");
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            assertThat(result.stderr()).contains("locked.connection.conflictpolicy");
        }
    }

    @Test
    void daemonMailerIdentityIncludesBothCapturedAndExplicitPolicyChoices() {
        final SimpleJavaMailConfig strict = ConfigLoader.builder().withMap(Map.of(POLICY_PROPERTY, "REJECT_CONFLICTS")).load();
        final SimpleJavaMailConfig permissive = ConfigLoader.builder().withMap(Map.of(POLICY_PROPERTY, "ALLOW_EXPLICIT_OVERRIDES")).load();
        final byte[] daemonKey = new byte[32];
        assertThat(CliMailerProfile.create(strict, List.of(), daemonKey))
                .isNotEqualTo(CliMailerProfile.create(permissive, List.of(), daemonKey));

        final CliDeclaredOptionSpec option = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots()).stream()
                .filter(candidate -> candidate.getName().equals(POLICY_OPTION)).findFirst().orElseThrow();
        final SimpleJavaMailConfig defaults = ConfigLoader.builder().load();
        final CliMailerProfile explicitStrict = CliMailerProfile.create(defaults,
                List.of(new CliReceivedOptionData(option, List.of(ConnectionConflictPolicy.REJECT_CONFLICTS))), daemonKey);
        final CliMailerProfile explicitPermissive = CliMailerProfile.create(defaults,
                List.of(new CliReceivedOptionData(option, List.of(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES))), daemonKey);
        assertThat(explicitStrict).isNotEqualTo(explicitPermissive);
    }

    private static CliExecutionEnvironment environment(final SimpleJavaMailConfig config, final AtomicReference<Mailer> selected) {
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        return new CliExecutionEnvironment(config, provider);
    }

    private static CliExecutionResult send(final CliExecutionEnvironment environment, final String... options) {
        final List<String> command = new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                "--email:to", "receiver@example.test", "--mailer:withTransportModeLoggingOnly", "true"));
        command.addAll(Arrays.asList(options));
        return execute(environment, command.toArray(new String[0]));
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... options) {
        return CliSupport.execute(options, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
