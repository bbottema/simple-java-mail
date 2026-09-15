package org.simplejavamail.api.mailer.spi;

import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.SmtpConnectionReport;

/**
 * Optional ServiceLoader boundary for inspecting a fresh SMTP connection without sending or borrowing a pool lease.
 * Separate from submission and abort adapters: inspecting a connection does not extend either send contract.
 * Register the implementation in {@code META-INF/services/org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter}
 * on the classpath, or with a {@code provides ... with ...} declaration in its module descriptor.
 */
public interface SmtpConnectionProbeAdapter {
    /** Match only providers whose connection semantics this adapter preserves, not arbitrary subclasses. */
    boolean supportsProvider(@NotNull Provider provider);

    /**
     * The supplied Session is private to this probe. It already contains the selected authentication policy and
     * credential bridge; do not substitute another provider or copy it again. Create and close one dedicated transport,
     * invoke the connector exactly once, and return safe partial facts on connection/cleanup failure.
     * The connector retains the library's regular password/OAuth2 behavior without making this module resolve tokens.
     * <p>
     * Omit unavailable optional facts and explain the limitation in the report's warnings. In particular, an unavailable
     * EHLO reply is an absent snapshot, not an empty snapshot claiming the server advertised no extensions.
     * A successful connection can still have unavailable capability or TLS details.
     * Report values escape display text, but do not recognize arbitrary secrets: never copy raw exception messages,
     * credentials or AUTH exchanges into them.
     */
    @NotNull
    SmtpConnectionReport probe(@NotNull Session session, boolean authenticate, @NotNull Connector connector);

    /** Library-supplied connection action, not an application callback or an SMTP command observer. */
    @FunctionalInterface
    interface Connector {
        void connect(@NotNull Transport transport) throws MessagingException;
    }
}
