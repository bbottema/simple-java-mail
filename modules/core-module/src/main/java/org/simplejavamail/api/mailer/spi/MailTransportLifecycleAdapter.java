package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Optional provider integration for stopping connection and SMTP I/O without waiting for the transport's send monitor.
 * Unlike {@link MailTransportAdapter}, this boundary is consulted before connecting. Implementations are discovered through ServiceLoader.
 * Registrations are cached per thread-context class loader, but each lookup invokes the registered constructor or provider factory again.
 * Abort actions are never cached between operations.
 */
public interface MailTransportLifecycleAdapter {

    /** @return Whether this adapter can configure the selected provider on a newly created, library-owned Session. */
    boolean supportsProvider(@NotNull Provider provider);

    /**
     * Initializes provider-specific encoding, protocol hooks and optional connection tracking before a library-owned Session is published or pooled.
     * This also runs for offline MIME preparation; configuring the Session must not connect or start network work.
     * Preserve custom factory selection and explicit settings. An adapter may supply fail-closed defaults and failure-cleanup decorators,
     * but must not claim abort capability for opaque factories through {@link #createAbortAction(Transport)}.
     * Stable connection-resource controllers may be installed here without starting workers; their lifetime follows physical connection disposal,
     * not a borrowed lease or one invoking Mailer. Application-supplied executors remain application-owned.
     * Never use this method to modify caller-owned Sessions or to store per-request state in Session properties.
     */
    void configureOwnedSession(@NotNull Session session, @NotNull String protocol);

    /**
     * Returns an idempotent, monitor-independent abort action, available before the first connect call, or empty when unsupported.
     * Creating the action must not request cancellation. Invoking it must promptly close current network resources and latch the request
     * so later socket creation cannot escape it. The owner must fence the action against lease reuse; it is not proof of SMTP non-acceptance.
     */
    @NotNull Optional<Runnable> createAbortAction(@NotNull Transport transport);
}
