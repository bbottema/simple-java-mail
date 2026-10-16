package org.simplejavamail.otel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class TelemetryAgentCoexistenceTest {
    @Test
    void parentageAndAccountingSurviveBothJvmModes() throws Exception {
        for (boolean agent : new boolean[] { false, true }) {
            try (TelemetryOtlpCollector collector = new TelemetryOtlpCollector()) {
                final List<String> command = fixtureCommand(agent, collector.endpoint());
                final Path log = Path.of("target/telemetry-coexistence-" + agent + ".log");
                final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    assertThat(process.waitFor(40, SECONDS)).as("child JVM finished: %s", log).isTrue();
                    assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
                    assertThat(Files.readString(log)).contains("TELEMETRY COEXISTENCE OK agent=" + agent);
                    if (agent) {
                        assertAgentExports(collector);
                    }
                } finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            }
        }
    }

    private static List<String> fixtureCommand(final boolean agent, final String collectorEndpoint) {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dio.opentelemetry.context.enableStrictContext=true");
        if (agent) {
            command.add("-javaagent:" + Path.of("target/agent/opentelemetry-javaagent.jar").toAbsolutePath());
            command.add("-Dotel.traces.exporter=otlp");
            command.add("-Dotel.metrics.exporter=otlp");
            command.add("-Dotel.logs.exporter=none");
            command.add("-Dotel.exporter.otlp.protocol=http/protobuf");
            command.add("-Dotel.exporter.otlp.endpoint=" + collectorEndpoint);
            command.add("-Dotel.bsp.schedule.delay=10");
            command.add("-Dotel.metric.export.interval=100");
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(TelemetryAgentFixture.class.getName());
        command.add(Boolean.toString(agent));
        return command;
    }

    private static void assertAgentExports(final TelemetryOtlpCollector collector) {
        assertThat(collector.failures()).isEmpty();
        assertThat(collector.named("incoming request")).hasSize(1);
        assertThat(collector.named("mail.send")).hasSize(2);
        assertThat(collector.named("mail.custom_submit")).hasSize(1);
        assertThat(collector.named("mail.submit")).hasSize(1);
        final var parent = collector.named("incoming request").get(0);
        assertThat(collector.named("mail.send"))
                .allSatisfy(send -> assertThat(send.getParentSpanId()).isEqualTo(parent.getSpanId()));
        for (String name : new String[] { "mail.custom_submit", "mail.submit" }) {
            final var submission = collector.named(name).get(0);
            assertThat(collector.named("mail.send"))
                    .anySatisfy(send -> assertThat(submission.getParentSpanId()).isEqualTo(send.getSpanId()));
            assertThat(submission.getTraceId()).isEqualTo(parent.getTraceId());
        }
        assertThat(collector.latestSum("simplejavamail.mail.send.completed")).isEqualTo(2);
        assertThat(collector.latestSum("simplejavamail.mail.submission.completed")).isEqualTo(2);
        assertThat(collector.latestSum("simplejavamail.mail.send.active")).isZero();
    }
}
