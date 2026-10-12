package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.internal.util.SmtpDiagnosticText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shares physical setup observations between ordinary acquisition and background pool allocation, without logging credentials or provider text. */
final class DestinationConnectionObserver implements PoolConnectionObserver {

	private static final Logger LOGGER = LoggerFactory.getLogger(DestinationConnectionObserver.class);
	private final ConnectionRecovery recovery;
	private final String destinationDescription;
	private final boolean backgroundConnectionSetupAllowed;

	DestinationConnectionObserver(final ConnectionRecovery recovery, final String destinationDescription, final boolean backgroundConnectionSetupAllowed) {
		this.recovery = recovery;
		this.destinationDescription = SmtpDiagnosticText.display(destinationDescription);
		this.backgroundConnectionSetupAllowed = backgroundConnectionSetupAllowed;
	}

	@Override
	public boolean isBackgroundConnectionAllowed() {
		return backgroundConnectionSetupAllowed && recovery.allowsBackgroundConnections();
	}

	void reportFailoverContinuation() {
		if (recovery.markFailoverReported()) {
			LOGGER.info("Continuing through the configured failover route after the connection failure for {}", destinationDescription);
		}
	}

	@Override
	public Attempt connectionStarting() {
		return observe(recovery.observeConnection());
	}

	/** Routing supplies its owner explicitly; unrelated claims and background allocation use the unscoped observer. */
	PoolConnectionObserver forVisit(final ConnectionRecovery.ConnectionRecoveryVisit visit) {
		return new PoolConnectionObserver() {
			@Override
			public Attempt connectionStarting() {
				return observe(visit.observeConnection());
			}
		};
	}

	private Attempt observe(final ConnectionRecovery.ConnectionRecoveryObservation observation) {
		return new Attempt() {
			@Override
			public void abandoned() {
				observation.abandon();
			}

			@Override
			public void connected() {
				if (observation.recordSuccessfulConnection()) {
					LOGGER.info("SMTP connection recovered for {}; new sends can prefer this destination again", destinationDescription);
				}
			}

			@Override
			public void failed(final Throwable failure) {
				final ConnectionSetupFailure evidence = failure instanceof ConnectionSetupFailure
						? (ConnectionSetupFailure) failure : new ConnectionSetupFailure(failure);
				final ConnectionRecovery.ConnectionFailurePublication publication = observation.recordFailedConnection(evidence);
				if (publication == ConnectionRecovery.ConnectionFailurePublication.FIRST_INCIDENT) {
					LOGGER.error("SMTP connection incident for {}: {}. Check this destination's connection configuration and server availability.",
							destinationDescription, evidence.explanation());
				} else if (publication == ConnectionRecovery.ConnectionFailurePublication.REPEATED_FAILURE) {
					LOGGER.debug("SMTP connection recheck did not recover {}: {}", destinationDescription, evidence.explanation());
				}
			}
		};
	}
}
