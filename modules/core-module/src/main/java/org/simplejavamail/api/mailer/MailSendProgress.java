package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Shows what one email send was doing when this snapshot was captured. You receive these immutable snapshots through
 * {@link MailSendProgressObserver}; you do not need to construct them yourself.
 * <p>
 * Progress helps you follow a send, but updates can be combined or dropped. Use {@link MailSendOutcome} to find out how it finished:
 * a progress snapshot does not establish whether the SMTP server accepted the email, let alone delivered it.
 */
public interface MailSendProgress extends Serializable {
    /**
     * Identifies this send in both progress updates and its final outcome, even if it changes servers or retries.
     * Sending the same Email again produces a different ID. This ID stays within Simple Java Mail and is never sent with the email.
     *
     * @return The generated observation ID.
     */
    @NotNull UUID getObservationId();

    /**
     * Numbers the snapshots published for this send. Newer snapshots have higher numbers, but you may see gaps when updates are combined or dropped.
     *
     * @return This snapshot's sequence number.
     */
    long getSequence();

    /** @return The wall-clock time when this snapshot was captured, which may be earlier than when your callback receives it. */
    @NotNull Instant getObservedAt();

    /**
     * Shows how long this send had been running when the snapshot was captured, including failover and retries.
     * A monotonic clock measures the duration, so changes to the system clock do not affect it.
     *
     * @return The non-negative elapsed duration.
     */
    @NotNull Duration getElapsed();

    /**
     * Describes the work being observed, rather than a step in a fixed sequence. Stages can repeat or be skipped;
     * {@link MailSendProgressStage#FINISHED FINISHED} tells you that the send ended, not that it succeeded.
     *
     * @return The stage observed when this snapshot was captured.
     */
    @NotNull MailSendProgressStage getStage();

    /**
     * Counts calls to the provider or {@link CustomMailer} to submit this email. The first call starts attempt one, and retries increase the number.
     * Switching servers during connection setup does not count as another submission.
     *
     * @return The submission-attempt number, or zero before the first invocation.
     */
    int getSubmissionAttempt();

    /**
     * @return The caller-supplied Message-ID when this send began, or {@code null} if none was supplied.
     * Use {@link #getObservationId()} to distinguish separate sends of the same email.
     */
    @Nullable String getInitialMessageId();

    /**
     * @return The effective Message-ID, or {@code null} if it is not yet known. It can change during preparation;
     * {@link #getObservationId()} remains the same throughout the send.
     */
    @Nullable String getEffectiveMessageId();

    /**
     * Shows how much of the message has been handed to the provider's content stream during this submission. The count starts over on each retry.
     * It measures serialized MIME bytes before SMTP framing, dot escaping and TLS, so it is not a count of bytes sent over the network.
     * Some bytes may still be buffered; this count does not prove that the server received them.
     *
     * @return The number of bytes successfully handed to the content stream, or {@code null} when no count is available.
     */
    @Nullable
    Long getBytesWritten();

    /**
     * Gives you a total to compare with {@link #getBytesWritten()}, when a matching serialized-message size is available.
     * This is not the SMTP SIZE value. Reaching the total means the content has been handed to the provider's stream;
     * the SMTP server may still reject it, and final delivery is a separate matter.
     *
     * @return The matching total, or {@code null} if it is unknown or no longer agrees with the content being written.
     */
    @Nullable
    Long getTotalBytes();
}
