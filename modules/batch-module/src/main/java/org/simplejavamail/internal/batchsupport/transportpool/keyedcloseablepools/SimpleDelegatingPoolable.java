package org.simplejavamail.internal.batchsupport.transportpool.keyedcloseablepools;

import stormpot.Poolable;
import stormpot.Slot;

public class SimpleDelegatingPoolable<T> implements Poolable {
  private final Slot slot;
  private final T allocatedDelegate;

  public SimpleDelegatingPoolable(Slot slot, T allocatedDelegate) {
    this.slot = slot;
    this.allocatedDelegate = allocatedDelegate;
  }

  public void release() {
    slot.release(this);
  }

  public T getDelegate() {
    return allocatedDelegate;
  }
}