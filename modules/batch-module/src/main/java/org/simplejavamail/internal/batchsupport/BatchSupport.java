package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.concurrent.AsyncOperationHelper;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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

	/**
	 * @see BatchModule#executeAsync(String, Runnable)
	 */
	@Override
	public CompletableFuture<Void> executeAsync(@NotNull final String processName, @NotNull final Runnable operation) {
		return AsyncOperationHelper.executeAsync(processName, operation);
	}

	/**
	 * @see BatchModule#executeAsync(ExecutorService, String, Runnable)
	 */
	@NotNull
	@Override
	public CompletableFuture<Void> executeAsync(@NotNull final ExecutorService executorService, @NotNull final String processName, @NotNull final Runnable operation) {
		return AsyncOperationHelper.executeAsync(executorService, processName, operation);
	}

	/**
	 * @see BatchModule#createDefaultExecutorService(int, int)
	 */
	@NotNull
	@Override
	public ExecutorService createDefaultExecutorService(final int threadPoolSize, final int keepAliveTime) {
		return new NonJvmBlockingThreadPoolExecutor(threadPoolSize, keepAliveTime);
	}

	/**
	 * @see BatchModule#registerToCluster(OperationalConfig, UUID, Session)
	 */
	@Override
	public synchronized void registerToCluster(@NotNull final OperationalConfig operationalConfig, @NotNull final UUID clusterKey, @NotNull final Session session) {
		ensureEngineInitialized(operationalConfig);
		if (requireNonNull(batchTransportEngine).register(clusterKey, session, PoolSettings.from(operationalConfig, clusterKey))) {
			LOGGER.warn("SMTP Connection pool cluster {} is already configured with pool defaults from the first Mailer instance in that cluster; ignoring later pool settings",
					clusterKey);
		}
	}

	private void ensureEngineInitialized(@NotNull OperationalConfig operationalConfig) {
		if (batchTransportEngine == null) {
			LOGGER.warn("Starting SMTP connection pool cluster: JVM won't shutdown until the pool is manually closed with mailer.shutdownConnectionPool() (for each mailer in the cluster)");
			batchTransportEngine = new BatchTransportEngine<>(PoolSettings.from(operationalConfig, null));
			smtpConnectionPool = batchTransportEngine.getSmtpConnectionPool();
		}
	}

	/**
	 * @see BatchModule#acquireTransport(UUID, Session, boolean)
	 */
	@NotNull
	@Override
	public LifecycleDelegatingTransport acquireTransport(@NotNull final UUID clusterKey, @NotNull final Session session, boolean stickySession) {
		final BatchTransportEngine<UUID> engine = requireNonNull(batchTransportEngine,
				"Connection pool used before it was initialized. This shouldn't be possible.");
		return new LifecycleDelegatingTransportImpl(engine, engine.claim(clusterKey, stickySession ? session : null));
	}

	/**
	 * @see BatchModule#shutdownConnectionPools(Session)
	 */
	@NotNull
	@Override
	public Future<Void> shutdownConnectionPools(@NotNull Session session) {
		if (batchTransportEngine == null) {
			LOGGER.warn("user requested connection pool shutdown, but there is no connection pool to shut down (yet)");
			return completedFuture(null);
		}
		return batchTransportEngine.shutdownPool(session);
	}
}
