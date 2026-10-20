package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import java.time.Duration;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied SendingLimitsConfig choices. Unset fields inherit the owning Mailer factory snapshot.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 */
@Value
public class SendingLimitsConfig {
    @Nullable SendingRateLimit messageRateLimit;
    boolean messageRateLimitCleared;
    @Nullable SendingRateLimit recipientRateLimit;
    boolean recipientRateLimitCleared;
    @Nullable String rateLimitGroup;
    boolean rateLimitGroupCleared;
    @Nullable Boolean rateLimitBurstsAllowed;

    private SendingLimitsConfig(final Builder builder) {
        messageRateLimit = builder.messageRateLimit;
        messageRateLimitCleared = builder.messageRateLimitCleared;
        recipientRateLimit = builder.recipientRateLimit;
        recipientRateLimitCleared = builder.recipientRateLimitCleared;
        rateLimitGroup = builder.rateLimitGroup;
        rateLimitGroupCleared = builder.rateLimitGroupCleared;
        rateLimitBurstsAllowed = builder.rateLimitBurstsAllowed;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /**
     * @return The supplied message rule, or {@code null} for inheritance or explicit disabling; {@link #isMessageRateLimitCleared()} distinguishes them.
     * @see Builder#withMessageRateLimit(int, Duration)
     */
    @Nullable public SendingRateLimit getMessageRateLimit() { return messageRateLimit; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearMessageRateLimit()
     */
    public boolean isMessageRateLimitCleared() { return messageRateLimitCleared; }
    /**
     * @return The supplied recipient rule, or {@code null} for inheritance or explicit disabling; {@link #isRecipientRateLimitCleared()} distinguishes them.
     * @see Builder#withRecipientRateLimit(int, Duration)
     */
    @Nullable public SendingRateLimit getRecipientRateLimit() { return recipientRateLimit; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearRecipientRateLimit()
     */
    public boolean isRecipientRateLimitCleared() { return recipientRateLimitCleared; }
    /**
     * @return The supplied sharing name, or {@code null} for inheritance or explicitly private allowance; {@link #isRateLimitGroupCleared()} distinguishes them.
     * @see Builder#withRateLimitGroup(String)
     */
    @Nullable public String getRateLimitGroup() { return rateLimitGroup; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearRateLimitGroup()
     */
    public boolean isRateLimitGroupCleared() { return rateLimitGroupCleared; }
    /** @see Builder#withRateLimitBurstsAllowed(boolean) */
    @Nullable public Boolean getRateLimitBurstsAllowed() { return rateLimitBurstsAllowed; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private SendingRateLimit messageRateLimit;
        private boolean messageRateLimitCleared;
        private SendingRateLimit recipientRateLimit;
        private boolean recipientRateLimitCleared;
        private String rateLimitGroup;
        private boolean rateLimitGroupCleared;
        private Boolean rateLimitBurstsAllowed;
        private Builder() { }
        private Builder(final SendingLimitsConfig config) {
            messageRateLimit = config.messageRateLimit;
            messageRateLimitCleared = config.messageRateLimitCleared;
            recipientRateLimit = config.recipientRateLimit;
            recipientRateLimitCleared = config.recipientRateLimitCleared;
            rateLimitGroup = config.rateLimitGroup;
            rateLimitGroupCleared = config.rateLimitGroupCleared;
            rateLimitBurstsAllowed = config.rateLimitBurstsAllowed;
        }
        /**
         * Limits local submission attempts to the given count in any rolling period. For example, use {@code (30, Duration.ofMinutes(1))}
         * for a service that allows thirty messages per minute. There is no limit by default. Calling this again replaces the previous rule.
         * <p>
         * Sends wait on their existing caller or worker thread before borrowing a connection. Waiting counts against the total send timeout
         * and can be cancelled. Simple batches and open-connection scopes retain their shared connection while waiting between emails;
         * long waits can encounter the SMTP server's idle timeout. The connection is not silently replaced.
         * <p>
         * One provider invocation counts as an attempt, even if it rejects the email or fails. Connection and MIME-preparation failures before
         * invocation consume nothing. Validation, rehearsal, probing and logging-only mode do not count. CustomMailer callbacks count once;
         * their internal retries and additional sends are outside this limit. This is not a persistent or account-wide provider quota.
         * <p>
         * The selected SMTP configuration supplies the limit, including when a pool cluster chooses another Mailer's connection.
         * By default each registration has its own allowance; use {@link #withRateLimitGroup(String)} to share it within a factory.
         * Properties: {@code simplejavamail.smtp.ratelimit.messages.limit} and {@code simplejavamail.smtp.ratelimit.messages.period}.
         *
         * @param count Positive maximum number of attempted submissions.
         * @param period Positive rolling period representable in nanoseconds; CLI/property text uses ISO-8601, for example {@code PT1M}.
         * @see #withRecipientRateLimit(int, Duration)
         * @see #withRateLimitBurstsAllowed(boolean)
         */
        @NotNull public Builder withMessageRateLimit(final int count, @NotNull final Duration period) { return withMessageRateLimit(new SendingRateLimit(count, period)); }
        /**
         * Replaces the complete count/period rule.
         * @param messageRateLimit Immutable rate-limit rule.
         * @see #withMessageRateLimit(int, Duration)
         */
        @NotNull public Builder withMessageRateLimit(@NotNull final SendingRateLimit messageRateLimit) { this.messageRateLimit = requireNonNull(messageRateLimit, "messageRateLimit"); messageRateLimitCleared = false; return this; }
        /** @see #withMessageRateLimit(int, Duration) */
        @Nullable public SendingRateLimit getMessageRateLimit() { return messageRateLimit; }
        /**
         * Disables the message rule, including a captured rule, without changing the recipient rule or sharing group.
         * @see #withMessageRateLimit(int, Duration)
         */
        @NotNull public Builder clearMessageRateLimit() { messageRateLimit = null; messageRateLimitCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withMessageRateLimit(int, Duration)
         */
        @NotNull public Builder useConfiguredMessageRateLimit() { messageRateLimit = null; messageRateLimitCleared = false; return this; }
        /**
         * Limits the number of intended envelope recipients in any rolling period, including BCC and delivery-list overrides. Duplicate
         * recipient occurrences count separately. For example, {@code (100, Duration.ofMinutes(1))} allows one hundred recipients per minute.
         * An email with more recipients than the whole allowance fails instead of waiting forever or being split into multiple messages.
         * <p>
         * Disabled by default; calling this again replaces the rule. When a message limit also exists, both must permit the submission.
         * Accounting, waiting and sharing follow {@link #withMessageRateLimit(int, Duration)}; rejected recipients are not refunded.
         * Properties: {@code simplejavamail.smtp.ratelimit.recipients.limit} and {@code simplejavamail.smtp.ratelimit.recipients.period}.
         *
         * @param count Positive maximum number of intended envelope-recipient occurrences.
         * @param period Positive rolling period representable in nanoseconds; CLI/property text uses ISO-8601, for example {@code PT1M}.
         */
        @NotNull public Builder withRecipientRateLimit(final int count, @NotNull final Duration period) { return withRecipientRateLimit(new SendingRateLimit(count, period)); }
        /**
         * Replaces the complete count/period rule.
         * @param recipientRateLimit Immutable rate-limit rule.
         * @see #withRecipientRateLimit(int, Duration)
         */
        @NotNull public Builder withRecipientRateLimit(@NotNull final SendingRateLimit recipientRateLimit) { this.recipientRateLimit = requireNonNull(recipientRateLimit, "recipientRateLimit"); recipientRateLimitCleared = false; return this; }
        /** @see #withRecipientRateLimit(int, Duration) */
        @Nullable public SendingRateLimit getRecipientRateLimit() { return recipientRateLimit; }
        /**
         * Disables the recipient rule, including a captured rule, without changing the message rule or sharing group.
         * @see #withRecipientRateLimit(int, Duration)
         */
        @NotNull public Builder clearRecipientRateLimit() { recipientRateLimit = null; recipientRateLimitCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withRecipientRateLimit(int, Duration)
         */
        @NotNull public Builder useConfiguredRecipientRateLimit() { recipientRateLimit = null; recipientRateLimitCleared = false; return this; }
        /**
         * Shares sending allowance between configurations using this name within the same SimpleJavaMail factory. For example, two Mailers
         * using {@code company-account} and thirty messages per minute share thirty, not sixty. Their message/recipient rules and burst setting
         * must agree, or construction fails. Closing a participant does not reset the group's recent history.
         * <p>
         * A separate factory or process has independent allowance, even with the same group name. This name does not identify a connection-pool
         * cluster or discover an SMTP account. Without a name, each registered configuration has a private allowance. Naming a group alone
         * does not enable a rate limit. Property: {@code simplejavamail.smtp.ratelimit.group}.
         *
         * @param rateLimitGroup Nonblank sharing name. A later call replaces it; {@link #clearRateLimitGroup()} selects private allowance again.
         */
        @NotNull public Builder withRateLimitGroup(@NotNull final String rateLimitGroup) {
            if (requireNonNull(rateLimitGroup, "rateLimitGroup").isBlank()) {
                throw new IllegalArgumentException("A rate-limit group needs a nonblank name; use clearRateLimitGroup() for private allowance");
            }
            this.rateLimitGroup = rateLimitGroup;
            rateLimitGroupCleared = false;
            return this;
        }
        /** @see #withRateLimitGroup(String) */
        @Nullable public String getRateLimitGroup() { return rateLimitGroup; }
        /**
         * Removes the sharing name, including a captured name, selecting private allowance without changing either rule.
         * @see #withRateLimitGroup(String)
         */
        @NotNull public Builder clearRateLimitGroup() { rateLimitGroup = null; rateLimitGroupCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withRateLimitGroup(String)
         */
        @NotNull public Builder useConfiguredRateLimitGroup() { rateLimitGroup = null; rateLimitGroupCleared = false; return this; }
        /**
         * Allows available rolling-window allowance to be used immediately ({@code true}, the default), or additionally spreads submissions
         * ({@code false}). Thirty messages per minute then gives two seconds between local submission starts; this is an example, not a default.
         * A ten-recipient email at one hundred recipients per minute gives six seconds before the next start. Both configured rules still apply.
         * <p>
         * The first send can start immediately. Slow sends can overlap; there is no extra delay after completion or catch-up credit after idle
         * time. Waiting sends are served in arrival order at the gate, so a larger email can delay smaller ones behind it. This controls local
         * provider invocations, not network bandwidth, server-observed timing or final delivery. Setting only this preference enables no limit.
         * Property: {@code simplejavamail.smtp.ratelimit.allowbursts}.
         *
         * @param rateLimitBurstsAllowed Whether immediate bursts within the rolling ceiling are allowed. A later call replaces the setting.
         */
        @NotNull public Builder withRateLimitBurstsAllowed(final boolean rateLimitBurstsAllowed) { this.rateLimitBurstsAllowed = rateLimitBurstsAllowed; return this; }
        /** @see #withRateLimitBurstsAllowed(boolean) */
        @Nullable public Boolean getRateLimitBurstsAllowed() { return rateLimitBurstsAllowed; }
        /**
         * Restores the published fallback of allowing bursts. This does not enable either rate-limit rule.
         * @see #withRateLimitBurstsAllowed(boolean)
         */
        @NotNull public Builder resetRateLimitBurstsAllowed() { return withRateLimitBurstsAllowed(MailerGenericBuilder.DEFAULT_RATE_LIMIT_BURSTS_ALLOWED); }
        /**
         * Restores captured configuration for this member.
         * @see #withRateLimitBurstsAllowed(boolean)
         */
        @NotNull public Builder useConfiguredRateLimitBurstsAllowed() { rateLimitBurstsAllowed = null; return this; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public SendingLimitsConfig build() { return new SendingLimitsConfig(this); }
    }
}
