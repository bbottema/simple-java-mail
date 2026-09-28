package testutil.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the harness, not a timing threshold. The manual runner obtains its exact reactor test classpath from this fork. */
class MailSendPerformanceHarnessTest {
    @TempDir Path directory;

    @Test
    void exportsTheReactorClasspathAndStartsOnlyLoopbackListeners() throws Exception {
        final Path classpath = Path.of("target", "mail-send-audit-classpath.txt");
        Files.writeString(classpath, System.getProperty("java.class.path"));
        for (final boolean advertisedSize : new boolean[] {true, false}) {
            try (AuditSmtpServer server = new AuditSmtpServer(advertisedSize)) {
                assertThat(server.port()).isPositive();
                assertThat(server.messages.get()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small-batch", "small-pooled", "memory-batch", "file-batch", "exact-batch", "dkim-batch", "limit-batch", "memory-nosize"})
    void smokeChecksSuccessfulSendsAndMeasuredCounts(final String scenario) throws Exception {
        final Path output = directory.resolve("samples.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            MailSendPerformanceAudit.auditScenario(directory, "baseline", scenario, 1, 3, 2, 512, false, writer);
        }
        assertThat(Files.readAllLines(output)).singleElement().asString().startsWith("baseline," + scenario + ",1,2,");
    }
}
