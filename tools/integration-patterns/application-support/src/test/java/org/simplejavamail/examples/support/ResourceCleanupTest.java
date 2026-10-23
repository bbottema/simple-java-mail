package org.simplejavamail.examples.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceCleanupTest {
    @Test
    void startupRollbackPreservesTheStartupFailureAndCleanupEvidence() {
        var startupFailure = new IllegalStateException("startup failed");
        var cleanupFailure = new IllegalArgumentException("cleanup failed");
        var closed = new ArrayList<String>();

        ResourceCleanup.afterFailure(startupFailure,
                () -> { throw cleanupFailure; },
                () -> closed.add("remaining"));

        assertArrayEquals(new Throwable[]{cleanupFailure}, startupFailure.getSuppressed());
        assertEquals(List.of("remaining"), closed);
    }

    @Test
    void continuesAfterFailuresWithoutReplacingTheFirstFailure() {
        var closed = new ArrayList<String>();
        var first = new IllegalStateException("first");
        var later = new IllegalArgumentException("later");

        Exception thrown = assertThrows(Exception.class, () -> ResourceCleanup.closeAll(
                () -> { closed.add("first"); throw first; },
                () -> closed.add("healthy"),
                () -> { closed.add("last"); throw later; }));

        assertSame(first, thrown);
        assertArrayEquals(new Throwable[]{later}, thrown.getSuppressed());
        assertEquals(List.of("first", "healthy", "last"), closed);
    }

    @Test
    void restoresInterruptionAfterAttemptingIndependentCleanup() {
        var interrupted = new InterruptedException("draining interrupted");
        var closed = new ArrayList<String>();
        try {
            assertSame(interrupted, assertThrows(InterruptedException.class, () -> ResourceCleanup.closeAll(
                    () -> { throw interrupted; },
                    () -> closed.add("client"))));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("client"), closed);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stillClosesOtherResourcesWhenAnErrorOccurs() {
        var error = new AssertionError("close failed");
        var closed = new ArrayList<String>();

        assertSame(error, assertThrows(AssertionError.class, () -> ResourceCleanup.closeAll(
                () -> { throw error; },
                () -> closed.add("remaining"))));

        assertEquals(List.of("remaining"), closed);
    }

    @Test
    void permitsAbsentResourcesAndTheSameFailureInstance() {
        var failure = new IllegalStateException("shared failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> ResourceCleanup.closeAll(
                null, () -> { throw failure; }, () -> { throw failure; })));
        assertEquals(0, failure.getSuppressed().length);
    }
}
