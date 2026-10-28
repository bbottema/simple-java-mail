package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonStartupConfigurationProcessTest {

	@TempDir Path stateRoot;

	@ParameterizedTest
	@ValueSource(strings = {"start", "acquire"})
	void startupCapturesTheCompleteRouteAndRestartUsesTheNewInvocationsConfiguration(final String mode) throws Exception {
		final String instance = "startup-" + mode;
		final Map<String, String> original = startupConfiguration("primary.example.invalid", "standby");
		assertSuccess(invoke(original, validation("off", instance)));
		try {
			if (mode.equals("start")) {
				assertSuccess(invoke(original, "daemon", "start", "--daemon-instance=" + instance));
				assertSuccess(invoke(Map.of(), validation("require", instance)));
			} else {
				assertSuccess(invoke(original, validation("acquire", instance)));
			}

			final Map<String, String> laterClient = Map.of("simplejavamail.smtp.host", "client.example.invalid",
					"simplejavamail.failover.mailer", "undefined-on-purpose");
			assertSuccess(invoke(laterClient, validation("require", instance)));
			assertSuccess(invoke(laterClient, validation("acquire", instance)));
			assertLockedHost(instance, "client.example.invalid");

			final Map<String, String> replacement = startupConfiguration("replacement.example.invalid", "new-standby");
			assertSuccess(invoke(replacement, "daemon", "restart", "--daemon-instance=" + instance));
			assertSuccess(invoke(Map.of(), validation("require", instance)));
			assertLockedHost(instance, "primary.example.invalid");
		} finally {
			assertSuccess(invoke(Map.of(), "daemon", "stop", "--daemon-instance=" + instance));
		}
	}

	private void assertLockedHost(final String instance, final String attemptedHost) throws Exception {
		final List<String> arguments = new ArrayList<>(List.of(validation("require", instance)));
		arguments.addAll(List.of("--mailer:withSMTPServer", attemptedHost));
		final Invocation rejected = invoke(Map.of(), arguments.toArray(new String[0]));
		assertThat(rejected.exitCode()).as(rejected.output()).isNotZero();
		assertThat(rejected.output()).contains("simplejavamail.locked.smtp.host");
	}

	private static Map<String, String> startupConfiguration(final String primaryHost, final String secondary) {
		final Map<String, String> properties = new LinkedHashMap<>();
		properties.put("simplejavamail.smtp.host", primaryHost);
		properties.put("simplejavamail.locked.smtp.host", primaryHost);
		properties.put("simplejavamail.failover.mailer", secondary);
		properties.put("simplejavamail.mailers." + secondary + ".smtp.host", "backup.example.invalid");
		properties.put("simplejavamail.locked.mailers." + secondary + ".smtp.port", "2525");
		properties.put("simplejavamail.mailers." + secondary + ".failover.mailer", "last");
		properties.put("simplejavamail.mailers." + secondary + ".extraproperties.mail.smtp.timeout", "1000");
		properties.put("simplejavamail.mailers.last.smtp.host", "last.example.invalid");
		return properties;
	}

	private static String[] validation(final String mode, final String instance) {
		return new String[] {"validate", "--daemon=" + mode, "--daemon-instance=" + instance,
				"--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "recipient@example.org",
				"--email:withPlainText", "Startup configuration"};
	}

	private Invocation invoke(final Map<String, String> properties, final String... arguments) throws Exception {
		final List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString());
		command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
		command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true");
		command.add("-Dsimplejavamail.cli.daemon.console-child=true");
		properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
		command.addAll(List.of("-cp", System.getProperty("java.class.path"), "org.simplejavamail.cli.SimpleJavaMail"));
		command.addAll(List.of(arguments));
		final Path output = Files.createTempFile(stateRoot, "cli-", ".log");
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("CLI process completed").isTrue();
			return new Invocation(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned CLI process stopped").isTrue();
			}
		}
	}

	private static void assertSuccess(final Invocation invocation) {
		assertThat(invocation.exitCode()).as(invocation.output()).isZero();
	}

	private record Invocation(int exitCode, String output) { }
}
