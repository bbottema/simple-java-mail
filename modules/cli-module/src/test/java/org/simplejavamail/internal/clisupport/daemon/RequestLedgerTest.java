package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLedgerTest {
	@Test
	void duplicateUuidAttachesToOneResultAndChangedContentIsRejected() {
		final RequestLedger ledger = new RequestLedger();
		final UUID session = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final DaemonRequest original = new DaemonRequest(DaemonOperation.EXECUTE, session, requestId, Instant.now(),
				Path.of("work"), List.of("send", "--email:startingBlank"));

		final RequestLedger.Registration first = ledger.register(original);
		final RequestLedger.Registration duplicate = ledger.register(original);
		assertThat(first.newlyRegistered()).isTrue();
		assertThat(duplicate.newlyRegistered()).isFalse();
		assertThat(duplicate.result()).isSameAs(first.result());

		final DaemonRequest changed = new DaemonRequest(DaemonOperation.EXECUTE, session, requestId, Instant.now(),
				Path.of("work"), List.of("send", "--email:withSubject", "changed"));
		assertThatThrownBy(() -> ledger.register(changed))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("different content");
	}

	@Test
	void resultBytePressureKeepsAReplayTombstoneInsteadOfReexecuting() {
		final RequestLedger ledger = new RequestLedger();
		final DaemonRequest firstRequest = request(UUID.randomUUID(), "first");
		final RequestLedger.Registration first = ledger.register(firstRequest);
		final String retainedOutput = "x".repeat(300_000);
		first.result().complete(CliExecutionResult.success(retainedOutput, ""));
		for (int i = 0; i < 39; i++) {
			ledger.register(request(UUID.randomUUID(), "request-" + i)).result()
					.complete(CliExecutionResult.success(retainedOutput, ""));
		}

		final RequestLedger.Registration replay = ledger.register(firstRequest);
		assertThat(replay.newlyRegistered()).isFalse();
		assertThat(replay.result().join().category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
		assertThat(replay.result().join().stderr()).contains("not executed again");
		assertThat(ledger.pruneExpiredEntriesAndCount()).isEqualTo(40);
		assertThat(ledger.retainedResultBytes()).isLessThanOrEqualTo(8L * 1024 * 1024);
	}

	@Test
	void recentTombstonesAreNotDiscardedToAdmitMoreThanTheEntryBound() {
		final RequestLedger ledger = new RequestLedger();
		for (int i = 0; i < 512; i++) {
			ledger.register(request(UUID.randomUUID(), "bounded-" + i)).result()
					.complete(CliExecutionResult.success("", ""));
		}

		assertThatThrownBy(() -> ledger.register(request(UUID.randomUUID(), "overflow")))
				.isInstanceOf(DaemonOverloadedException.class)
				.hasMessageContaining("ledger is full");
	}

	@Test
	void shutdownCanCompleteAcceptedButStuckRequestsAsAmbiguous() {
		final RequestLedger ledger = new RequestLedger();
		final RequestLedger.Registration registration = ledger.register(request(UUID.randomUUID(), "stuck"));

		ledger.completeIncomplete(new CliExecutionResult(CliExitCode.DAEMON_AMBIGUOUS, "", "outcome unknown"));

		assertThat(registration.result().join().category()).isEqualTo(CliExitCode.DAEMON_AMBIGUOUS);
		assertThat(registration.result().join().stderr()).contains("unknown");
	}

	private static DaemonRequest request(final UUID requestId, final String subject) {
		return new DaemonRequest(DaemonOperation.EXECUTE, UUID.randomUUID(), requestId, Instant.now(), Path.of("work"),
				List.of("send", "--email:withSubject", subject));
	}
}
