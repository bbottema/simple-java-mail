package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

/**
 * Lets you follow individual emails while they are being sent, for example to update a dashboard during a large upload.
 * You receive the latest available snapshots, not every change along the way. Use a {@link MailSendResultObserver} alongside this observer
 * when you also need to know how each send finished.
 * <p>
 * The Mailer delivers progress independently of sending, one callback at a time. If you share an observer between Mailers, make it thread-safe:
 * different Mailers can call it concurrently. The builder registration documents the reporting limits and executor choices.
 *
 * @see MailSendHandlers.Builder#withProgressObserver(MailSendProgressObserver)
 */
@FunctionalInterface
public interface MailSendProgressObserver {

    /**
     * Receives an immutable snapshot that may already be out of date by the time this callback runs. In particular, progress and final results
     * are delivered independently, so ignore updates for an observation ID once you have received its final result.
     * If this callback throws a {@link RuntimeException}, it is logged and ignored without affecting the send.
     *
     * @param progress The latest snapshot selected for delivery; earlier snapshots may have been combined or dropped.
     */
    void onMailSendProgress(@NotNull MailSendProgress progress);
}
