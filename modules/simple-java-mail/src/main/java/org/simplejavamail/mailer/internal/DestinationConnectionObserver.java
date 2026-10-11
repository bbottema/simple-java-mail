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

	DestinationConnectionObserver(final ConnectionRecovery recovery, final String destinationDescription) {
		this.recovery = recovery;
		this.destinationDescription = SmtpDiagnosticText.display(destinationDescription);
	}

	@Override
	public boolean isBackgroundConnectionAllowed() {
		return recovery.allowsBackgroundConnections();
	}

	void reportFailoverContinuation() {
		if (recovery.markFailoverReported()) {
			LOGGER.info("Continuing through the configured failover route after the connection failure for {}", destinationDescription);
		}
	}

	@Override
	public Attempt connectionStarting() {
		final ConnectionRecovery.Observation observation = recovery.observeConnection();
		return new Attempt() {
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
				if (observation.recordFailedConnection(evidence)) {
					LOGGER.error("SMTP connection incident for {}: {}. Check this destination's connection configuration and server availability.",
							destinationDescription, evidence.explanation());
				} else {
					LOGGER.debug("SMTP connection recheck did not recover {}: {}", destinationDescription, evidence.explanation());
				}
			}
		};
	}
}
