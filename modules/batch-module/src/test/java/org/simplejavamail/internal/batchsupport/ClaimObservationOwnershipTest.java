package org.simplejavamail.internal.batchsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.internal.batchsupport.PoolConnectionObserver;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.smtpconnectionpool.SmtpTransportLease;
import org.simplejavamail.smtpconnectionpool.SmtpTransportSelection;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Timeout(20)
class ClaimObservationOwnershipTest {

	@Test
	void cancelledPhysicalSetupRetiresTheClaimAssociationBeforeReturning() throws Exception {
		final PoolSettings settings = PoolSettings.from(BatchTransportPoolConfiguration.builder()
				.withCorePoolSize(0).withMaxPoolSize(1).withClaimTimeoutMillis(1000).withExpireAfterMillis(0).build());
		final BatchTransportEngine<String> engine = new BatchTransportEngine<>(settings);
		final Session session = mock(Session.class);
		final Transport transport = mock(Transport.class);
		when(session.getProperties()).thenReturn(new Properties());
		when(session.getTransport()).thenReturn(transport);
		final PoolConnectionObserver.Attempt observation = mock(PoolConnectionObserver.Attempt.class);
		try (MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
			doAnswer(invocation -> { control.requestCancellation(); return null; }).when(transport).connect();
			engine.register("cluster", session, settings);
			final SmtpTransportSelection selected = engine.select("cluster", session, control);
			final PoolConnectionObserver observer = new PoolConnectionObserver() {
				@Override public Attempt connectionStarting() { return observation; }
			};
			assertThatThrownBy(() -> engine.claimSelected("cluster", selected, control, observer))
					.isInstanceOf(MailSendCancelledException.class);
			assertNoClaimAssociations(engine);
			verify(observation).abandoned();
			verify(observation, never()).connected();
			verify(observation, never()).failed(any());
			verify(transport).close();
		} finally {
			engine.shutdown().get(5, SECONDS);
		}
	}

	@Test
	void physicalClaimUsesItsScopedObserverAndRemovesItAfterSuccessFailureAndCapacityTimeout() throws Exception {
		final PoolSettings settings = PoolSettings.from(BatchTransportPoolConfiguration.builder()
				.withCorePoolSize(0).withMaxPoolSize(1).withClaimTimeoutMillis(1000).withExpireAfterMillis(0).build());
		final BatchTransportEngine<String> engine = new BatchTransportEngine<>(settings);
		final Session session = mock(Session.class);
		final Transport transport = mock(Transport.class);
		when(session.getProperties()).thenReturn(new Properties());
		when(session.getTransport()).thenReturn(transport);
		when(transport.isConnected()).thenReturn(true);
		final AtomicInteger independentStarts = new AtomicInteger();
		final AtomicInteger scopedStarts = new AtomicInteger();
		final PoolConnectionObserver independent = observer(independentStarts);
		final PoolConnectionObserver scoped = observer(scopedStarts);
		try {
			engine.register("cluster", session, settings, independent);
			final SmtpTransportSelection selected = engine.select("cluster", session, null);
			final SmtpTransportLease held = engine.claimSelected("cluster", selected, null, scoped);
			assertThat(scopedStarts).hasValue(1);
			assertThat(independentStarts).hasValue(0);
			assertNoClaimAssociations(engine);
			try {
				assertThatThrownBy(() -> engine.claimSelected("cluster", selected, null, scoped)).isInstanceOf(BatchTransportException.class);
				assertNoClaimAssociations(engine);
				assertThat(scopedStarts).hasValue(1); // Waiting for capacity did not create a connection observation.
			} finally {
				engine.release(held);
			}
			final SmtpTransportLease reused = engine.claimSelected("cluster", selected, null, scoped);
			assertThat(scopedStarts).hasValue(1); // Healthy reuse does not manufacture a physical connection attempt.
			engine.invalidate(reused);
			doThrow(new MessagingException("fixture connect failure")).when(transport).connect();
			assertThatThrownBy(() -> engine.claimSelected("cluster", selected, null, scoped)).isInstanceOf(BatchTransportException.class);
			assertNoClaimAssociations(engine);
			assertThat(scopedStarts).hasValue(2);
			doNothing().when(transport).connect();
			final SmtpTransportLease unrelated = engine.claimSelected("cluster", selected, null);
			engine.release(unrelated);
			assertThat(independentStarts).hasValue(1);
			assertThat(scopedStarts).hasValue(2);
		} finally {
			engine.shutdown().get(5, SECONDS);
		}
	}

	private static PoolConnectionObserver observer(final AtomicInteger starts) {
		return new PoolConnectionObserver() {
			@Override public Attempt connectionStarting() {
				starts.incrementAndGet();
				return Attempt.NONE;
			}
		};
	}

	/** Internal resource-leak assertion; public-boundary behavior remains in the unchanged Stage A suite. */
	private static void assertNoClaimAssociations(final BatchTransportEngine<?> engine) throws Exception {
		final Field field = BatchTransportEngine.class.getDeclaredField("connectionObserverByClaim");
		field.setAccessible(true);
		assertThat((Map<?, ?>) field.get(engine)).isEmpty();
	}
}
