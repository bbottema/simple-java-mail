package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigKeys;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.time.Duration;

import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_JITTER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT;

/** One policy resolver shared by captured properties and typed CLI input; no runtime counters or defaults in configuration getters. */
final class ConfiguredMailSendRetry {
    private ConfiguredMailSendRetry() { }

    @NotNull
    static MailSendRetryPolicy policy(final SimpleJavaMailConfig config) {
        return resolve(config, null);
    }

    /** CLI changes extend the builder's current value; clearing or replacing it must not resurrect the original property-loaded schedule. */
    @NotNull
    static MailSendRetryPolicy withOverrides(final MailSendRetryPolicy current, final SimpleJavaMailConfig overrides) {
        return resolve(overrides, current);
    }

    private static MailSendRetryPolicy resolve(final SimpleJavaMailConfig config, @Nullable final MailSendRetryPolicy current) {
        final Integer attempts = config.valueOrProperty(null, ConfigKeys.SMTP_RETRY_ATTEMPTS, current == null ? null : current.getMaximumAttempts());
        final Duration delay = config.valueOrProperty(null, ConfigKeys.SMTP_RETRY_DELAY, current == null ? null : current.getRetryDelay());
        requireCompleteConfiguredSchedule(config, attempts, delay);

        return new MailSendRetryPolicy(attempts, delay,
                config.valueOrProperty(null, ConfigKeys.SMTP_RETRY_BACKOFF_MULTIPLIER,
                        current == null ? DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER : current.getRetryBackoffMultiplier()),
                config.valueOrProperty(null, ConfigKeys.SMTP_RETRY_MAXIMUM_DELAY, current == null ? null : current.getMaximumRetryDelay()),
                config.valueOrProperty(null, ConfigKeys.SMTP_RETRY_JITTER, current == null ? DEFAULT_MAIL_SEND_RETRY_JITTER : current.getRetryJitter()),
                config.valueOrProperty(null, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY,
                        current == null ? DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY : current.getWaitingCapacity()),
                config.valueOrProperty(null, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE,
                        current == null ? DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE : current.getMaximumMessageSize()),
                config.valueOrProperty(null, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT,
                        current == null ? DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT : current.getMemoryLimit()));
    }

    /** Resource properties alone may leave retries disabled; any explicit schedule property requires both schedule inputs. */
    private static void requireCompleteConfiguredSchedule(final SimpleJavaMailConfig config,
            @Nullable final Integer attempts, @Nullable final Duration delay) {
        if (hasConfiguredSchedule(config) && (attempts == null || delay == null)) {
            throw new IllegalArgumentException("Automatic mail-send retries need both simplejavamail.smtp.retry.attempts and simplejavamail.smtp.retry.delay. "
                    + "Supply the total attempts and a positive ISO-8601 delay such as PT30S, or remove the retry policy properties to disable retries.");
        }
    }

    private static boolean hasConfiguredSchedule(final SimpleJavaMailConfig config) {
        return config.asMap().keySet().stream().anyMatch(ConfiguredMailSendRetry::isScheduleProperty);
    }

    static void verifyLocks(final ConfigurationLocks locks, final MailSendRetryPolicy policy) {
        locks.verify(Property.SMTP_RETRY_ATTEMPTS, policy.getMaximumAttempts());
        locks.verify(Property.SMTP_RETRY_DELAY, policy.getRetryDelay());
        locks.verify(Property.SMTP_RETRY_BACKOFF_MULTIPLIER, policy.getRetryBackoffMultiplier());
        locks.verify(Property.SMTP_RETRY_MAXIMUM_DELAY, policy.getMaximumRetryDelay());
        locks.verify(Property.SMTP_RETRY_JITTER, policy.getRetryJitter());
        locks.verify(Property.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY, policy.getWaitingCapacity());
        locks.verify(Property.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, policy.getMaximumMessageSize());
        locks.verify(Property.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT, policy.getMemoryLimit());
    }

    private static boolean isScheduleProperty(final Property property) { return property.key().startsWith("simplejavamail.smtp.retry."); }
}
