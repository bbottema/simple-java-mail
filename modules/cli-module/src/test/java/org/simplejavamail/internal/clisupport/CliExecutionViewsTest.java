package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CliExecutionViewsTest {

	@ParameterizedTest
	@ValueSource(strings = {"--mailer:async", "--mailer:async=true", "--mailer:async=false", "--mailer:async--help"})
	void removedModeOptionFailsBeforeAnyMailerIsAcquired(final String removedOption) {
		final MailerProvider provider = mock(MailerProvider.class);
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
			for (String command : new String[]{"send", "connect"}) {
				final CliExecutionResult result = execute(environment, command, removedOption);
				assertThat(result.exitCode()).isEqualTo(CliExitCode.CLI_ERROR.code());
				assertThat(result.stdout()).isEmpty();
				assertThat(result.stderr()).contains("--mailer:async was removed in 10.0.0.", "send and connect already wait for completion");
			}
			verifyNoInteractions(provider);
		}
	}

	@Test
	void generatedHelpDoesNotAdvertiseRemovedModeOrOptionHelp() {
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
			final CliExecutionResult result = execute(environment, "send", "--help");
			assertThat(result.exitCode()).isZero();
			assertThat(result.stdout()).doesNotContain("--mailer:async", "--mailer:async--help")
					.contains("--mailer:withAsyncQueueCapacity");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"send", "connect"})
	void directOperationFailuresKeepTheCommandFailureExitCodeAndReleaseTheLease(final String command) {
		final Mailer mailer = mock(Mailer.class);
		final Mailer.Sync sync = mock(Mailer.Sync.class);
		when(mailer.sync()).thenReturn(sync);
		final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
		when(lease.mailer()).thenReturn(mailer);
		final MailerProvider provider = (profile, factory) -> lease;
		final Answer<Object> failOperation = invocation -> { throw new IllegalStateException("Simulated SMTP failure"); };
		doAnswer(failOperation).when(sync).sendMail(any(Email.class));
		doAnswer(failOperation).when(sync).testConnection();
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
			final String[] arguments = command.equals("send")
					? new String[]{"send", "--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "receiver@example.org"}
					: new String[]{"connect"};
			final CliExecutionResult result = execute(environment, arguments);
			assertThat(result.exitCode()).isEqualTo(CliExitCode.COMMAND_FAILED.code());
			assertThat(result.stdout()).isEmpty();
			assertThat(result.stderr()).contains("Simulated SMTP failure");
			verify(lease).close();
			verify(mailer, never()).async();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"send", "connect"})
	void concurrentRequestsWaitOnTheirOwnSynchronousOperationBeforeReturningTheLease(final String command) throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final Mailer.Sync sync = mock(Mailer.Sync.class);
		when(mailer.sync()).thenReturn(sync);
		final CountDownLatch entered = new CountDownLatch(2);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger releasedLeases = new AtomicInteger();
		final MailerProvider provider = (profile, factory) -> new MailerProvider.Lease() {
			@Override public Mailer mailer() { return mailer; }
			@Override public void close() { releasedLeases.incrementAndGet(); }
		};
		final Answer<Object> blockOperation = invocation -> {
			assertThat(Thread.currentThread().getName()).startsWith("cli-request-");
			entered.countDown();
			assertThat(release.await(20, SECONDS)).isTrue();
			return command.equals("send") ? mock(MailSubmissionReceipt.class) : null;
		};
		doAnswer(blockOperation).when(sync).sendMail(any(Email.class));
		doAnswer(blockOperation).when(sync).testConnection();
		final AtomicInteger threadSequence = new AtomicInteger();
		final ExecutorService requests = Executors.newFixedThreadPool(2,
				runnable -> new Thread(runnable, "cli-request-" + threadSequence.incrementAndGet()));
		try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
			final String[] arguments = command.equals("send")
					? new String[]{"send", "--email:startingBlank", "--email:from", "sender@example.org", "--email:to", "receiver@example.org",
						"--email:withSubject", "CLI execution", "--email:withPlainText", "body"}
					: new String[]{"connect"};
			final Future<CliExecutionResult> first = requests.submit(() -> execute(environment, arguments));
			final Future<CliExecutionResult> second = requests.submit(() -> execute(environment, arguments));
			try {
				assertThat(entered.await(20, SECONDS)).isTrue();
				assertThat(first.isDone()).isFalse();
				assertThat(second.isDone()).isFalse();
				assertThat(releasedLeases).hasValue(0);
			} finally {
				release.countDown();
			}
			for (Future<CliExecutionResult> request : List.of(first, second)) {
				final CliExecutionResult result = request.get(5, SECONDS);
				assertThat(result.exitCode()).as(result.stderr()).isZero();
				assertThat(result.stdout()).isEmpty(); // no receipt printed by the CLI
				assertThat(result.stderr()).isEmpty();
			}
			assertThat(releasedLeases).hasValue(2);
			verify(mailer, times(2)).sync();
			verify(mailer, never()).async();
		} finally {
			release.countDown();
			requests.shutdownNow();
		}
	}

	private static CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
		return CliSupport.execute(arguments, Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
	}
}
