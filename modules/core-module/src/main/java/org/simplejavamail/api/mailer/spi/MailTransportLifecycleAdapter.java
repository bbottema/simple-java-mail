package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Lets a provider observe connection setup or stop network I/O without waiting for the transport's send monitor.
 * These capabilities are independent: a provider can support progress, aborting, both or neither.
 * <p>
 * Unlike {@link MailTransportAdapter}, this adapter is consulted before connecting. Implementations are discovered through ServiceLoader.
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

    /**
     * Attaches progress reporting just before this transport connects. This method is not called when borrowing an already connected transport,
     * since its earlier setup is not work performed for the current email. Return {@code null} if your provider cannot observe setup;
     * connecting must remain possible without progress or abort support.
     * <p>
     * Use the supplied sink to report setup work; it records progress without running application callbacks. Attaching observation must not
     * connect, close or replace the transport, or wait for network work. Keep the sink out of Session properties and release it when the
     * registration closes so it cannot follow the transport into a later use.
     *
     * @param transport The transport about to establish a physical connection.
     * @param progress The sink for this connection setup only.
     * @return A registration that detaches observation, or {@code null} when setup observation is unsupported.
     */
    @Nullable
    default ConnectionProgressRegistration observeConnectionSetup(@NotNull Transport transport, @NotNull MailTransportProgress progress) {
        return null;
    }

    /** Detaches connection-setup observation when it is no longer needed, without taking ownership of the transport. */
    interface ConnectionProgressRegistration extends AutoCloseable {
        /**
         * Stops observing setup without closing the transport or waiting for network I/O.
         * Calling this more than once must be harmless, and it must not throw.
         */
        @Override void close();
    }
}
