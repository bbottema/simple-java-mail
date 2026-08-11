package org.simplejavamail.internal.batchsupport;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.simplejavamail.batch.BatchTransportException;
import org.simplejavamail.batch.BatchTransportOperation;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPool;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;
import org.simplejavamail.smtpconnectionpool.SmtpTransportLease;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROVIDER_PROPERTY;

/** Shared transport-lease engine for the public facade and the reflection-loaded Mailer adapter. */
public final class BatchTransportEngine<K> {
	private static final String SMTP_POOL_PROTOCOL = "smtppool";

	private final Object lifecycleMonitor = new Object();
	private final SmtpConnectionPoolClustered<K> smtpConnectionPool;
	private final Map<K, PoolSettings> clusterSettings = new HashMap<>();
	private final Map<K, Set<Session>> registeredSessions = new HashMap<>();
	private final Set<SmtpTransportLease> activeLeases = Collections.newSetFromMap(new ConcurrentHashMap<SmtpTransportLease, Boolean>());
	private boolean claimsOpen = true;
	private boolean shutdownStarted;

	BatchTransportEngine(final PoolSettings defaultSettings) {
		smtpConnectionPool = new SmtpConnectionPoolClustered<>(requireNonNull(defaultSettings, "defaultSettings").toSmtpClusterConfig());
	}

	public BatchTransportEngine(final BatchTransportPoolConfiguration defaultConfiguration) {
		this(PoolSettings.from(requireNonNull(defaultConfiguration, "defaultConfiguration")));
	}

	public boolean register(final K clusterKey, final Session session,
			final BatchTransportPoolConfiguration configuration) {
		rejectProviderOwnedPool(requireNonNull(session, "session"));
		return registerValidated(clusterKey, session, PoolSettings.from(requireNonNull(configuration, "configuration")));
	}

	/** @return true when an earlier registration fixed different settings for this cluster */
	boolean register(final K clusterKey, final Session session, final PoolSettings settings) {
		rejectDeclaredProviderOwnedPool(requireNonNull(session, "session"));
		return registerValidated(clusterKey, session, settings);
	}

	private boolean registerValidated(final K clusterKey, final Session session, final PoolSettings settings) {
		requireNonNull(clusterKey, "clusterKey");
		requireNonNull(settings, "settings");
		bridgeOAuth2Properties(session);

		synchronized (lifecycleMonitor) {
			ensureClaimsOpen("register a Session");
			final PoolSettings existingSettings = clusterSettings.get(clusterKey);
			if (existingSettings == null) {
				smtpConnectionPool.registerResourceCluster(clusterKey, settings.<K>toSmtpClusterConfig().getConfigBuilder().build());
				clusterSettings.put(clusterKey, settings);
			}

			Set<Session> sessions = registeredSessions.get(clusterKey);
			if (sessions == null) {
				sessions = Collections.newSetFromMap(new IdentityHashMap<Session, Boolean>());
				registeredSessions.put(clusterKey, sessions);
			}
			final ResourceClusterAndPoolKey<K, Session> resourceKey = new ResourceClusterAndPoolKey<>(clusterKey, session);
			if (!smtpConnectionPool.isPoolRegistered(resourceKey)) {
				smtpConnectionPool.registerResourcePool(resourceKey);
			}
			sessions.add(session);
			return existingSettings != null && !existingSettings.equals(settings);
		}
	}

	public <T, E extends Exception> T execute(final K clusterKey, final Session stickySession,
			final BatchTransportOperation<T, E> operation) throws E {
		final SmtpTransportLease lease = claim(clusterKey, stickySession);
		Throwable operationFailure = null;
		boolean completedNormally = false;
		try {
			final T result = operation.execute(lease.getSession(), lease.getTransport());
			completedNormally = true;
			return result;
		} catch (RuntimeException | Error failure) {
			operationFailure = failure;
			throw failure;
		} catch (Exception failure) {
			operationFailure = failure;
			@SuppressWarnings("unchecked") final E typedFailure = (E) failure;
			throw typedFailure;
		} finally {
			if (completedNormally) {
				release(lease);
			} else {
				try {
					invalidate(lease);
				} catch (RuntimeException cleanupFailure) {
					if (operationFailure != null) {
						operationFailure.addSuppressed(cleanupFailure);
					} else {
						throw cleanupFailure;
					}
				}
			}
		}
	}

