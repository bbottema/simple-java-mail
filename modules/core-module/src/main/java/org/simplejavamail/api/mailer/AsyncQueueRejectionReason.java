package org.simplejavamail.api.mailer;

/** Why execution admission failed. No SMTP work has started for this operation. Built-in queue diagnostics count only their own rejections. */
public enum AsyncQueueRejectionReason {
    /** No worker or waiting slot was available under the immediate rejection policy. */
    QUEUE_FULL,
    /** The caller waited for capacity, but the admission timeout expired before the task was accepted. */
    CAPACITY_WAIT_TIMED_OUT,
    /** The caller was interrupted while waiting for capacity; its interrupt flag remains set. */
    CAPACITY_WAIT_INTERRUPTED,
    /** The executor stopped accepting work before this task was admitted. */
    EXECUTOR_SHUT_DOWN,
    /** A borrowed executor rejected admission without exposing a more specific built-in queue reason. Its original rejection is the cause. */
    EXECUTOR_REJECTED
}
