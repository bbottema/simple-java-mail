package org.simplejavamail.mailer.failover;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs the socket contracts with genuinely absent pool artifacts, not a mocked module-availability answer. */
class FailoverDirectClasspathTest {
    private static final List<String> POOL_ARTIFACTS = List.of("batch-module", "smtp-connection-pool", "clustered-object-pool", "generic-object-pool");

    @ParameterizedTest
    @ValueSource(strings = {"FailoverSmtpContractTest", "FailoverRecoveryContractTest", "FailoverScopeContractTest", "FailoverProviderContractTest",
            "FailoverDirectProviderContracts", "FailoverDirectRecoveryContracts", "FailoverTlsContractTest"})
    @Timeout(180)
    void directSendingHonorsTheSameContracts(final String suite) throws Exception {
        final Path reports = Path.of("target", "failover-blackbox-direct");
        Files.createDirectories(reports);
        final Path output = reports.resolve(suite + ".txt");
        final Path arguments = reports.resolve(suite + ".args");
        final String original = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final String directClasspath = Arrays.stream(original.split(File.pathSeparator))
                .filter(entry -> !isPoolArtifact(entry)).collect(Collectors.joining(File.pathSeparator));
        assertThat(directClasspath).as("pool artifacts were removed from the test runtime").isNotEqualTo(original);
        Files.writeString(arguments, "-ea\n-Dfile.encoding=UTF-8\n-Dsjm.failover.direct=true\n"
                + "-Dlog4j.configurationFile=log4j2-failover-contract.xml\n-cp\n\""
                + directClasspath.replace('\\', '/') + "\"\n"
                + FailoverDirectClasspathTest.class.getName() + "\n" + suite + "\n");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(160, SECONDS)).as("pool-free test JVM finished; output: %s", output).isTrue();
            assertThat(child.exitValue()).withFailMessage("Pool-free %s failed:%n%s", suite, Files.readString(output)).isZero();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(10, SECONDS)).as("pool-free test JVM stopped").isTrue();
            }
        }
    }

    private static boolean isPoolArtifact(final String entry) {
        final String normalized = entry.replace('\\', '/');
        return POOL_ARTIFACTS.stream().anyMatch(artifact -> normalized.contains("/" + artifact + "/")
                || normalized.contains("/" + artifact + "-"));
    }

    public static void main(final String[] arguments) {
        assertThat(System.getProperty("sjm.failover.direct")).isEqualTo("true");
        assertThat(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)))
                .noneMatch(FailoverDirectClasspathTest::isPoolArtifact);
        final SummaryGeneratingListener results = new SummaryGeneratingListener();
        LauncherFactory.create().execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass("org.simplejavamail.mailer.failover." + arguments[0])).build(), results);
        results.getSummary().printTo(new PrintWriter(System.out, true));
        results.getSummary().printFailuresTo(new PrintWriter(System.out, true));
        assertThat(results.getSummary().getTestsFoundCount()).isPositive();
        assertThat(results.getSummary().getTestsSkippedCount()).isZero();
        assertThat(results.getSummary().getTestsAbortedCount()).isZero();
        assertThat(results.getSummary().getTotalFailureCount()).isZero();
    }
}
