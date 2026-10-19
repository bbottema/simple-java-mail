package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;

import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.MAILER;

/** Preserves numeric and scalar CLI syntax while Java uses grouped configuration and Duration values. */
@Cli.BuilderApiNode(builderApiType = MAILER)
public interface CliMailerConfigurationBuilder {
    /**
     * Sets Jakarta Mail debug logging without changing its selected output.
     * @param enabled Whether debug logging is enabled.
     */
    MailerGenericBuilder<?> withDebugLogging(@NotNull Boolean enabled);

    /**
     * Selects a built-in debug destination without changing logging activation.
     * @param output STDOUT, STDERR or SLF4J.
     */
    MailerGenericBuilder<?> withDebugOutput(@NotNull SessionDebugOutput output);

    /**
     * Enables or disables telemetry through the optional module, using the application-global SDK when enabled.
     * @param enabled Whether telemetry is enabled.
     */
    MailerGenericBuilder<?> withMailSendTelemetryEnabled(boolean enabled);

    /** Restores the built-in disabled telemetry setting. */
    MailerGenericBuilder<?> resetMailSendTelemetryEnabled();

    /**
     * Lets traces identify which SMTP relay handled a submission by including its logical host and port.
     * Leave this disabled if infrastructure names should stay private. Even when enabled, those details never become metric labels,
     * and email addresses, content, credentials, message identifiers and raw exception text remain excluded.
     * <p>
     * This choice replaces {@code simplejavamail.telemetry.smtp.endpointattributes} from the factory's captured configuration.
     * Without either choice, the default is false. If the configuration owner fixes a different value through
     * {@code simplejavamail.locked.telemetry.smtp.endpointattributes}, building the Mailer fails with that conflict.
     * <p>
     * The setting does not install an SDK or enable telemetry by itself; use {@link #withMailSendTelemetryEnabled(boolean)}
     * to enable reporting. Resetting this permission selects the built-in default without changing activation.
     *
     * @param enabled Whether the selected logical SMTP host and port may appear in trace attributes.
     */
    MailerGenericBuilder<?> withMailSendTelemetrySmtpEndpointAttributes(boolean enabled);

    /** Restores the built-in disabled endpoint-attribute permission. */
    MailerGenericBuilder<?> resetMailSendTelemetrySmtpEndpointAttributes();

    /**
     * Sets connection, read and write socket timeouts in milliseconds.
     * @param timeout Timeout in milliseconds; zero retains the provider's unlimited setting.
     */
    MailerGenericBuilder<?> withSessionTimeout(@NotNull Integer timeout);

    /**
     * Sets owned worker keep-alive time, with or without the optional batch module.
     * @param keepAliveTime Keep-alive in milliseconds; zero keeps core workers alive.
     */
    MailerGenericBuilder<?> withThreadPoolKeepAliveTime(@NotNull Integer keepAliveTime);

    /**
     * Bounds caller-side waiting for capacity under WAIT_FOR_CAPACITY.
     * @param waitTimeoutMillis Positive admission-wait limit in milliseconds.
     */
    MailerGenericBuilder<?> withAsyncQueueWaitTimeoutMillis(int waitTimeoutMillis);

    /**
     * Bounds waiting for a pooled SMTP connection.
     * @param timeoutMillis Non-negative milliseconds; zero performs a non-waiting claim.
     */
    MailerGenericBuilder<?> withConnectionPoolClaimTimeoutMillis(@NotNull Integer timeoutMillis);

    /** Restores the built-in connection-claim timeout. */
    MailerGenericBuilder<?> resetConnectionPoolClaimTimeoutMillis();

    /**
     * Sets idle expiration for available pooled SMTP connections.
     * @param expireAfterMillis Non-negative milliseconds; zero disables idle expiration.
     */
    MailerGenericBuilder<?> withConnectionPoolExpireAfterMillis(@NotNull Integer expireAfterMillis);

    /** Restores the built-in idle-expiration duration. */
    MailerGenericBuilder<?> resetConnectionPoolExpireAfterMillis();

    /**
     * Sets the creation-age threshold at which an available pooled connection becomes eligible for retirement.
     * @param expireAfterCreationMillis Positive age in milliseconds; active connections are not interrupted.
     */
    MailerGenericBuilder<?> withConnectionPoolExpireAfterCreationMillis(@NotNull Integer expireAfterCreationMillis);

    /** Removes the optional connection creation-age expiration. */
    MailerGenericBuilder<?> clearConnectionPoolExpireAfterCreationMillis();

    /** Restores captured ordinary email defaults by removing an explicitly supplied template. */
    MailerGenericBuilder<?> clearEmailDefaults();
}
