package org.simplejavamail.mailer.internal;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Immutable producer-owned snapshot. No Email, transport, callback or application executor is retained. */
@ApiStatus.Internal
@Getter
@EqualsAndHashCode
@ToString(onlyExplicitlyIncluded = true)
public final class MailSendProgressImpl implements MailSendProgress {
    private static final long serialVersionUID = 1L;

    @ToString.Include private final UUID observationId;
    @ToString.Include private final long sequence;
    private final Instant observedAt;
    @ToString.Include private final Duration elapsed;
    @ToString.Include private final MailSendProgressStage stage;
    @ToString.Include private final int submissionAttempt;
    @Nullable private final String initialMessageId;
    @Nullable private final String effectiveMessageId;
    @ToString.Include @Nullable private final Long bytesWritten;
    @ToString.Include @Nullable private final Long totalBytes;

    @Builder
    private MailSendProgressImpl(final UUID observationId, final long sequence, final Instant observedAt, final Duration elapsed,
            final MailSendProgressStage stage, final int submissionAttempt, @Nullable final String initialMessageId,
            @Nullable final String effectiveMessageId, @Nullable final Long bytesWritten, @Nullable final Long totalBytes) {
        this.observationId = requireNonNull(observationId, "observationId");
        this.sequence = sequence;
        this.observedAt = requireNonNull(observedAt, "observedAt");
        this.elapsed = requireNonNull(elapsed, "elapsed");
        this.stage = requireNonNull(stage, "stage");
        this.submissionAttempt = submissionAttempt;
        this.initialMessageId = initialMessageId;
        this.effectiveMessageId = effectiveMessageId;
        this.bytesWritten = bytesWritten;
        this.totalBytes = totalBytes;
        if (sequence < 1 || elapsed.isNegative() || submissionAttempt < 0 || (bytesWritten != null && bytesWritten < 0)
                || (totalBytes != null && totalBytes < 0) || (bytesWritten != null && totalBytes != null && bytesWritten > totalBytes)) {
            throw new IllegalArgumentException("Progress requires a positive sequence, non-negative measurements and a total covering written bytes");
        }
    }

    /** @see MailSendProgress#getBytesWritten() */
    @Override
    public Optional<Long> getBytesWritten() {
        return Optional.ofNullable(bytesWritten);
    }

    /** @see MailSendProgress#getTotalBytes() */
    @Override
    public Optional<Long> getTotalBytes() {
        return Optional.ofNullable(totalBytes);
    }
}
