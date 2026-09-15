package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.config.ConfigLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Keeps command status, output ownership and invocation-only authentication separate from Mailer configuration. */
class CliProbeCommandTest {
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void printsTheExactReportAndWaitsForTheSynchronousProbe(final boolean authenticate) {
		final SmtpConnectionReport report = report(authenticate);
		final MailerProvider.Lease lease = lease(report);
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease)) {
			final CliExecutionResult result = execute(environment, authenticate ? new String[]{"probe", "--authenticate"} : new String[]{"probe"});
			assertThat(result.exitCode()).isZero();
			assertThat(result.stdout()).isEqualTo(report + System.lineSeparator());
			assertThat(result.stderr()).isEmpty();
			verify(lease.mailer().sync()).probeConnection(authenticate);
			verify(lease.mailer(), never()).async();
			verify(lease).close();
		}
	}

	@ParameterizedTest
	@EnumSource(SmtpConnectionPhase.class)
	void retainsPartialReportsForEveryFailurePhase(final SmtpConnectionPhase phase) {
		final SmtpConnectionReport report = report(true).toBuilder().failurePhase(phase)
				.failureDescription("Safe diagnostic description").build();
		final MailerProvider.Lease lease = lease(report);
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease)) {
			final CliExecutionResult result = execute(environment, "probe", "--authenticate");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).isEqualTo(report + System.lineSeparator()).contains("Failure at " + phase);
			assertThat(result.stderr()).isEmpty();
			verify(lease).close();
		}
	}

	@Test
	void unsupportedReportsAreCommandFailuresEvenWithoutAnException() {
		final SmtpConnectionReport report = report(false).toBuilder().supported(false).connected(false)
				.warnings(List.of("This provider has no probe adapter.")).build();
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease(report))) {
			final CliExecutionResult result = execute(environment, "probe");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).contains("UNSUPPORTED", "This provider has no probe adapter.");
		}
	}

	@Test
	void missingRequestedAuthenticationIsNotReportedAsSuccess() {
		final SmtpConnectionReport report = report(true).toBuilder().authenticated(false).build();
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease(report))) {
			final CliExecutionResult result = execute(environment, "probe", "--authenticate");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).contains("Authentication: not completed");
		}
	}

	@Test
	void authenticationDoesNotChangeTheReusableMailerProfileOrLeakToLaterRequests() {
		final List<CliMailerProfile> profiles = new ArrayList<>();
		final MailerProvider.Lease lease = lease(report(false));
		when(lease.mailer().sync().probeConnection(anyBoolean())).thenAnswer(invocation -> report(invocation.getArgument(0)));
		try (CliExecutionEnvironment environment = environment((profile, factory) -> { profiles.add(profile); return lease; })) {
			assertThat(execute(environment, "probe").stdout()).contains("Authentication: not requested");
			assertThat(execute(environment, "probe", "--authenticate").stdout()).contains("Authentication: succeeded");
			assertThat(execute(environment, "probe").stdout()).contains("Authentication: not requested");
			assertThat(profiles).hasSize(3).containsOnly(profiles.get(0));
		}
	}

	@Test
	void helpAndInvalidArgumentsNeverAcquireAMailer() {
		final MailerProvider provider = mock(MailerProvider.class);
		try (CliExecutionEnvironment environment = environment(provider)) {
			assertThat(execute(environment, "--help").stdout()).contains("probe", "--authenticate");
			final CliExecutionResult help = execute(environment, "probe", "--help");
			assertThat(help.exitCode()).isZero();
			assertThat(help.stdout()).contains("--authenticate", "logging-only", "exit 0", "--mailer:withSMTPServer")
					.doesNotContain("--email:from");
			final CliExecutionResult optionHelp = execute(environment, "probe", "--authenticate--help");
			assertThat(optionHelp.exitCode()).isZero();
			assertThat(optionHelp.stdout().replaceAll("\\s+", " ")).contains("configured SMTP credentials", "Proxy authentication", "use core size 0");
			for (String[] arguments : List.of(new String[]{"send", "--authenticate"}, new String[]{"connect", "--authenticate"},
					new String[]{"validate", "--authenticate"}, new String[]{"probe", "--email:from", "sender@example.org"},
					new String[]{"probe", "--authenticate", "true"}, new String[]{"probe", "--mailer:async"})) {
				final CliExecutionResult invalid = execute(environment, arguments);
				assertThat(invalid.exitCode()).as(List.of(arguments).toString()).isEqualTo(CliExitCode.CLI_ERROR.code());
				assertThat(invalid.stdout()).isEmpty();
			}
			verifyNoInteractions(provider);
		}
	}

	@Test
	void oversizedReportsUseTheExistingBoundedOutputWithoutChangingTheExitCode() {
		final SmtpConnectionReport report = report(false).toBuilder()
				.warnings(Collections.nCopies(300, "Diagnostic note ".repeat(100))).build();
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease(report))) {
			final CliExecutionResult result = execute(environment, "probe");
			assertThat(result.exitCode()).isZero();
			assertThat(result.stdout()).startsWith("SMTP connection probe: SUCCESS").contains("[output truncated]");
			assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256 * 1024);
			assertThat(result.stderr()).isEmpty();
		}
	}

	@Test
	void alreadyPrintedReportSurvivesALeaseCleanupFailure() {
		final MailerProvider.Lease lease = lease(report(false));
		doThrow(new IllegalStateException("Unable to close the Mailer")).when(lease).close();
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease)) {
			final CliExecutionResult result = execute(environment, "probe");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).contains("SMTP connection probe: SUCCESS");
			assertThat(result.stderr()).contains("Unable to close the Mailer");
		}
	}

	@Test
	void directProbeFailuresKeepTheExistingExceptionExitContractAndReleaseTheLease() {
		final MailerProvider.Lease lease = lease(report(false));
		when(lease.mailer().sync().probeConnection(false)).thenThrow(new IllegalStateException("Mailer is shut down"));
		try (CliExecutionEnvironment environment = environment((profile, factory) -> lease)) {
			final CliExecutionResult result = execute(environment, "probe");
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).isEmpty();
			assertThat(result.stderr()).contains("Mailer is shut down");
			verify(lease).close();
		}
	}

	@Test
	void concurrentRequestsOwnTheirReportsAndWaitForLeaseRelease() throws Exception {
		final CountDownLatch bothProbing = new CountDownLatch(2);
		final CountDownLatch finish = new CountDownLatch(1);
		final AtomicInteger released = new AtomicInteger();
		final Mailer mailer = mock(Mailer.class);
		final Mailer.Sync sync = mock(Mailer.Sync.class);
		when(mailer.sync()).thenReturn(sync);
		when(sync.probeConnection(anyBoolean())).thenAnswer(invocation -> {
			bothProbing.countDown();
			assertThat(finish.await(15, SECONDS)).isTrue();
			return report(invocation.getArgument(0));
		});
		final MailerProvider provider = (profile, factory) -> new MailerProvider.Lease() {
			@Override public Mailer mailer() { return mailer; }
			@Override public void close() { released.incrementAndGet(); }
		};
		final ExecutorService requests = Executors.newFixedThreadPool(2);
		try (CliExecutionEnvironment environment = environment(provider)) {
			final Future<CliExecutionResult> anonymous = requests.submit(() -> execute(environment, "probe"));
			final Future<CliExecutionResult> authenticated = requests.submit(() -> execute(environment, "probe", "--authenticate"));
			assertThat(bothProbing.await(15, SECONDS)).isTrue();
			assertThat(anonymous.isDone()).isFalse();
			assertThat(authenticated.isDone()).isFalse();
			assertThat(released).hasValue(0);
			finish.countDown();
			assertThat(anonymous.get(5, SECONDS).stdout()).isEqualTo(report(false) + System.lineSeparator());
			assertThat(authenticated.get(5, SECONDS).stdout()).isEqualTo(report(true) + System.lineSeparator());
			assertThat(released).hasValue(2);
		} finally {
			finish.countDown();
			requests.shutdownNow();
		}
	}

	private static SmtpConnectionReport report(final boolean authenticate) {
		return SmtpConnectionReport.builder().host("smtp.example.org").port(587).protocol("smtp")
				.startedAt(Instant.EPOCH).completedAt(Instant.EPOCH.plusMillis(42)).supported(true).connected(true)
				.authenticationRequested(authenticate).authenticated(authenticate).warnings(List.of()).build();
	}

	private static MailerProvider.Lease lease(final SmtpConnectionReport report) {
		final Mailer mailer = mock(Mailer.class);
		final Mailer.Sync sync = mock(Mailer.Sync.class);
		when(mailer.sync()).thenReturn(sync);
		when(sync.probeConnection(anyBoolean())).thenReturn(report);
		final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
		when(lease.mailer()).thenReturn(mailer);
		return lease;
	}

	private static CliExecutionEnvironment environment(final MailerProvider provider) {
		return new CliExecutionEnvironment(ConfigLoader.builder().load(), provider);
	}

	private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
		return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
	}
}
