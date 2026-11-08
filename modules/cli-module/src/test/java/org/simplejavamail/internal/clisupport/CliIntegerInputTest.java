package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CliIntegerInputTest {

    static Stream<Arguments> invalidIntegerOptions() {
        return Stream.of("false", "true", "1.5", "2147483648", "-2147483649", "4294967321")
                .flatMap(value -> Stream.of(
                        Arguments.of("--mailer:withSessionTimeout", value, "Integer"),
                        Arguments.of("--mailer:withMaximumEmailSize", value, "int"),
                        Arguments.of("--mailer:withSMTPServer", value, "Integer")));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidIntegerOptions")
    void invalidIntegerOptionsFailBeforeMailerAcquisition(final String option, final String value, final String type) {
        final AtomicInteger acquisitions = new AtomicInteger();
        final MailerProvider provider = (profile, factory) -> {
            acquisitions.incrementAndGet();
            throw new IllegalStateException("Unexpected Mailer acquisition");
        };
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final String[] arguments = option.equals("--mailer:withSMTPServer")
                    ? new String[]{"connect", option, "localhost", value}
                    : new String[]{"connect", "--mailer:withSMTPServer", "localhost", "25", option, value};
            final CliExecutionResult result = execute(environment, arguments);

            assertThat(result.category()).as(result.stderr()).isEqualTo(CliExitCode.CLI_ERROR);
            assertThat(result.stderr()).contains("Unable to parse a command-line value as " + type);
            assertThat(acquisitions.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void integerOptionsStillBindValidValuesAndOmittedOptionalValuesDuringLoggingOnlySending(final boolean explicitPort) {
        final AtomicReference<Mailer> selected = new AtomicReference<>();
        final OneShotMailerProvider delegate = new OneShotMailerProvider();
        final MailerProvider provider = (profile, factory) -> delegate.acquire(profile, () -> {
            final Mailer mailer = factory.get();
            selected.set(mailer);
            return mailer;
        });
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final List<String> arguments = new ArrayList<>(List.of("send", "--email:startingBlank",
                    "--email:from", "sender@example.test", "--email:to", "receiver@example.test",
                    "--mailer:withTransportModeLoggingOnly", "true", "--mailer:withSMTPServer", "localhost"));
            if (explicitPort) { arguments.add("+25"); }
            arguments.addAll(List.of("--mailer:withSessionTimeout", "0", "--mailer:withMaximumEmailSize", "2147483647"));
            final CliExecutionResult result = execute(environment, arguments.toArray(String[]::new));

            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final Mailer mailer = selected.get();
            assertThat(mailer).isNotNull();
            assertThat(mailer.getServerConfig().getPort()).isEqualTo(25);
            assertThat(mailer.getServerConfig().getUsername()).isNull();
            assertThat(mailer.getServerConfig().getPassword()).isNull();
            assertThat(mailer.getOperationalConfig().getSessionTimeout()).isEqualTo(Duration.ZERO);
            assertThat(mailer.getEmailGovernance().getMaximumEmailSize()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
    }
}
