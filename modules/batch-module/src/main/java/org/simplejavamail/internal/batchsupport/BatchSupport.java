package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.PoolableObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.util.concurrent.AsyncOperationHelper;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.batchsupport.BatchException.ERROR_ACQUIRING_KEYED_POOLABLE;
import static org.simplejavamail.internal.batchsupport.ClusterHelper.compareClusterConfig;
import static org.simplejavamail.internal.batchsupport.ClusterHelper.configureSmtpClusterConfig;

/**
 * This class only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class BatchSupport implements BatchModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchSupport.class);

	// no need to make this static, because this module itself is already static in the ModuleLoader
	@Nullable private SmtpConnectionPoolClustered smtpConnectionPool;

	/**
	 * @see BatchModule#executeAsync(String, Runnable)
	 */
	@NotNull
	@Override
	public AsyncResponse executeAsync(@NotNull final String processName, @NotNull final Runnable operation) {
		return AsyncOperationHelper.executeAsync(processName, operation);
	}

	/**
	 * @see BatchModule#executeAsync(ExecutorService, String, Runnable)
	 */
	@NotNull
	@Override
	public AsyncResponse executeAsync(@NotNull final ExecutorService executorService, @NotNull final String processName, @NotNull final Runnable operation) {
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
	public void registerToCluster(@NotNull final OperationalConfig operationalConfig, @NotNull final UUID clusterKey, @NotNull final Session session) {
		ensureClusterInitialized(operationalConfig);
		final ResourceClusterAndPoolKey<UUID, Session> poolKey = new ResourceClusterAndPoolKey<>(clusterKey, session);
		if (!requireNonNull(smtpConnectionPool).isPoolRegistered(poolKey)) {
			smtpConnectionPool.registerResourcePool(poolKey);
		}
	}

	private void ensureClusterInitialized(@NotNull OperationalConfig operationalConfig) {
		if (smtpConnectionPool == null) {
			smtpConnectionPool = new SmtpConnectionPoolClustered(configureSmtpClusterConfig(operationalConfig));
		} else if (compareClusterConfig(operationalConfig, smtpConnectionPool.getClusterConfig())) {
			LOGGER.warn("Global SMTP Connection pool is already configured with pool defaults from the first Mailer instance, ignoring relevant properties from {}", operationalConfig);
		}
	}

	/**
	 * @see BatchModule#acquireTransport(UUID, Session, boolean)
	 */
	@NotNull
	@Override
	public LifecycleDelegatingTransport acquireTransport(@NotNull final UUID clusterKey, @NotNull final Session session, boolean stickySession) {
		try {
			requireNonNull(smtpConnectionPool, "Connection pool used before it was initialized. This shouldn't be possible.");
			final PoolableObject<Transport> pooledTransport = stickySession
					? smtpConnectionPool.claimResourceFromPool(new ResourceClusterAndPoolKey<>(clusterKey, session))
					: smtpConnectionPool.claimResourceFromCluster(clusterKey);
			return new LifecycleDelegatingTransportImpl(pooledTransport);
		} catch (InterruptedException e) {
			throw new BatchException(format(ERROR_ACQUIRING_KEYED_POOLABLE, session), e);
		}
	}

	/**
	 * @see BatchModule#shutdownConnectionPools(Session)
	 */
	@NotNull
	@Override
	public Future<?> shutdownConnectionPools(@NotNull Session session) {
		if (smtpConnectionPool == null) {
			LOGGER.warn("user requested connection pool shutdown, but there is no connection pool to shut down (yet)");
			FutureTask<Void> voidFutureTask = new FutureTask<>(() -> { }, null);
			voidFutureTask.run();
			return voidFutureTask;
		}
		return smtpConnectionPool.shutdownPool(session);
	}
}