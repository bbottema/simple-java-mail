package org.simplejavamail.examples.support;

/**
 * Close every resource an example owns, even if one of them fails to close.
 * The first failure stays primary; later failures are attached to it rather than hiding what went wrong first.
 */
public final class ResourceCleanup {
    private ResourceCleanup() {
    }

    /** Roll back partial startup without replacing the failure that prevented the application from starting. */
    public static void afterFailure(Throwable startupFailure, AutoCloseable... resources) {
        try {
            closeAll(resources);
        } catch (Throwable cleanupFailure) {
            if (startupFailure != cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    public static void closeAll(AutoCloseable... resources) throws Exception {
        Throwable firstFailure = null;
        boolean interrupted = false;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable failure) {
                interrupted |= failure instanceof InterruptedException;
                if (firstFailure == null) {
                    firstFailure = failure;
                } else if (firstFailure != failure) {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        // An interrupted wait must not prevent closing independent resources. Restore the signal before returning to its owner.
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (firstFailure instanceof Exception exception) {
            throw exception;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
    }
}
