package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPool;

import java.util.Properties;
import java.util.function.Supplier;

import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROVIDER_PROPERTY;

/** Preserves the standalone facade's existing canonical/legacy selection without copying aliases into a borrowed Session. */
final class StandaloneOAuth2TokenSource {
    private StandaloneOAuth2TokenSource() { }

    @Nullable
    static Supplier<String> capture(final Session session) {
        final Properties properties = session.getProperties();
        final Object canonicalProvider = properties.get(OAUTH2_TOKEN_PROVIDER_PROPERTY);
        final Object provider = canonicalProvider != null ? canonicalProvider
                : properties.get(SmtpConnectionPool.OAUTH2_TOKEN_PROVIDER_PROPERTY);
        if (provider != null) {
            if (provider instanceof Supplier) {
                @SuppressWarnings("unchecked") final Supplier<String> selected = (Supplier<String>) provider;
                return selected;
            }
            return () -> { throw new IllegalArgumentException("The configured OAuth2 token provider is not a Supplier"); };
        }
        final String canonicalToken = properties.getProperty(OAUTH2_TOKEN_PROPERTY);
        if (canonicalToken != null) {
            return () -> canonicalToken;
        }
        final Object legacyToken = properties.get(SmtpConnectionPool.OAUTH2_TOKEN_PROPERTY);
        return legacyToken == null ? null : () -> (String) legacyToken;
    }
}
