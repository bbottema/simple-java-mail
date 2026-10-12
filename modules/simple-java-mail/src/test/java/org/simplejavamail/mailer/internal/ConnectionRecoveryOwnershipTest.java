package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.config.FailoverPolicy;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.ConnectionRecovery.ConnectionFailurePublication.FIRST_INCIDENT;
import static org.simplejavamail.mailer.internal.ConnectionRecovery.ConnectionFailurePublication.IGNORED;

class ConnectionRecoveryOwnershipTest {

	private final AtomicLong clock = new AtomicLong();
	private final ConnectionRecovery recovery = new ConnectionRecovery(clock::get);
	private final FailoverPolicy policy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofNanos(10))
			.withPrimaryRecheckBackoff(2, Duration.ofNanos(100)).build();
	private final ConnectionSetupFailure refused = new ConnectionSetupFailure(new ConnectException("fixture refusal"));

	@ParameterizedTest
	@ValueSource(strings = {"012", "021", "102", "120", "201", "210"})
	void twoForegroundOwnersAndBackgroundFailureCannotOverruleAConcurrentSuccess(final String completionOrder) {
		try (ConnectionRecovery.ConnectionRecoveryVisit recheck = failedDestinationDueForRecheck();
			 ConnectionRecovery.ConnectionRecoveryVisit independent = recovery.openVisit(null)) {
			final ConnectionRecovery.ConnectionRecoveryObservation[] observations = {
					recheck.observeConnection(), independent.observeConnection(), recovery.observeConnection()
			};
			for (char step : completionOrder.toCharArray()) {
				final int index = step - '0';
				if (index == 1) {
					assertThat(observations[index].recordSuccessfulConnection()).isTrue();
				} else {
					observations[index].recordFailedConnection(refused);
				}
			}
			assertEligible();
			for (ConnectionRecovery.ConnectionRecoveryObservation observation : observations) {
				assertThat(observation.recordFailedConnection(refused)).isEqualTo(IGNORED);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"012", "021", "102", "120", "201", "210"})
	void cancelledRecheckCannotRetireIndependentOrBackgroundObservations(final String completionOrder) {
		final ConnectionRecovery.ConnectionRecoveryVisit recheck = failedDestinationDueForRecheck();
		recheck.observeConnection().recordFailedConnection(refused);
		try (ConnectionRecovery.ConnectionRecoveryVisit independent = recovery.openVisit(null)) {
			final ConnectionRecovery.ConnectionRecoveryObservation[] observations = {
					recheck.observeConnection(), independent.observeConnection(), recovery.observeConnection()
			};
			recheck.close();
			for (char step : completionOrder.toCharArray()) {
				final int index = step - '0';
				if (index == 0) {
					assertThat(observations[index].recordFailedConnection(refused)).isEqualTo(IGNORED);
				} else {
					observations[index].recordSuccessfulConnection();
				}
			}
			assertEligible();
		}
	}

	@Test
	void abandonedHandoffIsNotRecoveryAndCannotPublishLater() {
		try (ConnectionRecovery.ConnectionRecoveryVisit recheck = failedDestinationDueForRecheck()) {
			final ConnectionRecovery.ConnectionRecoveryObservation discarded = recheck.observeConnection();
			discarded.abandon();
			assertThat(discarded.recordSuccessfulConnection()).isFalse();
			assertThat(discarded.recordFailedConnection(refused)).isEqualTo(IGNORED);
			assertThat(recovery.allowsBackgroundConnections()).isFalse();
		}
		try (ConnectionRecovery.ConnectionRecoveryVisit replacement = recovery.openVisit(policy)) {
			assertThat(replacement.shouldAttemptConnection).isTrue();
		}
	}

	@Test
	void newConnectionInsideAnOlderOpenVisitUsesTheCurrentGenerationButRetainsItsOwnOwner() {
		final ConnectionRecovery.ConnectionRecoveryVisit older = failedDestinationDueForRecheck();
		recovery.observeConnection().recordSuccessfulConnection();
		assertThat(older.observeConnection().recordFailedConnection(refused)).isEqualTo(FIRST_INCIDENT);
		clock.set(20);
		try (ConnectionRecovery.ConnectionRecoveryVisit replacement = recovery.openVisit(policy)) {
			final ConnectionRecovery.ConnectionRecoveryObservation current = replacement.observeConnection();
			older.close();
			assertThat(current.recordSuccessfulConnection()).isTrue();
			assertEligible();
		}
	}

	private ConnectionRecovery.ConnectionRecoveryVisit failedDestinationDueForRecheck() {
		recovery.observeConnection().recordFailedConnection(refused);
		clock.set(10);
		return recovery.openVisit(policy);
	}

	private void assertEligible() {
		assertThat(recovery.allowsBackgroundConnections()).isTrue();
		try (ConnectionRecovery.ConnectionRecoveryVisit next = recovery.openVisit(policy)) {
			assertThat(next.shouldAttemptConnection).isTrue();
			assertThat(next.cachedFailure).isNull();
		}
	}
}
