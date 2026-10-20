package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import org.simplejavamail.api.mailer.config.OperationalConfig;

/**
 * Bounds serialized replay-content bytes, not total heap use: capture buffers, array copies and object overhead can consume additional memory.
 * Its short bookkeeping monitor never covers message reads, network work or callbacks.
 */
final class MailSendReplayBudget {
    private final long maximumMessageSize;
    private final long memoryLimit;
    private long retainedContentBytes;

    MailSendReplayBudget(final OperationalConfig config) {
        maximumMessageSize = config.getMailSendRetryPolicy().getMaximumMessageSize();
        memoryLimit = config.getMailSendRetryPolicy().getMemoryLimit();
    }

    Reservation reserve() { return new Reservation(); }

    private synchronized void retain(final long byteCount) {
        if (byteCount > memoryLimit - retainedContentBytes) {
            throw new MailerException(MailFailureKind.CAPACITY,
                    "This Mailer's automatic-retry replay memory budget is full (" + memoryLimit + " bytes). "
                    + "Reduce concurrent retry-enabled sends or increase withMemoryLimit(...) on the initiating Mailer's retry policy. "
                    + "No message was submitted.", null);
        }
        retainedContentBytes += byteCount;
    }

    private synchronized void release(final long byteCount) { retainedContentBytes -= byteCount; }

    /**
     * Owned by one logical operation, not independently thread-safe. Its state transitions keep execution and terminal cleanup mutually exclusive,
     * even when different retry turns run on different threads; only aggregate accounting needs the shared monitor.
     */
    final class Reservation implements AutoCloseable {
        private long retainedBytes;
        private boolean closed;

        void retain(final long byteCount) {
            verifyActiveReservation(byteCount);
            verifyMessageFits(byteCount);
            MailSendReplayBudget.this.retain(byteCount);
            retainedBytes += byteCount;
        }

        private void verifyActiveReservation(final long byteCount) {
            if (closed || byteCount < 0) {
                throw new IllegalStateException("This replay-memory reservation is closed or the byte count is invalid.");
            }
        }

        private void verifyMessageFits(final long byteCount) {
            if (byteCount > maximumMessageSize - retainedBytes) {
                throw new MailerException(MailFailureKind.MESSAGE_SIZE,
                        "This email's serialized MIME content exceeds the automatic-retry replay limit of "
                        + maximumMessageSize + " bytes. Increase withMaximumMessageSize(...) and withMemoryLimit(...) "
                        + "on the initiating Mailer's retry policy, "
                        + "or select a Mailer without an enabled retry schedule. No message was submitted.", null);
            }
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                release(retainedBytes);
                retainedBytes = 0;
            }
        }
    }
}
