package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.simplejavamail.config.ConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Starts a detached daemon child and reports success only after authenticated readiness.
 * The child runs the same CLI artifact in foreground-daemon mode, inherits supported configuration through its
 * environment, and writes startup diagnostics to private state. Competing starts are observed rather than killed, and
 * a child that never becomes ready is terminated within a fixed deadline.
 */
final class DaemonProcessLauncher {
	private static final Duration START_TIMEOUT = Duration.ofSeconds(15);

	private DaemonProcessLauncher() {
	}

	static CliExecutionResult start(final String instance) {
		final DaemonClient client = new DaemonClient(instance);
		final CliExecutionResult existing = client.ready();
		if (existing.category() == CliExitCode.SUCCESS) {
			return new CliExecutionResult(CliExitCode.SUCCESS, "Daemon already running." + System.lineSeparator(), "");
		}
		if (existing.category() != CliExitCode.DAEMON_ABSENT) {
			return existing;
		}
		final Process child;
		try {
			child = launchDaemon(client.paths(), instance);
		} catch (IOException | RuntimeException e) {
			return new CliExecutionResult(e instanceof SecurityException ? CliExitCode.DAEMON_SECURITY
					: CliExitCode.DAEMON_START_FAILED, "", "Unable to launch the daemon process: " + safeMessage(e)
					+ System.lineSeparator());
		}
		return awaitAuthenticatedReadiness(client, child);
	}

	private static Process launchDaemon(final DaemonPaths paths, final String instance) throws IOException {
		paths.prepare();
		prepareStartupLog(paths.startupLogFile());
		final ProcessBuilder builder = new ProcessBuilder(daemonCommand(paths, instance));
		copyConfigurationSystemProperties(builder.environment(), System.getProperties());
		builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
		builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.startupLogFile().toFile()));
		builder.redirectError(ProcessBuilder.Redirect.appendTo(paths.startupLogFile().toFile()));
		return builder.start();
	}

	private static void prepareStartupLog(final Path startupLog) throws IOException {
		Files.write(startupLog, new byte[0], StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		DaemonStateSecurity.ensurePrivateFile(startupLog);
	}

	private static CliExecutionResult awaitAuthenticatedReadiness(final DaemonClient client, final Process child) {
		final Instant deadline = Instant.now().plus(START_TIMEOUT);
		boolean waitingForCompetingStart = false;
		while (Instant.now().isBefore(deadline)) {
			final CliExecutionResult ready = client.ready();
			if (ready.category() == CliExitCode.SUCCESS) {
				return new CliExecutionResult(CliExitCode.SUCCESS,
						(waitingForCompetingStart ? "Daemon already running." : "Daemon started.")
								+ System.lineSeparator(), "");
			}
			if (!child.isAlive() && !waitingForCompetingStart) {
				final int exitCode = child.exitValue();
				if (exitCode == CliExitCode.DAEMON_REFUSED.code()) {
					waitingForCompetingStart = true;
					continue;
				}
				final CliExitCode category = CliExitCode.isKnown(exitCode) && exitCode != CliExitCode.SUCCESS.code()
						? CliExitCode.fromCode(exitCode) : CliExitCode.DAEMON_START_FAILED;
				return new CliExecutionResult(category, "",
						"The daemon process exited before authenticated readiness." + System.lineSeparator());
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				terminateChild(child);
				return startFailure("Interrupted while waiting for daemon readiness.");
			}
		}
		if (child.isAlive()) {
			terminateChild(child);
		}
		return startFailure(waitingForCompetingStart
				? "Timed out waiting for a competing daemon start to become ready."
				: "Timed out waiting for authenticated daemon readiness.");
	}

	static void copyConfigurationSystemProperties(final Map<String, String> environment, final Properties properties) {
		final Map<String, String> scalarEnvironmentNames = new HashMap<>();
		for (ConfigLoader.Property property : ConfigLoader.Property.values()) {
			if (!property.key().contains("*")) {
				scalarEnvironmentNames.put(property.key(), property.key().replace('.', '_').toUpperCase(Locale.ROOT));
			}
		}
		for (String name : properties.stringPropertyNames()) {
			final String environmentName = scalarEnvironmentNames.get(name);
			if (environmentName != null) {
				environment.put(environmentName, properties.getProperty(name));
			} else if (name.startsWith("simplejavamail.extraproperties.")
					|| name.startsWith("simplejavamail.defaults.connectionpool.clusters.")) {
				environment.put(name, properties.getProperty(name));
			}
		}
	}

	private static List<String> daemonCommand(final DaemonPaths paths, final String instance) {
		final List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + paths.stateRoot());
		copyProperty(command, DaemonPaths.FORCE_TCP_PROPERTY);
		copyProperty(command, "simplejavamail.cli.version");
		copyProperty(command, "simplejavamail.cli.daemon.max-mailers");
		copyProperty(command, "simplejavamail.cli.daemon.mailer-idle-millis");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add("org.simplejavamail.cli.SimpleJavaMail");
		command.add("daemon");
		command.add("run");
		command.add("--daemon-instance=" + instance);
		return command;
	}

	private static void copyProperty(final List<String> command, final String name) {
		final String value = System.getProperty(name);
		if (value != null) {
			command.add("-D" + name + "=" + value);
		}
	}

	private static String javaExecutable() {
		final Path bin = Path.of(System.getProperty("java.home"), "bin");
		if (DaemonPaths.isWindows()) {
			final Path javaw = bin.resolve("javaw.exe");
			if (Files.isRegularFile(javaw) && !Boolean.getBoolean("simplejavamail.cli.daemon.console-child")) {
				return javaw.toString();
			}
			return bin.resolve("java.exe").toString();
		}
		return bin.resolve("java").toString();
	}

	private static java.io.File nullDevice() {
		return new java.io.File(DaemonPaths.isWindows() ? "NUL" : "/dev/null");
	}

	private static void terminateChild(final Process child) {
		child.destroy();
		try {
			if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
				child.destroyForcibly();
				child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			child.destroyForcibly();
		}
	}

	private static CliExecutionResult startFailure(final String message) {
		return new CliExecutionResult(CliExitCode.DAEMON_START_FAILED, "", message + System.lineSeparator());
	}

	private static String safeMessage(final Throwable throwable) {
		return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
	}
}
