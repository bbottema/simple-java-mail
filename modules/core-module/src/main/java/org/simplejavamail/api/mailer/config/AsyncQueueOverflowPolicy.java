package org.simplejavamail.api.mailer.config;

/** What a built-in Mailer executor does when all workers and queued places are occupied. */
public enum AsyncQueueOverflowPolicy {
    /** Fail the returned completion immediately, without running the send on the calling thread. */
    REJECT,
    /** Wait on the calling thread for queue capacity, up to the configured admission timeout. */
    WAIT_FOR_CAPACITY
}
