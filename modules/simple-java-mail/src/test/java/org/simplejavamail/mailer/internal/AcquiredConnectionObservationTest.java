package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;

import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the narrow boundary between physical setup and ownership of a usable lease, independently of routing. */
class AcquiredConnectionObservationTest {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void cancelledHandoffDoesNotPublishRecoveryAndReleasesExactlyOnce(final boolean releaseFails) throws Exception {
		final Session session = Session.getInstance(new Properties());
		final SelectedPoolTransport selected = mock(SelectedPoolTransport.class);
		final LifecycleDelegatingTransport lease = lease(session);
		final PoolConnectionObserver.Attempt observation = mock(PoolConnectionObserver.Attempt.class);
		final RuntimeException cleanupFailure = new IllegalStateException("fixture lease release failure");
		if (releaseFails) {
			doThrow(cleanupFailure).when(lease).signalTransportUsed();
		}
		try (MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
			when(selected.acquireTransport(any(), any())).thenAnswer(invocation -> {
				final PoolConnectionObserver scoped = invocation.getArgument(1);
				scoped.connectionStarting(lease.getTransport()).connected();
				verify(observation, never()).connected(); // Physical success alone does not establish a usable handoff.
				control.requestCancellation();
				return lease;
			});
			final Throwable failure = catchThrowable(() -> AcquiredMailTransport.acquire(session, selected, control, observer(observation),
					MailSendDiagnosticsRecorder.unobserved()));
			assertThat(failure).isInstanceOf(MailSendCancelledException.class);
			if (releaseFails) {
				assertThat(failure.getSuppressed()).containsExactly(cleanupFailure);
			} else {
				assertThat(failure.getSuppressed()).isEmpty();
			}
			verify(observation, never()).connected();
			verify(observation, never()).failed(any());
			verify(observation).abandoned();
			verify(lease).signalTransportUsed();
			verify(lease, never()).signalTransportFailed();
		}
	}

	@Test
	void healthyReusedLeasePublishesRecoveryWithoutInventingPhysicalSetup() throws Exception {
		final Session session = Session.getInstance(new Properties());
		final SelectedPoolTransport selected = mock(SelectedPoolTransport.class);
		final LifecycleDelegatingTransport lease = lease(session);
		final PoolConnectionObserver.Attempt observation = mock(PoolConnectionObserver.Attempt.class);
		when(selected.acquireTransport(any(), any())).thenReturn(lease);
		try (MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
			final AcquiredMailTransport acquired = AcquiredMailTransport.acquire(session, selected, control, observer(observation),
					MailSendDiagnosticsRecorder.unobserved());
			verify(observation).connected();
			verify(observation, never()).failed(any());
			verify(observation, never()).abandoned();
			acquired.close();
			acquired.close();
			verify(lease).signalTransportUsed();
			verify(lease, never()).signalTransportFailed();
		}
	}

	private static PoolConnectionObserver observer(final PoolConnectionObserver.Attempt observation) {
		return new PoolConnectionObserver() {
			@Override
			public Attempt connectionStarting() {
				return observation;
			}
		};
	}

	private static LifecycleDelegatingTransport lease(final Session session) {
		final LifecycleDelegatingTransport lease = mock(LifecycleDelegatingTransport.class);
		final Transport transport = mock(Transport.class);
		when(lease.getSessionUsedToObtainTransport()).thenReturn(session);
		when(lease.getTransport()).thenReturn(transport);
		return lease;
	}
}
