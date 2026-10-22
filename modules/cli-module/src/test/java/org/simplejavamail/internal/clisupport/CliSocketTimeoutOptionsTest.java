package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliSocketTimeoutOptionsTest {

    @Test
    void scalarAdaptersExposeNumericTimeoutChoicesAndZeroArgumentResetsWithUsableHelp() {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), new OneShotMailerProvider())) {
            for (final String operation : List.of("Connect", "Read", "Write")) {
                final String setter = "--mailer:with" + operation + "Timeout";
                assertThat(options).filteredOn(option -> option.getName().equals(setter)).singleElement().satisfies(option -> {
                    assertThat(option.getSourceMethod().getDeclaringClass().getSimpleName()).isEqualTo("CliMailerConfigurationBuilder");
                    assertThat(option.getSourceMethod().getParameterTypes()).containsExactly(Integer.class);
                });
                final CliExecutionResult help = execute(environment, "send", setter + "--help");
                assertThat(help.exitCode()).as(help.stderr()).isZero();
                assertThat(help.stdout()).contains(setter, "milliseconds");
                final String reset = "--mailer:reset" + operation + "Timeout";
                assertThat(options).filteredOn(option -> option.getName().equals(reset)).singleElement()
                        .satisfies(option -> assertThat(option.getSourceMethod().getParameterCount()).isZero());
            }
        }
    }

    @Test
    void combinedShorthandCanBeFineTunedAndLaterCombinedChoicesReplaceAllThreeAgain() {
        assertCommandTimeouts(10_000, 45_000, 30_000,
                "--mailer:withSessionTimeout", "30000", "--mailer:withConnectTimeout", "10000", "--mailer:withReadTimeout", "45000");
        assertCommandTimeouts(20_000, 20_000, 20_000,
                "--mailer:withSessionTimeout", "30000", "--mailer:withConnectTimeout", "10000", "--mailer:withReadTimeout", "45000",
                "--mailer:withSessionTimeout", "20000");
        assertCommandTimeouts(60_000, 45_000, 60_000,
                "--mailer:withSessionTimeout", "30000", "--mailer:withReadTimeout", "45000",
                "--mailer:resetConnectTimeout", "--mailer:resetWriteTimeout");
    }

    private static void assertCommandTimeouts(final int connect, final int read, final int write, final String... options) {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> command = new ArrayList<>(List.of("send", "--email:startingBlank", "--email:from", "sender@example.test",
                    "--email:to", "reader@example.test", "--mailer:withTransportModeLoggingOnly", "true"));
            command.addAll(Arrays.asList(options));
            final CliExecutionResult result = execute(environment, command.toArray(new String[0]));
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer.getOperationalConfig().getConnectTimeout()).isEqualTo(Duration.ofMillis(connect));
            assertThat(mailer.getOperationalConfig().getReadTimeout()).isEqualTo(Duration.ofMillis(read));
            assertThat(mailer.getOperationalConfig().getWriteTimeout()).isEqualTo(Duration.ofMillis(write));
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(connect == read && read == write ? Duration.ofMillis(connect) : null);
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... args) {
        return CliSupport.execute(args, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
