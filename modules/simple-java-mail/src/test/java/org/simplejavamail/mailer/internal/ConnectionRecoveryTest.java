package org.simplejavamail.mailer.internal;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.mailer.config.FailoverPolicy;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class ConnectionRecoveryTest {
	@ParameterizedTest
	@CsvSource({"false,false", "false,true", "true,false", "true,true"})
	void concurrentSuccessWinsOverFailureInEitherCompletionAndStartOrder(final boolean successFinishesFirst, final boolean successStartsFirst) {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.enableCoordinatedFailover();
		final ConnectionRecovery.Observation first = recovery.observeConnection();
		final ConnectionRecovery.Observation second = recovery.observeConnection();
		final ConnectionRecovery.Observation successful = successStartsFirst ? first : second;
		final ConnectionRecovery.Observation failed = successStartsFirst ? second : first;
		final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException());
		if (successFinishesFirst) {
			assertThat(successful.recordSuccessfulConnection()).isFalse();
			assertThat(failed.recordFailedConnection(refused)).isFalse();
		} else {
			assertThat(failed.recordFailedConnection(refused)).isTrue();
			assertThat(successful.recordSuccessfulConnection()).isTrue();
		}
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		try (ConnectionRecovery.Visit next = recovery.openVisit(FailoverPolicy.builder().build())) {
			assertThat(next.shouldAttemptConnection).isTrue();
			assertThat(next.cachedFailure).isNull();
		}
	}

	@Test
	void startingADueRecheckRetiresResultsFromEarlierConnectionWork() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		final ConnectionRecovery.Observation oldSuccess = recovery.observeConnection();
		final ConnectionRecovery.Observation oldFailure = recovery.observeConnection();
		clock.set(10);
		try (ConnectionRecovery.Visit recheck = recovery.openVisit(policy)) {
			assertThat(recheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.Observation current = recovery.observeConnection();
			assertThat(oldSuccess.recordSuccessfulConnection()).isFalse();
			assertThat(oldFailure.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException()))).isFalse();
			assertThat(recovery.allowsBackgroundConnections()).isFalse();
			assertThat(current.recordSuccessfulConnection()).isTrue();
		}
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
	}

	@Test
	void workStartedAfterRecoveryDoesNotBelongToTheEarlierRecheck() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		clock.set(10);
		final ConnectionRecovery.Visit recheck = recovery.openVisit(policy);
		recovery.observeConnection().recordSuccessfulConnection();
		final ConnectionRecovery.Observation newConnection = recovery.observeConnection();
		recheck.close();
		assertThat(newConnection.recordFailedConnection(new ConnectionSetupFailure(new ConnectException()))).isTrue();
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
	}

	@Test
	void aRecoveredVisitCannotBlockTheNextIncidentsDueRecheck() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		clock.set(10);
		try (ConnectionRecovery.Visit earlierVisit = recovery.openVisit(policy)) {
			recovery.observeConnection().recordSuccessfulConnection();
			recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
			clock.set(20);
			try (ConnectionRecovery.Visit nextRecheck = recovery.openVisit(policy)) {
				assertThat(nextRecheck.shouldAttemptConnection).isTrue();
				final ConnectionRecovery.Observation nextConnection = recovery.observeConnection();
				earlierVisit.close();
				assertThat(nextConnection.recordSuccessfulConnection()).isTrue();
			}
		}
	}

	@Test
	void oneObservationCannotPublishBothFailureAndSuccess() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.enableCoordinatedFailover();
		final ConnectionRecovery.Observation connection = recovery.observeConnection();
		assertThat(connection.recordFailedConnection(new ConnectionSetupFailure(new ConnectException()))).isTrue();
		assertThat(connection.recordSuccessfulConnection()).isFalse();
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
	}

	@Test
	void oldSuccessCannotEraseAFailureThatBeganAfterRecovery() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.enableCoordinatedFailover();
		final ConnectionRecovery.Observation oldSuccess = recovery.observeConnection();
		recovery.observeConnection().recordSuccessfulConnection();
		final ConnectionSetupFailure newFailure = new ConnectionSetupFailure(new ConnectException());
		recovery.observeConnection().recordFailedConnection(newFailure);
		assertThat(oldSuccess.recordSuccessfulConnection()).isFalse();
		try (ConnectionRecovery.Visit waiting = recovery.openVisit(FailoverPolicy.builder().build())) {
			assertThat(waiting.shouldAttemptConnection).isFalse();
			assertThat(waiting.cachedFailure).isSameAs(newFailure);
		}
	}

	@Test
	void configuredRoutesSuppressBackgroundRetriesEvenBeforeTheFirstSend() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		recovery.enableCoordinatedFailover();
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
		recovery.observeConnection().recordSuccessfulConnection();
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
	}

	@Test
	void strictPolicyCanRecheckItsPrimaryWithoutPermittingAuthenticationFailover() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException()));
		assertThatThrownBy(() -> recovery.openVisit(policy)).isInstanceOf(MailerException.class);
		clock.set(10);
		try (ConnectionRecovery.Visit visit = recovery.openVisit(policy)) {
			assertThat(visit.shouldAttemptConnection).isTrue();
			assertThatThrownBy(() -> recovery.openVisit(policy)).isInstanceOf(MailerException.class);
			recovery.observeConnection().recordSuccessfulConnection();
		}
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isTrue();
	}

	@Test
	void withoutFailoverBackgroundRecoveryRemainsEnabledAndIncidentLoggingIsDeduplicated() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		assertThat(recovery.openVisit(null).shouldAttemptConnection).isTrue();
		assertThat(recovery.markFailoverReported()).isTrue();
		assertThat(recovery.markFailoverReported()).isFalse();
		recovery.observeConnection().recordSuccessfulConnection();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		assertThat(recovery.markFailoverReported()).isTrue();
	}

	@Test
	void onlyOneDueRecheckRunsAndItsCancellationFencesLateResults() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
		clock.set(10);
		final ConnectionRecovery.Visit cancelledRecheck = recovery.openVisit(policy);
		assertThat(cancelledRecheck.shouldAttemptConnection).isTrue();
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		final ConnectionRecovery.Observation abandonedConnection = recovery.observeConnection();
		cancelledRecheck.close();
		try (ConnectionRecovery.Visit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.Observation replacementConnection = recovery.observeConnection();
			abandonedConnection.recordSuccessfulConnection();
			assertThat(recovery.allowsBackgroundConnections()).isFalse();
			assertThat(replacementConnection.recordSuccessfulConnection()).isTrue();
			assertThat(recovery.allowsBackgroundConnections()).isTrue();
		}
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isTrue();
	}

	@Test
	void cancellingAfterAFailedRetryFencesItsLateSuccessWithoutDiscardingTheReplacementFailure() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(2)
				.withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("initial outage")));
		clock.set(10);
		final ConnectionRecovery.Visit cancelledRecheck = recovery.openVisit(policy);
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("first retry failed")));
		final ConnectionRecovery.Observation lateRetryConnection = recovery.observeConnection();
		cancelledRecheck.close();

		clock.set(20);
		final ConnectionSetupFailure replacementFailure = new ConnectionSetupFailure(new ConnectException("replacement failed"));
		try (ConnectionRecovery.Visit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.Observation replacementConnection = recovery.observeConnection();
			assertThat(lateRetryConnection.recordSuccessfulConnection()).isFalse();
			replacementConnection.recordFailedConnection(replacementFailure);
		}
		try (ConnectionRecovery.Visit waitingVisit = recovery.openVisit(policy)) {
			assertThat(waitingVisit.shouldAttemptConnection).isFalse();
			assertThat(waitingVisit.cachedFailure).isSameAs(replacementFailure);
		}
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
	}

	@Test
	void cancellingAfterAFailedRetryFencesItsLateFailureWithoutDiscardingTheReplacementSuccess() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(2)
				.withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("initial outage")));
		clock.set(10);
		final ConnectionRecovery.Visit cancelledRecheck = recovery.openVisit(policy);
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("first retry failed")));
		final ConnectionRecovery.Observation lateRetryConnection = recovery.observeConnection();
		cancelledRecheck.close();

		clock.set(20);
		try (ConnectionRecovery.Visit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.Observation replacementConnection = recovery.observeConnection();
			// Closing the retired owner again must not cancel the replacement's observation.
			cancelledRecheck.close();
			lateRetryConnection.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException("cancelled retry failed late")));
			assertThat(replacementConnection.recordSuccessfulConnection()).isTrue();
		}
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		try (ConnectionRecovery.Visit healthyVisit = recovery.openVisit(policy)) {
			assertThat(healthyVisit.shouldAttemptConnection).isTrue();
			assertThat(healthyVisit.cachedFailure).isNull();
		}
	}

	@Test
	void backoffIsCappedAndEachPolicyIsAppliedToSharedFacts() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10))
				.withPrimaryRecheckBackoff(3, Duration.ofNanos(50)).build();
		final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException());
		recovery.observeConnection().recordFailedConnection(refused);
		clock.set(10);
		try (ConnectionRecovery.Visit ignoredVisit = recovery.openVisit(policy)) {
			recovery.observeConnection().recordFailedConnection(refused);
		}
		clock.set(39);
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		clock.set(40);
		try (ConnectionRecovery.Visit recheckVisit = recovery.openVisit(policy)) {
			assertThat(recheckVisit.shouldAttemptConnection).isTrue();
			recovery.observeConnection().recordFailedConnection(refused);
		}
		clock.set(89);
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		clock.set(90);
		try (ConnectionRecovery.Visit cappedRecheckVisit = recovery.openVisit(policy)) {
			assertThat(cappedRecheckVisit.shouldAttemptConnection).isTrue();
		}

		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException("credentials")));
		assertThatThrownBy(() -> recovery.openVisit(policy)).isInstanceOf(MailerException.class).hasMessageContaining("No new connection");
		assertThat(recovery.openVisit(FailoverPolicy.builder().withFailoverOnAuthenticationFailure(true).build()).shouldAttemptConnection).isFalse();
	}

	@Test
	void onlyFailedRecheckVisitsAdvanceBackoffAndSuccessfulRecoveryResetsIt() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryConnectionAttempts(3)
				.withPrimaryRecheckInterval(Duration.ofNanos(10)).withPrimaryRecheckBackoff(2, Duration.ofNanos(50)).build();
		final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException());
		try (ConnectionRecovery.Visit initialVisit = recovery.openVisit(policy)) {
			assertThat(initialVisit.shouldAttemptConnection).isTrue();
			for (int attempt = 0; attempt < policy.getPrimaryConnectionAttempts(); attempt++) {
				recovery.observeConnection().recordFailedConnection(refused);
			}
		}
		assertRecheckEligibility(recovery, policy, clock, 9, false);
		final long[] recheckTimes = {10, 30, 70, 120};
		final long[] nextIntervals = {20, 40, 50, 50};
		for (int recheckNumber = 0; recheckNumber < recheckTimes.length; recheckNumber++) {
			final long recheckAt = recheckTimes[recheckNumber];
			clock.set(recheckAt);
			try (ConnectionRecovery.Visit recheck = recovery.openVisit(policy)) {
				assertThat(recheck.shouldAttemptConnection).isTrue();
				for (int attempt = 0; attempt < policy.getPrimaryConnectionAttempts(); attempt++) {
					recovery.observeConnection().recordFailedConnection(refused);
				}
			}
			assertRecheckEligibility(recovery, policy, clock, recheckAt + nextIntervals[recheckNumber] - 1, false);
		}
		clock.set(170);
		try (ConnectionRecovery.Visit recovered = recovery.openVisit(policy)) {
			assertThat(recovered.shouldAttemptConnection).isTrue();
			recovery.observeConnection().recordSuccessfulConnection();
		}
		recovery.observeConnection().recordFailedConnection(refused);
		assertRecheckEligibility(recovery, policy, clock, 179, false);
		assertRecheckEligibility(recovery, policy, clock, 180, true);
	}

	@Test
	void cancelledRecheckRetriesCannotAdvanceTheNextRecoveryInterval() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10))
				.withPrimaryRecheckBackoff(2, Duration.ofNanos(100)).build();
		final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException());
		recovery.observeConnection().recordFailedConnection(refused);
		clock.set(10);
		final ConnectionRecovery.Visit cancelled = recovery.openVisit(policy);
		recovery.observeConnection().recordFailedConnection(refused);
		final ConnectionRecovery.Observation lateRetry = recovery.observeConnection();
		cancelled.close();
		clock.set(20);
		lateRetry.recordFailedConnection(refused);
		assertRecheckEligibility(recovery, policy, clock, 29, false);
		assertRecheckEligibility(recovery, policy, clock, 30, true);
	}

	private static void assertRecheckEligibility(final ConnectionRecovery recovery, final FailoverPolicy policy,
			final AtomicLong clock, final long now, final boolean eligible) {
		clock.set(now);
		try (ConnectionRecovery.Visit visit = recovery.openVisit(policy)) {
			assertThat(visit.shouldAttemptConnection).as("recheck eligibility at %s", now).isEqualTo(eligible);
		}
	}

	@Test
	void staleFailureCannotReplaceSuccessfulRecheckAndIndependentRegistriesRemainIndependent() {
		final ConnectionRecovery recovery = new ConnectionRecovery(() -> 100);
		final ConnectionRecovery.Observation staleConnection = recovery.observeConnection();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		recovery.observeConnection().recordSuccessfulConnection();
		staleConnection.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException()));
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		assertThat(new ConnectionRecovery().allowsBackgroundConnections()).isTrue();
	}
}
