package org.simplejavamail.internal.batchsupport.concurrent;

import org.jetbrains.annotations.NotNull;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;

class NamedThreadFactory implements ThreadFactory {
	private final AtomicInteger threadNumber = new AtomicInteger(1);
	private final ThreadGroup group;
	private final String threadName;
	
	NamedThreadFactory(@NotNull final String threadName) {
		SecurityManager s = System.getSecurityManager();
		group = (s != null) ? s.getThreadGroup() : currentThread().getThreadGroup();
		this.threadName = threadName;
	}
	
	@NotNull
	public Thread newThread(@NotNull Runnable r) {
		Thread t = new Thread(group, r, format("%s %d", threadName, threadNumber.getAndIncrement()));
		if (t.isDaemon())
			t.setDaemon(false);
		if (t.getPriority() != Thread.NORM_PRIORITY)
			t.setPriority(Thread.NORM_PRIORITY);
		return t;
	}
}