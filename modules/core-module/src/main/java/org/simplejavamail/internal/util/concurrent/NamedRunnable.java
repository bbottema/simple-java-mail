package org.simplejavamail.internal.util.concurrent;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * This Runnable is smart in the sense that it can shutdown the
 */
@RequiredArgsConstructor
public class NamedRunnable implements Runnable {

	@NotNull private final String processName;
	@NotNull private final Runnable operation;

	@Override
	public void run() {
		operation.run();
	}

	@Override
	public String toString() {
		return processName;
	}
}
