package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in forked-process harness; timings are evidence, never a brittle CI threshold. */
@EnabledIfSystemProperty(named = "sjm.runDaemonBenchmark", matches = "true")
class CliDaemonPerformanceTest {
	private static final int WARMUPS = 3;
	private static final int SAMPLES = 10;

	@TempDir
	Path stateRoot;

	@Test
	void recordsBareJavaOneShotColdStartAndWarmDaemonSamples() throws Exception {
		final List<Measurement> measurements = new ArrayList<>();
		measure("bare-java", bareJavaCommand(), measurements);
		measure("one-shot-validate", cliCommand(validationArguments("--daemon=off")), measurements);

		final long startNanos = timed(cliCommand(new String[] { "daemon", "start", "--daemon-instance=benchmark" }));
		measurements.add(new Measurement("daemon-start", 1, millis(startNanos)));
		try {
			final long firstNanos = timed(cliCommand(validationArguments("--daemon=require")));
			measurements.add(new Measurement("first-daemon-validate", 1, millis(firstNanos)));
			measure("warm-daemon-validate", cliCommand(validationArguments("--daemon=require")), measurements);
		} finally {
			run(cliCommand(new String[] { "daemon", "stop", "--daemon-instance=benchmark" }));
		}

		final String report = report(measurements);
		System.out.println(report);
		final Path output = Path.of("target", "cli-daemon-benchmark.csv");
		Files.createDirectories(output.getParent());
		Files.writeString(output, report, StandardCharsets.UTF_8);
	}

	private void measure(final String scenario, final List<String> command, final List<Measurement> target) throws Exception {
		for (int i = 0; i < WARMUPS; i++) {
			run(command);
		}
		for (int i = 1; i <= SAMPLES; i++) {
			target.add(new Measurement(scenario, i, millis(timed(command))));
		}
	}

	private long timed(final List<String> command) throws Exception {
		final long started = System.nanoTime();
		run(command);
		return System.nanoTime() - started;
	}

	private void run(final List<String> command) throws Exception {
		final Path output = Files.createTempFile(stateRoot, "benchmark-", ".log");
		final Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(output.toFile())
				.start();
		final boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		if (!exited) {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
		}
		final String text = Files.readString(output, StandardCharsets.UTF_8);
		assertThat(exited).as(text).isTrue();
		assertThat(process.exitValue()).as(text).isZero();
	}

	private List<String> bareJavaCommand() {
		return List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"), "demo.BareJavaProcessApp");
	}

	private List<String> cliCommand(final String[] arguments) {
		final List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
		command.add("-Dsimplejavamail.cli.daemon.console-child=true");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add("org.simplejavamail.cli.SimpleJavaMail");
		command.addAll(List.of(arguments));
		return command;
	}

	private static String[] validationArguments(final String executionMode) {
		return new String[] {
				"validate", executionMode, "--daemon-instance=benchmark",
				"--email:startingBlank",
				"--email:from", "sender@example.com",
				"--email:withSubject", "Daemon benchmark",
				"--email:withPlainText", "Body",
				"--email:to", "Recipient", "recipient@example.com",
				"--mailer:withSMTPServer", "localhost", "25"
		};
	}

	private static String report(final List<Measurement> measurements) {
		final StringBuilder report = new StringBuilder()
				.append("environment,jdk,").append(System.getProperty("java.version")).append('\n')
				.append("environment,os,").append(System.getProperty("os.name")).append(' ')
				.append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n')
				.append("scenario,sample,milliseconds\n");
		for (Measurement measurement : measurements) {
			report.append(measurement.scenario()).append(',').append(measurement.sample()).append(',')
					.append(measurement.milliseconds()).append('\n');
		}
		report.append("summary,scenario,median_ms,p95_ms\n");
		measurements.stream().map(Measurement::scenario).distinct().forEach(scenario -> {
			final List<Long> samples = measurements.stream()
					.filter(measurement -> measurement.scenario().equals(scenario))
					.map(Measurement::milliseconds).sorted().toList();
			report.append("summary,").append(scenario).append(',').append(percentile(samples, 0.5)).append(',')
					.append(percentile(samples, 0.95)).append('\n');
		});
		return report.toString();
	}

	private static long percentile(final List<Long> sortedSamples, final double percentile) {
		if (sortedSamples.isEmpty()) {
			return 0;
		}
		final int index = Math.max(0, (int) Math.ceil(percentile * sortedSamples.size()) - 1);
		return sortedSamples.get(index);
	}

	private static long millis(final long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}

	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString();
	}

	private record Measurement(String scenario, int sample, long milliseconds) {
	}
}
