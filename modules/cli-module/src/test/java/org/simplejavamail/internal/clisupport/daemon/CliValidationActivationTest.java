package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.OneShotMailerProvider;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Exercises real CLI validation while intercepting the sending-resource boundary. */
class CliValidationActivationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneShotValidationDoesNotRegisterPrimaryOrNamedPools(final boolean namedRoute) {
        final AtomicInteger registrations = new AtomicInteger();
        try (MockedStatic<ModuleLoader> modules = batchBoundary(registrations);
             CliExecutionEnvironment environment = new CliExecutionEnvironment(configuration(namedRoute), new OneShotMailerProvider())) {
            final CliExecutionResult result = validate(environment);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(registrations).as("validation must not activate sending pools").hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coldDaemonValidationDoesNotPublishASendingMailer(final boolean namedRoute) {
        final AtomicInteger registrations = new AtomicInteger();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1));
        try (MockedStatic<ModuleLoader> modules = batchBoundary(registrations);
             CliExecutionEnvironment environment = new CliExecutionEnvironment(configuration(namedRoute), registry)) {
            final CliExecutionResult result = validate(environment);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(registry.entryCount()).as("validation must not seed the sending cache").isZero();
            assertThat(registry.activeLeaseCount()).isZero();
            assertThat(registrations).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedClusterCoreOverrideDoesNotActivateValidationPools(final boolean daemon) {
        final String cluster = UUID.randomUUID().toString();
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "smtp.example.test", "simplejavamail.defaults.connectionpool.clusterkey.uuid", cluster,
                "simplejavamail.defaults.connectionpool.clusters.validation.clusterkey.uuid", cluster,
                "simplejavamail.defaults.connectionpool.clusters.validation.coresize", 2,
                "simplejavamail.extraproperties.mail.host", "localhost")).load();
        final AtomicInteger registrations = new AtomicInteger();
        try (MockedStatic<ModuleLoader> modules = batchBoundary(registrations);
             CliExecutionEnvironment environment = new CliExecutionEnvironment(config,
                     daemon ? new DaemonMailerRegistry(2, Duration.ofMinutes(1)) : new OneShotMailerProvider())) {
            final CliExecutionResult result = validate(environment);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(registrations).hasValue(0);
        }
    }

    @org.junit.jupiter.api.Test
    void readyDaemonMailerIsReusedWithoutAnotherRegistration() {
        final AtomicInteger registrations = new AtomicInteger();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1));
        try (MockedStatic<ModuleLoader> modules = batchBoundary(registrations);
             CliExecutionEnvironment environment = new CliExecutionEnvironment(configuration(false), registry)) {
            final CliMailerProfile profile = CliMailerProfile.create(environment.config(), List.of(), new byte[32],
                    environment.configurationWorkingDirectory());
            try (org.simplejavamail.internal.clisupport.MailerProvider.Lease ignored = registry.acquire(profile,
                    () -> environment.simpleJavaMail().mailerBuilder().buildMailer())) { }
            assertThat(registrations).hasValue(1);
            registrations.set(0);
            final CliExecutionResult result = validate(environment);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(registry.entryCount()).isEqualTo(1);
            assertThat(registry.activeLeaseCount()).isZero();
            assertThat(registrations).hasValue(0);
        }
    }

    @org.junit.jupiter.api.Test
    void aLaterSendStillCreatesItsConfiguredSendingMailer() {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of("simplejavamail.smtp.host", "smtp.example.test",
                "simplejavamail.defaults.connectionpool.coresize", 1, "simplejavamail.transport.mode.logging.only", true,
                "simplejavamail.extraproperties.mail.host", "localhost")).load();
        final AtomicInteger registrations = new AtomicInteger();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1));
        try (MockedStatic<ModuleLoader> modules = batchBoundary(registrations);
             CliExecutionEnvironment environment = new CliExecutionEnvironment(config, registry)) {
            assertThat(validate(environment).exitCode()).isZero();
            assertThat(registry.entryCount()).isZero();
            assertThat(registrations).hasValue(0);
            final CliExecutionResult sent = execute(environment, "send");
            assertThat(sent.exitCode()).as(sent.stderr()).isZero();
            assertThat(registry.entryCount()).isEqualTo(1);
            assertThat(registrations).hasValue(1);
        }
    }

    private static MockedStatic<ModuleLoader> batchBoundary(final AtomicInteger registrations) {
        final BatchModule batch = mock(BatchModule.class);
        final MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        modules.when(ModuleLoader::batchModuleAvailable).thenReturn(true);
        modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
        doAnswer(invocation -> { registrations.incrementAndGet(); return null; })
                .when(batch).registerToCluster(any(), any(), any(), any(), any(), any());
        when(batch.shutdownConnectionPools(any())).thenReturn(CompletableFuture.completedFuture(null));
        return modules;
    }

    private static SimpleJavaMailConfig configuration(final boolean namedRoute) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("simplejavamail.smtp.host", "smtp.example.test");
        values.put("simplejavamail.extraproperties.mail.host", "localhost");
        values.put("simplejavamail.defaults.connectionpool.coresize", 1);
        if (namedRoute) {
            values.put("simplejavamail.failover.mailer", "backup");
            values.put("simplejavamail.mailers.backup.smtp.host", "backup.example.test");
            values.put("simplejavamail.mailers.backup.extraproperties.mail.host", "localhost");
            values.put("simplejavamail.mailers.backup.defaults.connectionpool.coresize", 1);
        }
        return ConfigLoader.builder().withMap(values).load();
    }

    private static CliExecutionResult validate(final CliExecutionEnvironment environment) {
        return execute(environment, "validate");
    }

    private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String command) {
        return CliSupport.execute(new String[] {command, "--email:startingBlank", "--email:from", "sender@example.org",
                        "--email:to", "recipient@example.org", "--email:withPlainText", "Local validation"},
                Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, new byte[32]);
    }
}
