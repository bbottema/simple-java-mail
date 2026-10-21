package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.clisupport.CliMailerConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CliValidationWarningOptionsTest {

    @Test
    void generatedOptionsAndHelpKeepTheExistingNamesAndBooleanMeaning() {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        assertThat(options).filteredOn(option -> option.getName().equals("--mailer:disablingAllClientValidation")).singleElement()
                .satisfies(option -> {
                    assertThat(option.getSourceMethod().getDeclaringClass()).isEqualTo(CliMailerConfigurationBuilder.class);
                    assertThat(option.getSourceMethod().getParameterTypes()).containsExactly(Boolean.class);
                });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult setterHelp = execute(environment, "send", "--mailer:disablingAllClientValidation--help");
            assertThat(setterHelp.exitCode()).as(setterHelp.stderr()).isZero();
            assertThat(setterHelp.stdout()).contains("still run", "reported as warnings", "default false");
            final CliExecutionResult resetHelp = execute(environment, "send", "--mailer:resetDisableAllClientValidations--help");
            assertThat(resetHelp.exitCode()).as(resetHelp.stderr()).isZero();
            assertThat(resetHelp.stdout()).contains("Restores blocking client-side validation", "default (false)");
            final CliExecutionResult generalHelp = execute(environment, "send", "--help");
            assertThat(generalHelp.exitCode()).as(generalHelp.stderr()).isZero();
            assertThat(generalHelp.stdout()).contains("--mailer:disablingAllClientValidation", "--mailer:resetDisableAllClientValidations")
                    .doesNotContain("--mailer:withClientValidationWarningsOnly", "--mailer:resetClientValidationWarningsOnly");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingBooleanOptionRetainsItsPolarityAndOverridesConfiguredProperties(final boolean warningsOnly) {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.disable.all.clientvalidation", true)).load(), provider)) {
            final CliExecutionResult result = execute(environment, validationArguments(
                    "--mailer:disablingAllClientValidation", Boolean.toString(warningsOnly)));
            assertThat(selected.get()).as(result.stderr()).isNotNull();
            assertThat(selected.get().getOperationalConfig().isClientValidationWarningsOnly()).isEqualTo(warningsOnly);
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(warningsOnly ? 0 : CliExitCode.COMMAND_FAILED.code());
        }
    }

    @Test
    void existingResetOptionSelectsBlockingEvenWhenPropertiesEnableWarnings() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.disable.all.clientvalidation", true)).load(), new OneShotMailerProvider())) {
            final CliExecutionResult result = execute(environment, validationArguments(
                    "--mailer:disablingAllClientValidation", "true", "--mailer:resetDisableAllClientValidations"));
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            assertThat(result.stderr()).contains("not-an-email-address");
        }
    }

    @Test
    void existingCliOptionCannotOverrideALockedBlockingPolicy() {
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.locked.disable.all.clientvalidation", false)).load(), new OneShotMailerProvider())) {
            final CliExecutionResult result = execute(environment, validationArguments("--mailer:disablingAllClientValidation", "true"));
            assertThat(result.exitCode()).as(result.stderr()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
            assertThat(result.stderr()).contains("locked.disable.all.clientvalidation");
        }
    }

    private static String[] validationArguments(final String... extras) {
        final List<String> arguments = new ArrayList<>(List.of("validate", "--email:startingBlank", "--email:from", "not-an-email-address",
                "--email:to", "receiver@example.test", "--email:withPlainText", "body",
                "--mailer:withSMTPServer", "unused.example.invalid", "25"));
        arguments.addAll(Arrays.asList(extras));
        return arguments.toArray(new String[0]);
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
