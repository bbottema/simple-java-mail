package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import java.time.Duration;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.MAILER;

/** Typed CLI input for the ordinary retry-policy resolver; not a second public Java configuration API. */
@Cli.BuilderApiNode(builderApiType = MAILER)
public interface CliMailSendRetryBuilder {
    /**
     * Enables safely replayable temporary SMTP retries with this maximum total invocation count, including the first.
     * Requires a retry delay too; no implicit retry schedule. Applies when this Mailer is the selected SMTP destination.
     * @param attempts Total provider invocations; at least two.
     * @see MailSendRetryPolicy.Builder#withMaximumAttempts(int)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryAttempts(int attempts);

    /**
     * Waits after completed cleanup before replaying a safely refused email. Requires a total attempt count too.
     * @param delay Positive ISO-8601 duration, such as PT30S; choose it for the selected SMTP service.
     * @see MailSendRetryPolicy.Builder#withRetryDelay(Duration)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryDelay(@NotNull Duration delay);

    /**
     * Increases the retry delay after successive temporary refusals, without exceeding a supplied cap.
     * @param multiplier Integer greater than one; omitted means fixed delay.
     * @param maximumDelay Positive ISO-8601 cap at least as large as the initial retry delay.
     * @see MailSendRetryPolicy.Builder#withRetryBackoff(int, Duration)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryBackoff(int multiplier, @NotNull Duration maximumDelay);

    /**
     * Varies retry delays uniformly in both directions by a fractional amount, within any configured cap.
     * @param fraction Finite fraction from zero to less than one; 0.2 means up to 20 percent variation. Default zero.
     * @see MailSendRetryPolicy.Builder#withRetryJitter(double)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryJitter(double fraction);

    /**
     * Bounds suspended asynchronous retries on the initiating Mailer; does not enable retries. Default 100.
     * @param capacity Positive maximum number of suspended retries.
     * @see MailSendRetryPolicy.Builder#withWaitingCapacity(int)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryWaitingCapacity(int capacity);

    /**
     * Restores the built-in waiting capacity.
     * @see MailSendRetryPolicy.Builder#resetWaitingCapacity()
     */
    @NotNull MailerGenericBuilder<?> resetMailSendRetryWaitingCapacity();

    /**
     * Bounds each serialized retry message on the initiating Mailer, including encoded attachments. Default 32 MiB.
     * @param bytes Positive maximum replay-message size that fits a Java byte array.
     * @see MailSendRetryPolicy.Builder#withMaximumMessageSize(long)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryMaximumMessageSize(long bytes);

    /**
     * Restores the built-in replay-message size limit.
     * @see MailSendRetryPolicy.Builder#resetMaximumMessageSize()
     */
    @NotNull MailerGenericBuilder<?> resetMailSendRetryMaximumMessageSize();

    /**
     * Bounds aggregate replay-content bytes on the initiating Mailer. Default 128 MiB; not a JVM heap limit.
     * @param bytes Positive budget at least as large as the maximum replay-message size.
     * @see MailSendRetryPolicy.Builder#withMemoryLimit(long)
     */
    @NotNull MailerGenericBuilder<?> withMailSendRetryMemoryLimit(long bytes);

    /**
     * Restores the built-in aggregate replay-content budget.
     * @see MailSendRetryPolicy.Builder#resetMemoryLimit()
     */
    @NotNull MailerGenericBuilder<?> resetMailSendRetryMemoryLimit();
}
