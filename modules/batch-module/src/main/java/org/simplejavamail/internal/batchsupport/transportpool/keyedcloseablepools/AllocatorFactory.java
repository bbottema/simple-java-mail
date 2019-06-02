package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.Allocator;
import stormpot.Poolable;

import javax.annotation.Nonnull;

public interface AllocatorFactory<Key, T extends Poolable> {
	@Nonnull Allocator<T> create(@Nonnull Key key);
}
