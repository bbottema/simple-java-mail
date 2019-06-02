package org.simplejavamail.internal.batchsupport.transportpool;

import org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools.AllocatorFactory;
import org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools.SimpleDelegatingPoolable;
import stormpot.Allocator;

import javax.annotation.Nonnull;
import javax.mail.Session;
import javax.mail.Transport;

public class PoolableTransportAllocatorFactory implements AllocatorFactory<Session, SimpleDelegatingPoolable<Transport>> {
	@Override
	@Nonnull
	public Allocator<SimpleDelegatingPoolable<Transport>> create(@Nonnull final Session session) {
		return new PoolableTransportAllocator(session);
	}
}
