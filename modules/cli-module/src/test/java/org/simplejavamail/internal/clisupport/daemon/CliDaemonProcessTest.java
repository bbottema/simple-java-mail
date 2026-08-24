package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.smtp.server.Session;
import org.subethamail.smtp.server.SessionHandler;
import org.subethamail.wiser.Wiser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CliDaemonProcessTest {
	@TempDir
	Path stateRoot;

	@Test
	void startStatusRoutedValidationMailerReuseAndStopWorkOverLoopback() throws Exception {
		final Invocation absent = invoke(true, "daemon", "status", "--daemon-instance=integration");
		assertThat(absent.exitCode).as(absent.output).isEqualTo(CliExitCode.DAEMON_ABSENT.code());

		final Invocation start = invoke(true, "daemon", "start", "--daemon-instance=integration");
		assertThat(start.exitCode).as(start.output).isZero();
		try {
			final Invocation first = invoke(true, validationArguments("localhost"));
			assertThat(first.exitCode).as(first.output).isZero();
			final Invocation second = invoke(true, validationArguments("localhost"));
			assertThat(second.exitCode).as(second.output).isZero();
			final Invocation otherProfile = invoke(true, validationArguments("smtp.other.example"));
			assertThat(otherProfile.exitCode).as(otherProfile.output).isZero();
			final String password = "daemon-password-" + UUID.randomUUID();
			final String body = "daemon-body-" + UUID.randomUUID();
			final Invocation secretBearing = invoke(true, secretValidationArguments(password, body));
			assertThat(secretBearing.exitCode).as(secretBearing.output).isZero();

			final Invocation status = invoke(true, "daemon", "status", "--daemon-instance=integration");
			assertThat(status.exitCode).as(status.output).isZero();
			assertThat(status.output).contains("state=READY", "transport=TCP", "mailers=3");
			try (java.util.stream.Stream<Path> files = Files.walk(stateRoot)) {
				for (Path file : files.filter(Files::isRegularFile)
						.filter(path -> !path.getFileName().toString().equals("instance.lock")).toList()) {
					final String contents = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
					assertThat(contents).as(file.toString()).doesNotContain(password, body);
				}
			}
		} finally {
			final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=integration");
			assertThat(stop.exitCode).as(stop.output).isZero();
		}

		final Invocation stopped = invoke(true, "daemon", "status", "--daemon-instance=integration");
		assertThat(stopped.exitCode).as(stopped.output).isEqualTo(CliExitCode.DAEMON_ABSENT.code());
	}

	@Test
	void acquireShorthandStartsTheSelectedDaemonBeforeExecuting() throws Exception {
		final String[] command = validationArguments("localhost");
		command[1] = "-d";
		command[2] = "--daemon-instance=acquire";
		final Invocation acquired = invoke(true, command);
		assertThat(acquired.exitCode).as(acquired.output).isZero();
		try {
			final Invocation status = invoke(true, "daemon", "status", "--daemon-instance=acquire");
			assertThat(status.exitCode).as(status.output).isZero();
			assertThat(status.output).contains("mailers=1");
		} finally {
			final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=acquire");
			assertThat(stop.exitCode).as(stop.output).isZero();
		}
	}

	@Test
	void prefersUnixDomainSocketsWhenTheRuntimeSupportsThem() throws Exception {
		final Invocation start = invoke(false, "daemon", "start", "--daemon-instance=unix");
		assertThat(start.exitCode).as(start.output).isZero();
		try {
			final Invocation status = invoke(false, "daemon", "status", "--daemon-instance=unix");
			assertThat(status.exitCode).as(status.output).isZero();
			assertThat(status.output).contains("transport=UNIX");
		} finally {
			final Invocation stop = invoke(false, "daemon", "stop", "--daemon-instance=unix");
			assertThat(stop.exitCode).as(stop.output).isZero();
		}
	}

	@Test
	void managementAndRoutedClientsUseTheThinBootstrapWithoutLoadingTheMailStack() throws Exception {
		final List<String> command = command(true,
				new String[] { "daemon", "status", "--daemon-instance=thin-bootstrap" });
		command.add(1, "-Xlog:class+load=info");
		final Invocation status = invoke(command);

		assertThat(status.exitCode).as(status.output).isEqualTo(CliExitCode.DAEMON_ABSENT.code());
		assertThinClassLoading(status);

		final Invocation start = invoke(true, "daemon", "start", "--daemon-instance=thin-bootstrap");
		assertThat(start.exitCode).as(start.output).isZero();
		try {
			final List<String> routedCommand = command(true, validationArguments("localhost", "thin-bootstrap"));
			routedCommand.add(1, "-Xlog:class+load=info");
			final Invocation routed = invoke(routedCommand);
			assertThat(routed.exitCode).as(routed.output).isZero();
			assertThinClassLoading(routed);
		} finally {
			final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=thin-bootstrap");
			assertThat(stop.exitCode).as(stop.output).isZero();
		}
	}

	@Test
	void repeatedDaemonSendsReuseTheSameSmtpConnection() throws Exception {
		final AtomicInteger acceptedSessions = new AtomicInteger();
		final Wiser smtp = Wiser.create(SMTPServer.port(0).sessionHandler(new SessionHandler() {
			@Override
			public SessionAcceptance accept(final Session session) {
				acceptedSessions.incrementAndGet();
				return SessionAcceptance.success();
			}

			@Override
			public void onSessionEnd(final Session session) {
			}
		}));
		smtp.start();
		Invocation start = null;
		try {
			final String[] oneShotArguments = sendArguments(smtp.getServer().getPortAllocated(), "one-shot");
			oneShotArguments[1] = "--daemon=off";
			final Invocation oneShot = invoke(true, oneShotArguments);
			assertThat(oneShot.exitCode).as(oneShot.output).isZero();
			final int sessionsBeforeDaemon = acceptedSessions.get();

			start = invoke(true, "daemon", "start", "--daemon-instance=smtp-pool");
			assertThat(start.exitCode).as(start.output).isZero();
			final int smtpPort = smtp.getServer().getPortAllocated();
			final Invocation first = invoke(true, sendArguments(smtpPort, "first"));
			final Invocation second = invoke(true, sendArguments(smtpPort, "second"));
			assertThat(first.exitCode).as(first.output).isZero();
			assertThat(second.exitCode).as(second.output).isZero();
			assertThat(smtp.getMessages()).hasSize(3);
			assertThat(acceptedSessions).hasValue(sessionsBeforeDaemon + 1);
			final Invocation connect = invoke(true, connectArguments(smtpPort));
			assertThat(connect.exitCode).as(connect.output).isZero();
		} finally {
			if (start != null && start.exitCode == CliExitCode.SUCCESS.code()) {
				final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=smtp-pool");
				assertThat(stop.exitCode).as(stop.output).isZero();
			}
			smtp.stop();
		}
	}

	@Test
	void daemonAndMailerReuseRemainAvailableWithoutTheOptionalBatchStack() throws Exception {
		final String classPath = withoutBatchStack(System.getProperty("java.class.path"));
		assertThat(classPath).doesNotContain("batch-module", "smtp-connection-pool", "clustered-object-pool");
		final Invocation start = invoke(command(true,
				new String[] { "daemon", "start", "--daemon-instance=no-batch" }, classPath));
		assertThat(start.exitCode).as(start.output).isZero();
		try {
			final Invocation first = invoke(command(true, validationArguments("localhost", "no-batch"), classPath));
			final Invocation second = invoke(command(true, validationArguments("localhost", "no-batch"), classPath));
			assertThat(first.exitCode).as(first.output).isZero();
			assertThat(second.exitCode).as(second.output).isZero();
			final Invocation status = invoke(command(true,
					new String[] { "daemon", "status", "--daemon-instance=no-batch" }, classPath));
			assertThat(status.exitCode).as(status.output).isZero();
			assertThat(status.output).contains("mailers=1");
		} finally {
			final Invocation stop = invoke(command(true,
					new String[] { "daemon", "stop", "--daemon-instance=no-batch" }, classPath));
			assertThat(stop.exitCode).as(stop.output).isZero();
		}
	}

	@Test
	void concurrentStartsConvergeOnOneReadyDaemon() throws Exception {
		final ExecutorService executor = Executors.newFixedThreadPool(2);
		final CountDownLatch startTogether = new CountDownLatch(1);
		try {
			final List<Future<Invocation>> starts = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				starts.add(executor.submit(() -> {
					startTogether.await();
					return invoke(true, "daemon", "start", "--daemon-instance=concurrent-start");
				}));
			}
			startTogether.countDown();
			for (Future<Invocation> start : starts) {
				final Invocation result = start.get(30, TimeUnit.SECONDS);
				assertThat(result.exitCode).as(result.output).isZero();
			}
			final Invocation status = invoke(true, "daemon", "status", "--daemon-instance=concurrent-start");
			assertThat(status.exitCode).as(status.output).isZero();
			assertThat(status.output).contains("state=READY");
		} finally {
			startTogether.countDown();
			executor.shutdownNow();
			final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=concurrent-start");
			assertThat(stop.exitCode).as(stop.output).isIn(CliExitCode.SUCCESS.code(), CliExitCode.DAEMON_ABSENT.code());
		}
	}

	@Test
	void authenticatedRestartReplacesTheDaemonGeneration() throws Exception {
		final Invocation start = invoke(true, "daemon", "start", "--daemon-instance=restart");
		assertThat(start.exitCode).as(start.output).isZero();
		try {
			final Invocation before = invoke(true, "daemon", "status", "--daemon-instance=restart");
			final Invocation restart = invoke(true, "daemon", "restart", "--daemon-instance=restart");
			final Invocation after = invoke(true, "daemon", "status", "--daemon-instance=restart");
			assertThat(before.exitCode).as(before.output).isZero();
			assertThat(restart.exitCode).as(restart.output).isZero();
			assertThat(after.exitCode).as(after.output).isZero();
			assertThat(after.output).contains("state=READY", "mailers=0");
			assertThat(value(before.output, "pid")).isNotEqualTo(value(after.output, "pid"));
		} finally {
			final Invocation stop = invoke(true, "daemon", "stop", "--daemon-instance=restart");
			assertThat(stop.exitCode).as(stop.output).isZero();
		}
	}

	private String[] validationArguments(final String smtpHost) {
		return validationArguments(smtpHost, "integration");
	}

	private String[] validationArguments(final String smtpHost, final String instance) {
		return new String[] {
				"validate", "--daemon=require", "--daemon-instance=" + instance,
				"--email:startingBlank",
				"--email:from", "sender@example.com",
				"--email:withSubject", "Daemon validation",
				"--email:withPlainText", "Body",
				"--email:to", "Recipient", "recipient@example.com",
				"--mailer:withSMTPServer", smtpHost, "25"
		};
	}

	private static void assertThinClassLoading(final Invocation invocation) {
		assertThat(invocation.output)
				.doesNotContain("org.simplejavamail.internal.clisupport.CliSupport source:")
				.doesNotContain("org.simplejavamail.config.ConfigLoader source:")
				.doesNotContain("org.simplejavamail.api.mailer.Mailer source:")
				.doesNotContain("jakarta.mail.Session source:");
	}

	private String[] secretValidationArguments(final String password, final String body) {
		return new String[] {
				"validate", "--daemon=require", "--daemon-instance=integration",
				"--email:startingBlank",
				"--email:from", "sender@example.com",
				"--email:withSubject", "Secret scan",
				"--email:withPlainText", body,
				"--email:to", "Recipient", "recipient@example.com",
				"--mailer:withSMTPServer", "localhost", "25", "user", password
		};
	}

	private String[] sendArguments(final int smtpPort, final String subject) {
		return new String[] {
				"send", "--daemon=require", "--daemon-instance=smtp-pool",
				"--email:startingBlank",
				"--email:from", "sender@example.com",
				"--email:withSubject", subject,
				"--email:withPlainText", "Daemon SMTP pool reuse",
				"--email:to", "Recipient", "recipient@example.com",
				"--mailer:withSMTPServer", "127.0.0.1", Integer.toString(smtpPort),
				"--mailer:withConnectionPoolCoreSize", "1",
				"--mailer:withConnectionPoolMaxSize", "1",
				"--mailer:withConnectionPoolExpireAfterMillis", "60000"
		};
	}

	private String[] connectArguments(final int smtpPort) {
		return new String[] {
				"connect", "--daemon=require", "--daemon-instance=smtp-pool",
				"--mailer:withSMTPServer", "127.0.0.1", Integer.toString(smtpPort),
				"--mailer:withConnectionPoolCoreSize", "1",
				"--mailer:withConnectionPoolMaxSize", "1",
				"--mailer:withConnectionPoolExpireAfterMillis", "60000"
		};
	}

	private Invocation invoke(final boolean forceTcp, final String... arguments) throws Exception {
		return invoke(command(forceTcp, arguments));
	}

	private Invocation invoke(final List<String> command) throws Exception {
		final Path output = Files.createTempFile(stateRoot, "cli-", ".log");
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
		return new Invocation(process.exitValue(), text);
	}

	private List<String> command(final boolean forceTcp, final String[] arguments) {
		return command(forceTcp, arguments, System.getProperty("java.class.path"));
	}

	private List<String> command(final boolean forceTcp, final String[] arguments, final String classPath) {
		final List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add("-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot);
		if (forceTcp) {
			command.add("-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true");
		}
		command.add("-Dsimplejavamail.cli.daemon.console-child=true");
		command.add("-cp");
		command.add(classPath);
		command.add("org.simplejavamail.cli.SimpleJavaMail");
		command.addAll(List.of(arguments));
		return command;
	}

	private static String withoutBatchStack(final String classPath) {
		return Arrays.stream(classPath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
				.filter(entry -> {
					final String normalized = entry.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
					return !normalized.contains("/batch-module/")
							&& !normalized.contains("/smtp-connection-pool/")
							&& !normalized.contains("/clustered-object-pool/")
							&& !normalized.contains("/generic-object-pool/");
				})
				.collect(java.util.stream.Collectors.joining(File.pathSeparator));
	}

	private static String value(final String status, final String key) {
		return status.lines()
				.filter(line -> line.startsWith(key + "="))
				.map(line -> line.substring(key.length() + 1))
				.findFirst()
				.orElseThrow();
	}

	private static String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString();
	}

	private record Invocation(int exitCode, String output) {
	}
}
