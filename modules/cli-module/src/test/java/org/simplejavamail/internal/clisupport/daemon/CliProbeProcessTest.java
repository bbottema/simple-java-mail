package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs actual CLI processes and local SMTP conversations; the fixture never accepts an email. */
class CliProbeProcessTest {
	private static final String USER = "probe-cli-user";
	private static final String PASSWORD = "probe-cli-secret";
	private static final String AUTH_RESPONSE = Base64.getEncoder().encodeToString((USER + "\0" + USER + "\0" + PASSWORD).getBytes(StandardCharsets.UTF_8));
	@TempDir Path stateRoot;

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void reportsAuthenticationFailuresAndRecoveryWithoutSendingOrRetainingAuthentication(final boolean daemon) throws Exception {
		final ExecutorService peers = Executors.newSingleThreadExecutor();
		try (ServerSocket smtp = smtpEndpoint()) {
			if (daemon) {
				assertSuccess(invoke("daemon", "start", "--daemon-instance=probe"));
			}
			try {
				for (int attempt = 0; attempt < 4; attempt++) {
					final boolean authenticate = attempt == 1 || attempt == 2;
					final boolean accepted = attempt != 1;
					final Future<?> conversation = peers.submit(() -> {
						inspectConnection(smtp, authenticate, accepted, null, null);
						return null;
					});
					final Invocation result = invoke(probeArguments(smtp.getLocalPort(), daemon, authenticate));
					conversation.get(5, SECONDS); // QUIT and socket closure happened before the command completed.
					assertThat(result.exitCode()).as(result.toString()).isEqualTo(accepted ? 0 : CliExitCode.COMMAND_FAILED.code());
					assertThat(result.stderr()).isEmpty();
					assertThat(result.stdout()).contains("SMTP connection probe: " + (accepted ? "SUCCESS" : "FAILED"),
							"Endpoint: 127.0.0.1:" + smtp.getLocalPort(), "SIZE=[4096]", "DSN", "Elapsed:");
					assertThat(result.stdout()).contains(!authenticate ? "Authentication: not requested"
							: accepted ? "Authentication: succeeded (PLAIN)" : "Failure at AUTHENTICATION");
					// Match the authentication replies, not digits that can also occur in the random port or elapsed time.
					assertThat(result.stdout() + result.stderr()).doesNotContain(USER, PASSWORD, AUTH_RESPONSE,
							"235 authenticated", "535 authentication rejected for");
				}
				if (daemon) {
					final Invocation status = invoke("daemon", "status", "--daemon-instance=probe");
					assertSuccess(status);
					assertThat(status.stdout()).contains("mailers=1");
				}
			} finally {
				if (daemon) {
					assertSuccess(invoke("daemon", "stop", "--daemon-instance=probe"));
				}
			}
		} finally {
			peers.shutdownNow();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void failedConnectionsKeepTheirReportAndArgumentErrorsDoNotBecomeProbeFailures(final boolean daemon) throws Exception {
		final int closedPort;
		try (ServerSocket unusedEndpoint = smtpEndpoint()) {
			closedPort = unusedEndpoint.getLocalPort();
		}
		if (daemon) {
			assertSuccess(invoke("daemon", "start", "--daemon-instance=probe"));
		}
		try {
			final Invocation failure = invoke(probeArguments(closedPort, daemon, false));
			assertThat(failure.exitCode()).as(failure.toString()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(failure.stdout()).contains("SMTP connection probe: FAILED", "Failure at CONNECT");
			assertThat(failure.stderr()).isEmpty();
			final Invocation invalid = invoke("probe", daemon ? "--daemon=require" : "--daemon=off",
					"--daemon-instance=probe", "--email:from", "sender@example.org");
			assertThat(invalid.exitCode()).as(invalid.toString()).isEqualTo(CliExitCode.CLI_ERROR.code());
			assertThat(invalid.stdout()).doesNotContain("SMTP connection probe:");
			assertThat(invalid.stderr()).contains("--email:from");
		} finally {
			if (daemon) {
				assertSuccess(invoke("daemon", "stop", "--daemon-instance=probe"));
			}
		}
	}

	@Test
	void concurrentDaemonProbesKeepReportsAndAuthenticationOnTheirOwnRequests() throws Exception {
		final CountDownLatch bothProbing = new CountDownLatch(2);
		final CountDownLatch finish = new CountDownLatch(1);
		final ExecutorService workers = Executors.newFixedThreadPool(4);
		try (ServerSocket firstEndpoint = smtpEndpoint(); ServerSocket secondEndpoint = smtpEndpoint()) {
			assertSuccess(invoke("daemon", "start", "--daemon-instance=probe"));
			try {
				final Future<?> firstPeer = workers.submit(() -> { inspectConnection(firstEndpoint, false, true, bothProbing, finish); return null; });
				final Future<?> secondPeer = workers.submit(() -> { inspectConnection(secondEndpoint, true, true, bothProbing, finish); return null; });
				final Future<Invocation> first = workers.submit(() -> invoke(probeArguments(firstEndpoint.getLocalPort(), true, false)));
				final Future<Invocation> second = workers.submit(() -> invoke(probeArguments(secondEndpoint.getLocalPort(), true, true)));
				assertThat(bothProbing.await(20, SECONDS)).isTrue();
				assertThat(first.isDone()).isFalse();
				assertThat(second.isDone()).isFalse();
				finish.countDown();
				final Invocation firstResult = first.get(10, SECONDS);
				final Invocation secondResult = second.get(10, SECONDS);
				firstPeer.get(5, SECONDS);
				secondPeer.get(5, SECONDS);
				assertSuccess(firstResult);
				assertSuccess(secondResult);
				assertThat(firstResult.stdout()).contains("127.0.0.1:" + firstEndpoint.getLocalPort(), "Authentication: not requested")
						.doesNotContain("127.0.0.1:" + secondEndpoint.getLocalPort(), "Authentication: succeeded");
				assertThat(secondResult.stdout()).contains("127.0.0.1:" + secondEndpoint.getLocalPort(), "Authentication: succeeded")
						.doesNotContain("127.0.0.1:" + firstEndpoint.getLocalPort(), "Authentication: not requested");
			} finally {
				finish.countDown();
				assertSuccess(invoke("daemon", "stop", "--daemon-instance=probe"));
			}
		} finally {
			finish.countDown();
			workers.shutdownNow();
		}
	}

	private static ServerSocket smtpEndpoint() throws Exception {
		final ServerSocket smtp = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
		smtp.setSoTimeout(20000);
		return smtp;
	}

	private static void inspectConnection(final ServerSocket smtp, final boolean authenticate, final boolean accepted,
			final CountDownLatch bothProbing, final CountDownLatch finish) throws Exception {
		try (Socket socket = smtp.accept()) {
			socket.setSoTimeout(15000);
			final BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
			final PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
			reply(writer, "220 local CLI probe fixture");
			assertThat(reader.readLine()).startsWith("EHLO ");
			if (bothProbing != null) {
				bothProbing.countDown();
				assertThat(finish.await(20, SECONDS)).isTrue();
			}
			reply(writer, "250-localhost\r\n250-SIZE 4096\r\n250-DSN\r\n250 AUTH PLAIN");
			if (authenticate) {
				assertThat(reader.readLine()).isEqualTo("AUTH PLAIN " + AUTH_RESPONSE);
				reply(writer, accepted ? "235 authenticated" : "535 authentication rejected for " + USER + " " + PASSWORD);
			}
			final String lastCommand = reader.readLine();
			if (accepted || lastCommand != null) {
				assertThat(lastCommand).isEqualTo("QUIT");
				reply(writer, "221 goodbye");
			}
			assertThat(reader.readLine()).isNull();
		}
	}

	private static void reply(final PrintWriter writer, final String response) {
		writer.print(response + "\r\n");
		writer.flush();
	}

	private static String[] probeArguments(final int port, final boolean daemon, final boolean authenticate) {
		final List<String> arguments = new ArrayList<>(List.of("probe", daemon ? "--daemon=require" : "--daemon=off", "--daemon-instance=probe",
				"--mailer:withSMTPServer", "127.0.0.1", Integer.toString(port), USER, PASSWORD,
				"--mailer:withTransportStrategy", "SMTP", "--mailer:withOpportunisticTLS", "false",
				"--mailer:withSessionTimeout", "5000", "--mailer:withConnectionPoolCoreSize", "0",
				"--mailer:withTransportModeLoggingOnly", "true"));
		if (authenticate) {
			arguments.add("--authenticate");
		}
		return arguments.toArray(new String[0]);
	}

	private Invocation invoke(final String... arguments) throws Exception {
		final Path stdout = Files.createTempFile(stateRoot, "stdout-", ".log");
		final Path stderr = Files.createTempFile(stateRoot, "stderr-", ".log");
		final List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", DaemonPaths.isWindows() ? "java.exe" : "java").toString(),
				"-Dlog4j.configurationFile=" + CliProbeProcessTest.class.getResource("/log4j2-probe-process.xml"),
				"-D" + DaemonPaths.STATE_DIRECTORY_PROPERTY + "=" + stateRoot,
				"-D" + DaemonPaths.FORCE_TCP_PROPERTY + "=true", "-Dsimplejavamail.cli.daemon.console-child=true",
				"-cp", System.getProperty("java.class.path"), "org.simplejavamail.cli.SimpleJavaMail"));
		command.addAll(List.of(arguments));
		final Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
		try {
			assertThat(process.waitFor(30, SECONDS)).as("CLI process completed").isTrue();
			return new Invocation(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, SECONDS);
			}
		}
	}

	private static void assertSuccess(final Invocation invocation) {
		assertThat(invocation.exitCode()).as(invocation.toString()).isZero();
	}

	private record Invocation(int exitCode, String stdout, String stderr) { }
}
