package org.simplejavamail.internal.batchsupport;

import org.simplejavamail.api.internal.batchsupport.LifecycleDelegatingTransport;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;
import org.simplejavamail.internal.batchsupport.transportpool.LifecycleDelegatingTransportImpl;
import org.simplejavamail.internal.batchsupport.transportpool.PoolableTransportAllocatorFactory;
import org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools.KeyedObjectPools;
import org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools.SimpleDelegatingPoolable;
import org.simplejavamail.internal.modules.BatchModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stormpot.TimeExpiration;
import stormpot.Timeout;

import javax.annotation.Nonnull;
import javax.mail.Session;
import javax.mail.Transport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This class only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
@SuppressWarnings("unused") // it is used through reflection
public class BatchSupport implements BatchModule {

	private static final Logger LOGGER = LoggerFactory.getLogger(BatchSupport.class);
	private static final Timeout WAIT_FOREVER = new Timeout(Long.MAX_VALUE, TimeUnit.DAYS);
	private static final TimeExpiration<SimpleDelegatingPoolable<Transport>> TIME_TO_LIVE_5_SECONDS = new TimeExpiration<>(5, SECONDS);

	private final KeyedObjectPools<Session, SimpleDelegatingPoolable<Transport>> transportPools =
			new KeyedObjectPools<>(new PoolableTransportAllocatorFactory(), TIME_TO_LIVE_5_SECONDS, WAIT_FOREVER);

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
	 * @see BatchModule#acquireTransport(Session)
	 */
	@Nonnull
	@Override
	@SuppressWarnings("deprecation")
	public LifecycleDelegatingTransport acquireTransport(@Nonnull final Session session) {
		return new LifecycleDelegatingTransportImpl(transportPools.acquire(session));
	}

	/**
	 * @see BatchModule#clearTransportPool(Session)
	 */
	@Override
	public void clearTransportPool(@Nonnull final Session session) {
		transportPools.clearPool(session);
	}
}