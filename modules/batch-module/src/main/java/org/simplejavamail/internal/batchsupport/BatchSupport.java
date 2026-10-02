package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;
import org.simplejavamail.smtpconnectionpool.SmtpTransportSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * This class only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class BatchSupport implements BatchModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchSupport.class);

	// no need to make this static, because this module itself is already static in the ModuleLoader
	@Nullable private BatchTransportEngine<UUID> batchTransportEngine;
	// Retained as a direct field for diagnostics and compatibility with existing internal tests.
	@Nullable private SmtpConnectionPoolClustered<UUID> smtpConnectionPool;
	private final Map<UUID, Map<Session, SendingAllowance>> sendingAllowances = new HashMap<>();

	/**
	 * @see BatchModule#registerToCluster(OperationalConfig, UUID, Session, SendingAllowance)
	 */
	@Override
	public synchronized void registerToCluster(@NotNull final OperationalConfig operationalConfig, @NotNull final UUID clusterKey,
			@NotNull final Session session, @NotNull final SendingAllowance sendingAllowance) {
		requireCompatibleAllowance(clusterKey, session, sendingAllowance);
		ensureEngineInitialized(operationalConfig);
		if (requireNonNull(batchTransportEngine).register(clusterKey, session, PoolSettings.from(operationalConfig, clusterKey))) {
			LOGGER.warn("SMTP Connection pool cluster {} is already configured with pool defaults from the first Mailer instance in that cluster; "
					+ "ignoring later pool settings",
					clusterKey);
		}
		retainSendingAllowance(clusterKey, session, sendingAllowance);
	}

	private void requireCompatibleAllowance(final UUID clusterKey, final Session session, final SendingAllowance allowance) {
		final Map<Session, SendingAllowance> cluster = sendingAllowances.get(clusterKey);
		final SendingAllowance existing = cluster == null ? null : cluster.get(session);
		if (existing != null && existing != allowance && (existing.isEnabled() || allowance.isEnabled())) {
			throw new IllegalArgumentException("This Session already has sending limits registered in this connection-pool cluster. "
					+ "Reuse the Mailer, share the same rate-limit group within one factory, or use a separate Session for the other SMTP configuration.");
		}
	}

	private void retainSendingAllowance(final UUID clusterKey, final Session session, final SendingAllowance allowance) {
		final Map<Session, SendingAllowance> existing = sendingAllowances.get(clusterKey);
		// Replace, never mutate, the per-cluster map: an in-flight selection retains its original ownership snapshot.
		final Map<Session, SendingAllowance> registered = existing == null ? new IdentityHashMap<>() : new IdentityHashMap<>(existing);
		registered.putIfAbsent(session, allowance);
		sendingAllowances.put(clusterKey, registered);
	}

	private void ensureEngineInitialized(@NotNull OperationalConfig operationalConfig) {
		if (batchTransportEngine == null) {
			LOGGER.warn("Starting SMTP connection pool cluster: JVM won't shutdown until the pool is manually closed with mailer.shutdownConnectionPool() "
					+ "(for each mailer in the cluster)");
			batchTransportEngine = new BatchTransportEngine<>(PoolSettings.from(operationalConfig, null));
			smtpConnectionPool = batchTransportEngine.getSmtpConnectionPool();
		}
	}

	/**
	 * @see BatchModule#acquireTransport(UUID, Session, boolean, MailSendControl)
	 */
	@NotNull
	@Override
	public LifecycleDelegatingTransport acquireTransport(@NotNull final UUID clusterKey, @NotNull final Session session, boolean stickySession,
			@Nullable final MailSendControl control) {
		final BatchTransportEngine<UUID> engine = requireNonNull(batchTransportEngine,
				"Connection pool used before it was initialized. This shouldn't be possible.");
		return new LifecycleDelegatingTransportImpl(engine, engine.claim(clusterKey, stickySession ? session : null, control), control);
	}

	/** @see BatchModule#selectTransport(UUID, Session, boolean, MailSendControl) */
	@NotNull
	@Override
	public SelectedPoolTransport selectTransport(@NotNull final UUID clusterKey, @NotNull final Session session, final boolean stickySession,
			@Nullable final MailSendControl control) {
		final BatchTransportEngine<UUID> engine = requireNonNull(batchTransportEngine,
				"Connection pool used before it was initialized. This shouldn't be possible.");
		final Map<Session, SendingAllowance> registered = sendingAllowancesSnapshot(clusterKey);
		final SmtpTransportSelection selected = engine.select(clusterKey, stickySession ? session : null, control);
		return new SelectedTransport(engine, clusterKey, selected, sendingAllowance(clusterKey, selected.getSession(), registered));
	}

	private synchronized Map<Session, SendingAllowance> sendingAllowancesSnapshot(final UUID clusterKey) {
		final Map<Session, SendingAllowance> registered = sendingAllowances.get(clusterKey);
		return registered == null ? emptyMap() : registered;
	}

	private synchronized SendingAllowance sendingAllowance(final UUID clusterKey, final Session session,
			final Map<Session, SendingAllowance> originalRegistrations) {
		final Map<Session, SendingAllowance> cluster = sendingAllowances.get(clusterKey);
		final SendingAllowance allowance = cluster == null ? null : cluster.get(session);
		if (allowance == null || originalRegistrations.get(session) != allowance) {
			throw new IllegalStateException("The selected SMTP configuration changed while selecting its connection pool. No email was submitted. "
					+ "Keep participating Mailers open until their sends have finished.");
		}
		return allowance;
	}

	/**
	 * @see BatchModule#shutdownConnectionPools(Session)
	 */
	@NotNull
	@Override
	public synchronized Future<Void> shutdownConnectionPools(@NotNull Session session) {
		if (batchTransportEngine == null) {
			LOGGER.warn("user requested connection pool shutdown, but there is no connection pool to shut down (yet)");
			return completedFuture(null);
		}
		final Future<Void> shutdown = batchTransportEngine.shutdownPool(session);
		removeSendingAllowances(session);
		return shutdown;
	}

	private void removeSendingAllowances(final Session session) {
		final Iterator<Map.Entry<UUID, Map<Session, SendingAllowance>>> clusters = sendingAllowances.entrySet().iterator();
		while (clusters.hasNext()) {
			final Map.Entry<UUID, Map<Session, SendingAllowance>> cluster = clusters.next();
			final Map<Session, SendingAllowance> remaining = new IdentityHashMap<>(cluster.getValue());
			remaining.remove(session);
			if (remaining.isEmpty()) {
				clusters.remove();
			} else {
				cluster.setValue(remaining);
			}
		}
	}

	private static final class SelectedTransport implements SelectedPoolTransport {
		private final BatchTransportEngine<UUID> engine;
		private final UUID clusterKey;
		private final SmtpTransportSelection selected;
		private final SendingAllowance sendingAllowance;

		private SelectedTransport(final BatchTransportEngine<UUID> engine, final UUID clusterKey, final SmtpTransportSelection selected,
				final SendingAllowance sendingAllowance) {
			this.engine = engine;
			this.clusterKey = clusterKey;
			this.selected = selected;
			this.sendingAllowance = sendingAllowance;
		}

		/** @see SelectedPoolTransport#getSendingAllowance() */
		@NotNull
		@Override
		public SendingAllowance getSendingAllowance() {
			return sendingAllowance;
		}

		/** @see SelectedPoolTransport#getSession() */
		@NotNull
		@Override
		public Session getSession() {
			return selected.getSession();
		}

		/** @see SelectedPoolTransport#acquireTransport(MailSendControl) */
		@NotNull
		@Override
		public LifecycleDelegatingTransport acquireTransport(@Nullable final MailSendControl control) {
			return new LifecycleDelegatingTransportImpl(engine, engine.claimSelected(clusterKey, selected, control), control);
		}
	}
}
