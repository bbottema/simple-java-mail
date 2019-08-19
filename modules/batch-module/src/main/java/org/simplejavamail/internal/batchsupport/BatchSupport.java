package org.simplejavamail.internal.batchsupport;

import org.bbottema.clusteredobjectpool.core.api.ResourceKey.ResourceClusterAndPoolKey;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.mail.Session;
import javax.mail.Transport;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.simplejavamail.internal.batchsupport.BatchException.ERROR_ACQUIRING_KEYED_POOLABLE;

/**
 * This class only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class BatchSupport implements BatchModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchSupport.class);

	// no need to make this static, because this module itself is already static in the ModuleLoader
	private final SmtpConnectionPoolClustered smtpConnectionPool = configureSmtpConnectionPool();

	// FIXME allow configurating the max poolsize, core poolsize
	private static SmtpConnectionPoolClustered configureSmtpConnectionPool() {
		SmtpClusterConfig smtpClusterConfig = new SmtpClusterConfig();
		smtpClusterConfig.getConfigBuilder()
				.defaultExpirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<Transport>(5, SECONDS));
		return new SmtpConnectionPoolClustered(smtpClusterConfig);
	}

	/**
	 * @see BatchModule#executeAsync(String, Runnable)
	 */
	@Nonnull
	@Override
	public AsyncResponse executeAsync(@Nonnull final String processName, @Nonnull final Runnable operation) {
		return AsyncOperationHelper.executeAsync(processName, operation);
	}

	/**
	 * @see BatchModule#executeAsync(ExecutorService, String, Runnable)
	 */
	@Nonnull
	@Override
	public AsyncResponse executeAsync(@Nonnull final ExecutorService executorService, @Nonnull final String processName, @Nonnull final Runnable operation) {
		return AsyncOperationHelper.executeAsync(executorService, processName, operation);
	}

	/**
	 * @see BatchModule#createDefaultExecutorService(int, int)
	 */
	@Nonnull
	@Override
	public ExecutorService createDefaultExecutorService(final int threadPoolSize, final int keepAliveTime) {
		return new NonJvmBlockingThreadPoolExecutor(threadPoolSize, keepAliveTime);
	}

	/**
	 * @see BatchModule#acquireTransport(UUID, Session, boolean)
	 */
	@Nonnull
	@Override
	public LifecycleDelegatingTransport acquireTransport(@Nonnull final UUID clusterKey, @Nonnull final Session session, boolean stickySession) {
		try {
			final ResourceClusterAndPoolKey<UUID, Session> poolKey = new ResourceClusterAndPoolKey<>(clusterKey, session);
			final PoolableObject<Transport> pooledTransport;
			if (stickySession) {
				pooledTransport = smtpConnectionPool.claimResourceFromPool(poolKey);
			} else {
				// add pool not yet registered, add it to the cluster
				if (!smtpConnectionPool.isPoolRegistered(poolKey)) {
					smtpConnectionPool.registerResourcePool(poolKey);
				}
				pooledTransport = smtpConnectionPool.claimResourceFromCluster(clusterKey);
			}
			return new LifecycleDelegatingTransportImpl(pooledTransport);
		} catch (InterruptedException e) {
			throw new BatchException(format(ERROR_ACQUIRING_KEYED_POOLABLE, session), e);
		}
	}

	/**
	 * @see BatchModule#shutdownConnectionPools(Session)
	 */
	@Nonnull
	@Override
	public Future<?> shutdownConnectionPools(@Nonnull Session session) {
		return smtpConnectionPool.shutdownPool(session);
	}
}