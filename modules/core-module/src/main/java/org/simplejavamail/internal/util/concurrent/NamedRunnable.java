package org.simplejavamail.internal.util.concurrent;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;


/**
 * This Runnable is smart in the sense that it can shutdown the
 */
@RequiredArgsConstructor
public class NamedRunnable implements Runnable {

	private static final Logger LOGGER = getLogger(NamedRunnable.class);

	@NotNull private final String processName;
	@NotNull private final Runnable operation;

	@Override
	public void run() {
		// by the time the code reaches here, the user would have configured the appropriate handlers
		try {
			operation.run();
		} catch (Exception e) {
			LOGGER.error("Failed to run " + processName, e);
			throw e;
		}
	}

	@Override
	public String toString() {
		return processName;
	}
}