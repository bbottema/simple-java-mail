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
		final ConnectionRecovery.ConnectionRecoveryObservation first = recovery.observeConnection();
		final ConnectionRecovery.ConnectionRecoveryObservation second = recovery.observeConnection();
		final ConnectionRecovery.ConnectionRecoveryObservation successful = successStartsFirst ? first : second;
		final ConnectionRecovery.ConnectionRecoveryObservation failed = successStartsFirst ? second : first;
		final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException());
		if (successFinishesFirst) {
			assertThat(successful.recordSuccessfulConnection()).isFalse();
			assertThat(failed.recordFailedConnection(refused)).isEqualTo(ConnectionRecovery.ConnectionFailurePublication.IGNORED);
		} else {
			assertThat(failed.recordFailedConnection(refused)).isEqualTo(ConnectionRecovery.ConnectionFailurePublication.FIRST_INCIDENT);
			assertThat(successful.recordSuccessfulConnection()).isTrue();
		}
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		try (ConnectionRecovery.ConnectionRecoveryVisit next = recovery.openVisit(FailoverPolicy.builder().build())) {
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
		final ConnectionRecovery.ConnectionRecoveryObservation oldSuccess = recovery.observeConnection();
		final ConnectionRecovery.ConnectionRecoveryObservation oldFailure = recovery.observeConnection();
		clock.set(10);
		try (ConnectionRecovery.ConnectionRecoveryVisit recheck = recovery.openVisit(policy)) {
			assertThat(recheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.ConnectionRecoveryObservation current = recheck.observeConnection();
			assertThat(oldSuccess.recordSuccessfulConnection()).isFalse();
			assertThat(oldFailure.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException())))
					.isEqualTo(ConnectionRecovery.ConnectionFailurePublication.IGNORED);
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
		final ConnectionRecovery.ConnectionRecoveryVisit recheck = recovery.openVisit(policy);
		recovery.observeConnection().recordSuccessfulConnection();
		final ConnectionRecovery.ConnectionRecoveryObservation newConnection = recovery.observeConnection();
		recheck.close();
		assertThat(newConnection.recordFailedConnection(new ConnectionSetupFailure(new ConnectException())))
				.isEqualTo(ConnectionRecovery.ConnectionFailurePublication.FIRST_INCIDENT);
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
	}

	@Test
	void aRecoveredVisitCannotBlockTheNextIncidentsDueRecheck() {
		final AtomicLong clock = new AtomicLong();
		final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
		final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10)).build();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		clock.set(10);
		try (ConnectionRecovery.ConnectionRecoveryVisit earlierVisit = recovery.openVisit(policy)) {
			recovery.observeConnection().recordSuccessfulConnection();
			recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
			clock.set(20);
			try (ConnectionRecovery.ConnectionRecoveryVisit nextRecheck = recovery.openVisit(policy)) {
				assertThat(nextRecheck.shouldAttemptConnection).isTrue();
				final ConnectionRecovery.ConnectionRecoveryObservation nextConnection = nextRecheck.observeConnection();
				earlierVisit.close();
				assertThat(nextConnection.recordSuccessfulConnection()).isTrue();
			}
		}
	}

	@Test
	void oneObservationCannotPublishBothFailureAndSuccess() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.enableCoordinatedFailover();
		final ConnectionRecovery.ConnectionRecoveryObservation connection = recovery.observeConnection();
		assertThat(connection.recordFailedConnection(new ConnectionSetupFailure(new ConnectException())))
				.isEqualTo(ConnectionRecovery.ConnectionFailurePublication.FIRST_INCIDENT);
		assertThat(connection.recordSuccessfulConnection()).isFalse();
		assertThat(recovery.allowsBackgroundConnections()).isFalse();
	}

	@Test
	void oldSuccessCannotEraseAFailureThatBeganAfterRecovery() {
		final ConnectionRecovery recovery = new ConnectionRecovery();
		recovery.enableCoordinatedFailover();
		final ConnectionRecovery.ConnectionRecoveryObservation oldSuccess = recovery.observeConnection();
		recovery.observeConnection().recordSuccessfulConnection();
		final ConnectionSetupFailure newFailure = new ConnectionSetupFailure(new ConnectException());
		recovery.observeConnection().recordFailedConnection(newFailure);
		assertThat(oldSuccess.recordSuccessfulConnection()).isFalse();
		try (ConnectionRecovery.ConnectionRecoveryVisit waiting = recovery.openVisit(FailoverPolicy.builder().build())) {
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
		try (ConnectionRecovery.ConnectionRecoveryVisit visit = recovery.openVisit(policy)) {
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
		final ConnectionRecovery.ConnectionRecoveryVisit cancelledRecheck = recovery.openVisit(policy);
		assertThat(cancelledRecheck.shouldAttemptConnection).isTrue();
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		final ConnectionRecovery.ConnectionRecoveryObservation abandonedConnection = cancelledRecheck.observeConnection();
		cancelledRecheck.close();
		try (ConnectionRecovery.ConnectionRecoveryVisit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.ConnectionRecoveryObservation replacementConnection = replacementRecheck.observeConnection();
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
		final ConnectionRecovery.ConnectionRecoveryVisit cancelledRecheck = recovery.openVisit(policy);
		cancelledRecheck.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("first retry failed")));
		final ConnectionRecovery.ConnectionRecoveryObservation lateRetryConnection = cancelledRecheck.observeConnection();
		cancelledRecheck.close();

		clock.set(20);
		final ConnectionSetupFailure replacementFailure = new ConnectionSetupFailure(new ConnectException("replacement failed"));
		try (ConnectionRecovery.ConnectionRecoveryVisit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.ConnectionRecoveryObservation replacementConnection = replacementRecheck.observeConnection();
			assertThat(lateRetryConnection.recordSuccessfulConnection()).isFalse();
			replacementConnection.recordFailedConnection(replacementFailure);
		}
		try (ConnectionRecovery.ConnectionRecoveryVisit waitingVisit = recovery.openVisit(policy)) {
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
		final ConnectionRecovery.ConnectionRecoveryVisit cancelledRecheck = recovery.openVisit(policy);
		cancelledRecheck.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException("first retry failed")));
		final ConnectionRecovery.ConnectionRecoveryObservation lateRetryConnection = cancelledRecheck.observeConnection();
		cancelledRecheck.close();

		clock.set(20);
		try (ConnectionRecovery.ConnectionRecoveryVisit replacementRecheck = recovery.openVisit(policy)) {
			assertThat(replacementRecheck.shouldAttemptConnection).isTrue();
			final ConnectionRecovery.ConnectionRecoveryObservation replacementConnection = replacementRecheck.observeConnection();
			// Closing the retired owner again must not cancel the replacement's observation.
			cancelledRecheck.close();
			lateRetryConnection.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException("cancelled retry failed late")));
			assertThat(replacementConnection.recordSuccessfulConnection()).isTrue();
		}
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		try (ConnectionRecovery.ConnectionRecoveryVisit healthyVisit = recovery.openVisit(policy)) {
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
		try (ConnectionRecovery.ConnectionRecoveryVisit ignoredVisit = recovery.openVisit(policy)) {
			ignoredVisit.observeConnection().recordFailedConnection(refused);
		}
		clock.set(39);
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		clock.set(40);
		try (ConnectionRecovery.ConnectionRecoveryVisit recheckVisit = recovery.openVisit(policy)) {
			assertThat(recheckVisit.shouldAttemptConnection).isTrue();
			recheckVisit.observeConnection().recordFailedConnection(refused);
		}
		clock.set(89);
		assertThat(recovery.openVisit(policy).shouldAttemptConnection).isFalse();
		clock.set(90);
		try (ConnectionRecovery.ConnectionRecoveryVisit cappedRecheckVisit = recovery.openVisit(policy)) {
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
		try (ConnectionRecovery.ConnectionRecoveryVisit initialVisit = recovery.openVisit(policy)) {
			assertThat(initialVisit.shouldAttemptConnection).isTrue();
			for (int attempt = 0; attempt < policy.getPrimaryConnectionAttempts(); attempt++) {
				initialVisit.observeConnection().recordFailedConnection(refused);
			}
		}
		assertRecheckEligibility(recovery, policy, clock, 9, false);
		final long[] recheckTimes = {10, 30, 70, 120};
		final long[] nextIntervals = {20, 40, 50, 50};
		for (int recheckNumber = 0; recheckNumber < recheckTimes.length; recheckNumber++) {
			final long recheckAt = recheckTimes[recheckNumber];
			clock.set(recheckAt);
			try (ConnectionRecovery.ConnectionRecoveryVisit recheck = recovery.openVisit(policy)) {
				assertThat(recheck.shouldAttemptConnection).isTrue();
				for (int attempt = 0; attempt < policy.getPrimaryConnectionAttempts(); attempt++) {
					recheck.observeConnection().recordFailedConnection(refused);
				}
			}
			assertRecheckEligibility(recovery, policy, clock, recheckAt + nextIntervals[recheckNumber] - 1, false);
		}
		clock.set(170);
		try (ConnectionRecovery.ConnectionRecoveryVisit recovered = recovery.openVisit(policy)) {
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
		final ConnectionRecovery.ConnectionRecoveryVisit cancelled = recovery.openVisit(policy);
		cancelled.observeConnection().recordFailedConnection(refused);
		final ConnectionRecovery.ConnectionRecoveryObservation lateRetry = cancelled.observeConnection();
		cancelled.close();
		clock.set(20);
		lateRetry.recordFailedConnection(refused);
		assertRecheckEligibility(recovery, policy, clock, 29, false);
		assertRecheckEligibility(recovery, policy, clock, 30, true);
	}

	private static void assertRecheckEligibility(final ConnectionRecovery recovery, final FailoverPolicy policy,
			final AtomicLong clock, final long now, final boolean eligible) {
		clock.set(now);
		try (ConnectionRecovery.ConnectionRecoveryVisit visit = recovery.openVisit(policy)) {
			assertThat(visit.shouldAttemptConnection).as("recheck eligibility at %s", now).isEqualTo(eligible);
		}
	}

	@Test
	void staleFailureCannotReplaceSuccessfulRecheckAndIndependentRegistriesRemainIndependent() {
		final ConnectionRecovery recovery = new ConnectionRecovery(() -> 100);
		final ConnectionRecovery.ConnectionRecoveryObservation staleConnection = recovery.observeConnection();
		recovery.observeConnection().recordFailedConnection(new ConnectionSetupFailure(new ConnectException()));
		recovery.observeConnection().recordSuccessfulConnection();
		staleConnection.recordFailedConnection(new ConnectionSetupFailure(new AuthenticationFailedException()));
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		assertThat(new ConnectionRecovery().allowsBackgroundConnections()).isTrue();
	}
}