	SmtpTransportLease claim(final K clusterKey, final Session stickySession) {
		requireNonNull(clusterKey, "clusterKey");
		synchronized (lifecycleMonitor) {
			ensureClaimsOpen("claim a transport");
			if (!registeredSessions.containsKey(clusterKey)) {
				throw new BatchTransportException("No Sessions are registered for the requested cluster");
			}
			if (stickySession != null && !registeredSessions.get(clusterKey).contains(stickySession)) {
				throw new BatchTransportException("The requested Session is not registered for the requested cluster");
			}
		}

		final SmtpTransportLease lease;
		try {
			lease = stickySession == null
					? smtpConnectionPool.claimTransportFromCluster(clusterKey)
					: smtpConnectionPool.claimTransport(new ResourceClusterAndPoolKey<>(clusterKey, stickySession));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new BatchTransportException("Interrupted while waiting for an SMTP transport", interrupted);
		} catch (RuntimeException failure) {
			throw new BatchTransportException("Unable to claim an SMTP transport", failure);
		}

		synchronized (lifecycleMonitor) {
			if (!claimsOpen) {
				lease.invalidate();
				throw new BatchTransportException("The batch transport executor is shutting down");
			}
			activeLeases.add(lease);
		}
		return lease;
	}

	void release(final SmtpTransportLease lease) {
		try {
			lease.release();
		} catch (RuntimeException failure) {
			throw new BatchTransportException("Unable to return an SMTP transport to the pool", failure);
		} finally {
			activeLeases.remove(lease);
		}
	}

	void invalidate(final SmtpTransportLease lease) {
		try {
			lease.invalidate();
		} catch (RuntimeException failure) {
			throw new BatchTransportException("Unable to invalidate a failed SMTP transport", failure);
		} finally {
			activeLeases.remove(lease);
		}
	}

	public void stopClaimsAndInvalidateActiveLeases() {
		final SmtpTransportLease[] snapshot;
		synchronized (lifecycleMonitor) {
			claimsOpen = false;
			snapshot = activeLeases.toArray(new SmtpTransportLease[0]);
		}
		for (SmtpTransportLease lease : snapshot) {
			try {
				invalidate(lease);
			} catch (RuntimeException ignored) {
				// Whole-pool shutdown below remains the final cleanup boundary.
			}
		}
	}

	public Future<?> shutdown() {
		synchronized (lifecycleMonitor) {
			claimsOpen = false;
			if (shutdownStarted) {
				throw new IllegalStateException("Batch transport engine shutdown was already started");
			}
			shutdownStarted = true;
			return smtpConnectionPool.shutDown();
		}
	}

	Future<Void> shutdownPool(final Session session) {
		synchronized (lifecycleMonitor) {
			final Future<Void> shutdown = smtpConnectionPool.shutdownPool(session);
			final Iterator<Map.Entry<K, Set<Session>>> clusters = registeredSessions.entrySet().iterator();
			while (clusters.hasNext()) {
				final Set<Session> sessions = clusters.next().getValue();
				sessions.remove(session);
				if (sessions.isEmpty()) {
					clusters.remove();
				}
			}
			return shutdown;
		}
	}

	SmtpConnectionPoolClustered<K> getSmtpConnectionPool() {
		return smtpConnectionPool;
	}

	private void ensureClaimsOpen(final String operation) {
		if (!claimsOpen) {
			throw new BatchTransportException("Cannot " + operation + " after shutdown has begun");
		}
	}

	private static void bridgeOAuth2Properties(final Session session) {
		final Properties properties = session.getProperties();
		if (properties.containsKey(OAUTH2_TOKEN_PROPERTY)) {
			properties.setProperty(SmtpConnectionPool.OAUTH2_TOKEN_PROPERTY, properties.getProperty(OAUTH2_TOKEN_PROPERTY));
		}
		if (properties.containsKey(OAUTH2_TOKEN_PROVIDER_PROPERTY)) {
			properties.put(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY, properties.get(OAUTH2_TOKEN_PROVIDER_PROPERTY));
		}
	}

	private static void rejectProviderOwnedPool(final Session session) {
		rejectDeclaredProviderOwnedPool(session);

		Transport selectedTransport = null;
		try {
			selectedTransport = session.getTransport();
			final URLName urlName = selectedTransport.getURLName();
			if (urlName != null && isSmtpPool(urlName.getProtocol())) {
				throw doublePoolingException();
			}
		} catch (BatchTransportException failure) {
			throw failure;
		} catch (MessagingException failure) {
			throw new BatchTransportException("Unable to inspect the Session's default transport provider", failure);
		} finally {
			if (selectedTransport != null) {
				try {
					selectedTransport.close();
				} catch (MessagingException ignored) {
					// A disconnected provider probe has no pooled resource to recover.
				}
			}
		}
	}

	private static void rejectDeclaredProviderOwnedPool(final Session session) {
		final Properties properties = session.getProperties();
		if (isSmtpPool(properties.getProperty("mail.transport.protocol"))
				|| isSmtpPool(properties.getProperty("mail.transport.protocol.rfc822"))) {
			throw doublePoolingException();
		}
	}

	private static boolean isSmtpPool(final String protocol) {
		return protocol != null && SMTP_POOL_PROTOCOL.equalsIgnoreCase(protocol.trim());
	}

	private static BatchTransportException doublePoolingException() {
		return new BatchTransportException("The Session selects the smtppool provider; exactly one component may own the physical connection pool");
	}
}
