package org.simplejavamail.api.mailer;

/** Why a built-in Mailer executor did not accept an asynchronous operation. No SMTP work has started for this operation. */
public enum AsyncQueueRejectionReason {
    /** No worker or waiting slot was available under the immediate rejection policy. */
    QUEUE_FULL,
    /** The caller waited for capacity, but the admission timeout expired before the task was accepted. */
    CAPACITY_WAIT_TIMED_OUT,
    /** The caller was interrupted while waiting for capacity; its interrupt flag remains set. */
    CAPACITY_WAIT_INTERRUPTED,
    /** The executor stopped accepting work before this task was admitted. */
    EXECUTOR_SHUT_DOWN
}
