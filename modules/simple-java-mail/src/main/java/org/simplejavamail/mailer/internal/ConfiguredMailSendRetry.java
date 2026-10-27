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
    static MailSendRetryPolicy policy(final SimpleJavaMailConfig config, final MailSendRetryPolicy choices) {
        final Integer attempts = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RETRY_ATTEMPTS,
                MailerConfigurationResolver.choice(choices.getMaximumAttempts(), choices.isMaximumAttemptsCleared()), null, "MailSendRetryPolicy.withMaximumAttempts/clearMaximumAttempts");
        final Duration delay = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RETRY_DELAY,
                MailerConfigurationResolver.choice(choices.getRetryDelay(), choices.isRetryDelayCleared()), null, "MailSendRetryPolicy.withRetryDelay/clearRetryDelay");
        requireCompleteConfiguredSchedule(config, choices, attempts, delay);

        return new MailSendRetryPolicy(attempts, delay,
                MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RETRY_BACKOFF_MULTIPLIER,
                        choices.getRetryBackoffMultiplier(), DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER),
                MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RETRY_MAXIMUM_DELAY,
                        MailerConfigurationResolver.choice(choices.getMaximumRetryDelay(), choices.isMaximumRetryDelayCleared()), null,
                        "MailSendRetryPolicy.withRetryBackoff/clearMaximumRetryDelay"),
                MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RETRY_JITTER,
                        choices.getRetryJitter(), DEFAULT_MAIL_SEND_RETRY_JITTER),
                MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY,
                        choices.getWaitingCapacity(), DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY),
                MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE,
                        choices.getMaximumMessageSize(), DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE),
                MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT,
                        choices.getMemoryLimit(), DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT));
    }

    /** CLI changes extend supplied Java choices before factory inheritance and complete-policy validation. */
    @NotNull
    static MailSendRetryPolicy withOverrides(final MailSendRetryPolicy current, final SimpleJavaMailConfig overrides) {
        final MailSendRetryPolicy.Builder choices = current.toBuilder();
        if (overrides.hasProperty(ConfigKeys.SMTP_RETRY_ATTEMPTS)) {
            choices.withMaximumAttempts(overrides.get(ConfigKeys.SMTP_RETRY_ATTEMPTS));
        }
        if (overrides.hasProperty(ConfigKeys.SMTP_RETRY_DELAY)) {
            choices.withRetryDelay(overrides.get(ConfigKeys.SMTP_RETRY_DELAY));
        }
        if (overrides.hasProperty(ConfigKeys.SMTP_RETRY_BACKOFF_MULTIPLIER)) {
            choices.withRetryBackoff(overrides.get(ConfigKeys.SMTP_RETRY_BACKOFF_MULTIPLIER),
                    overrides.get(ConfigKeys.SMTP_RETRY_MAXIMUM_DELAY));
        }
        if (overrides.hasProperty(ConfigKeys.SMTP_RETRY_JITTER)) {
            choices.withRetryJitter(overrides.get(ConfigKeys.SMTP_RETRY_JITTER));
        }
        if (overrides.hasProperty(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY)) {
            choices.withWaitingCapacity(overrides.get(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY));
        }
        if (overrides.hasProperty(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE)) {
            choices.withMaximumMessageSize(overrides.get(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE));
        }
        if (overrides.hasProperty(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT)) {
            choices.withMemoryLimit(overrides.get(ConfigKeys.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT));
        }
        return choices.build();
    }

    /** Resource properties alone may leave retries disabled; any explicit schedule property requires both schedule inputs. */
    private static void requireCompleteConfiguredSchedule(final SimpleJavaMailConfig config, final MailSendRetryPolicy choices,
            @Nullable final Integer attempts, @Nullable final Duration delay) {
        final boolean explicitlyDisabled = choices.isMaximumAttemptsCleared() && choices.isRetryDelayCleared();
        if (!explicitlyDisabled && hasConfiguredSchedule(config) && (attempts == null || delay == null)) {
            throw new IllegalArgumentException("Automatic mail-send retries need both simplejavamail.smtp.retry.attempts and simplejavamail.smtp.retry.delay. "
                    + "Supply the total attempts and a positive delay in properties or the retry policy, or use clearRetrySchedule() to disable retries.");
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
