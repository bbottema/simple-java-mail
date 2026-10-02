package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTransportSelectionTest {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@SuppressWarnings("unchecked")
	void selectionRejectsChangedOwnershipButNotAnUnrelatedRegistration(final boolean replaceSelected) throws Exception {
		final BatchSupport batch = new BatchSupport();
		final UUID cluster = UUID.randomUUID();
		final Session selectedSession = session();
		final Session otherSession = session();
		final SendingAllowance original = mock(SendingAllowance.class);
		final SendingAllowance replacement = mock(SendingAllowance.class);
		try {
			batch.registerToCluster(configuration(), cluster, selectedSession, original);
			final Field engineField = BatchSupport.class.getDeclaredField("batchTransportEngine");
			engineField.setAccessible(true);
			final BatchTransportEngine<UUID> engine = spy((BatchTransportEngine<UUID>) engineField.get(batch));
			engineField.set(batch, engine);
			doAnswer(invocation -> {
				if (replaceSelected) {
					batch.shutdownConnectionPools(selectedSession).get(5, SECONDS);
					batch.registerToCluster(configuration(), cluster, selectedSession, replacement);
				} else {
					batch.registerToCluster(configuration(), cluster, otherSession, replacement);
				}
				return invocation.callRealMethod();
			}).when(engine).select(cluster, selectedSession, null);
			if (replaceSelected) {
				assertThatThrownBy(() -> batch.selectTransport(cluster, selectedSession, true, null))
						.hasMessageContaining("changed while selecting").hasMessageContaining("No email was submitted");
			} else {
				assertThat(batch.selectTransport(cluster, selectedSession, true, null).getSendingAllowance()).isSameAs(original);
			}
			verify(selectedSession, never()).getTransport();
		} finally {
			batch.shutdownConnectionPools(selectedSession).get(5, SECONDS);
			batch.shutdownConnectionPools(otherSession).get(5, SECONDS);
		}
	}

	@Test
	void oneSessionCanHaveIndependentLimitsInDifferentClustersButCannotReplaceALivePoolsLimits() throws Exception {
		final BatchSupport batch = new BatchSupport();
		final UUID firstCluster = UUID.randomUUID();
		final UUID secondCluster = UUID.randomUUID();
		final Session session = session();
		final SendingAllowance first = mock(SendingAllowance.class);
		final SendingAllowance second = mock(SendingAllowance.class);
		when(first.isEnabled()).thenReturn(true);
		try {
			batch.registerToCluster(configuration(), firstCluster, session, first);
			batch.registerToCluster(configuration(), secondCluster, session, second);
			final SelectedPoolTransport selected = batch.selectTransport(firstCluster, session, true, null);
			assertThat(selected.getSendingAllowance()).isSameAs(first);
			assertThat(batch.selectTransport(secondCluster, session, true, null).getSendingAllowance()).isSameAs(second);
			assertThatThrownBy(() -> batch.registerToCluster(configuration(), firstCluster, session, second))
					.hasMessageContaining("Reuse the Mailer").hasMessageContaining("separate Session");
			assertThat(batch.selectTransport(firstCluster, session, true, null).getSendingAllowance()).isSameAs(first);
			batch.shutdownConnectionPools(session).get(5, SECONDS);
			batch.registerToCluster(configuration(), firstCluster, session, second);
			assertThat(selected.getSendingAllowance()).isSameAs(first);
			assertThatThrownBy(() -> selected.acquireTransport(null)).isInstanceOf(BatchTransportException.class);
			assertThat(batch.selectTransport(firstCluster, session, true, null).getSendingAllowance()).isSameAs(second);
		} finally {
			batch.shutdownConnectionPools(session).get(5, SECONDS);
		}
	}

	@Test
	void selectedConfigurationIsKnownBeforeAnyTransportIsCreated() throws Exception {
		final BatchSupport batch = new BatchSupport();
		final UUID cluster = UUID.randomUUID();
		final Session first = session();
		final Session second = session();
		try {
			batch.registerToCluster(configuration(), cluster, first, mock(SendingAllowance.class));
			batch.registerToCluster(configuration(), cluster, second, mock(SendingAllowance.class));
			final SelectedPoolTransport one = batch.selectTransport(cluster, first, false, null);
			final SelectedPoolTransport two = batch.selectTransport(cluster, first, false, null);
			assertThat(one.getSession()).isSameAs(first);
			assertThat(two.getSession()).isSameAs(second);
			verify(first, never()).getTransport();
			verify(second, never()).getTransport();
			// Admission can finish in the opposite order without selecting either destination again.
			assertAcquiredFrom(two, second);
			assertAcquiredFrom(one, first);
			assertThat(batch.selectTransport(cluster, first, false, null).getSession()).isSameAs(first);
		} finally {
			batch.shutdownConnectionPools(first).get(5, SECONDS);
			batch.shutdownConnectionPools(second).get(5, SECONDS);
		}
	}

	@Test
	void cancellationBetweenSelectionAndClaimCreatesNothing() throws Exception {
		final BatchSupport batch = new BatchSupport();
		final UUID cluster = UUID.randomUUID();
		final Session session = session();
		try (MailSendControl control = new MailSendControl(null, mock(ScheduledExecutorService.class))) {
			batch.registerToCluster(configuration(), cluster, session, mock(SendingAllowance.class));
			final SelectedPoolTransport selected = batch.selectTransport(cluster, session, true, control);
			control.requestCancellation();
			assertThatThrownBy(() -> selected.acquireTransport(control)).isInstanceOf(MailSendCancelledException.class);
			verify(session, never()).getTransport();
			assertAcquiredFrom(selected, session);
		} finally {
			batch.shutdownConnectionPools(session).get(5, SECONDS);
		}
	}

	@Test
	void selectedRegistrationCannotBeRevivedAfterShutdown() throws Exception {
		final BatchSupport batch = new BatchSupport();
		final UUID cluster = UUID.randomUUID();
		final Session session = session();
		try {
			batch.registerToCluster(configuration(), cluster, session, mock(SendingAllowance.class));
			final SelectedPoolTransport selected = batch.selectTransport(cluster, session, true, null);
			batch.shutdownConnectionPools(session).get(5, SECONDS);
			assertThatThrownBy(() -> selected.acquireTransport(null)).isInstanceOf(BatchTransportException.class);
			batch.registerToCluster(configuration(), cluster, session, mock(SendingAllowance.class));
			assertThatThrownBy(() -> selected.acquireTransport(null)).isInstanceOf(BatchTransportException.class);
			verify(session, never()).getTransport();
			assertAcquiredFrom(batch.selectTransport(cluster, session, true, null), session);
		} finally {
			batch.shutdownConnectionPools(session).get(5, SECONDS);
		}
	}

	private static void assertAcquiredFrom(final SelectedPoolTransport selected, final Session expected) {
		final LifecycleDelegatingTransport lease = selected.acquireTransport(null);
		try {
			assertThat(lease.getSessionUsedToObtainTransport()).isSameAs(expected);
		} finally {
			lease.signalTransportUsed();
		}
	}

	private static Session session() throws Exception {
		final Session session = mock(Session.class);
		final Transport transport = mock(Transport.class);
		when(session.getProperties()).thenReturn(new Properties());
		when(session.getTransport()).thenReturn(transport);
		when(transport.isConnected()).thenReturn(true);
		return session;
	}

	private static OperationalConfig configuration() {
		final OperationalConfig config = mock(OperationalConfig.class);
		when(config.getConnectionPoolMaxSize()).thenReturn(1);
		when(config.getConnectionPoolClaimTimeoutMillis()).thenReturn(1000);
		when(config.getConnectionPoolExpireAfterMillis()).thenReturn(60000);
		when(config.getConnectionPoolExpireAfterCreationMillis()).thenReturn(null);
		when(config.getConnectionPoolLoadBalancingStrategy()).thenReturn(LoadBalancingStrategy.ROUND_ROBIN);
		when(config.getConnectionPoolClusterConfigs()).thenReturn(Collections.emptyMap());
		return config;
	}
}
