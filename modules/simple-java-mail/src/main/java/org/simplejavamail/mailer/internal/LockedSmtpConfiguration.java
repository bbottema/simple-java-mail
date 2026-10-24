package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.ServerConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.Properties;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_SOCKS5BRIDGE_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_LEGACY_CONTENT_SUPPORT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_MESSAGE_RATE_LIMIT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_MESSAGE_RATE_PERIOD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RATE_LIMIT_ALLOW_BURSTS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RATE_LIMIT_GROUP;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_LIMIT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_PERIOD;

/** Retains a completed Mailer's logical ownership facts alongside its pure, reusable Session requirements. */
final class LockedSmtpConfiguration {

    private final ConfigurationLocks locks;
    private final LockedSmtpConnectionRequirements connectionRequirements;
    @Nullable private final ServerConfig server;
    private final ProxyConfig proxy;
    private final OperationalConfig operational;

    LockedSmtpConfiguration(final SimpleJavaMailConfig config, @Nullable final ServerConfig server, final ProxyConfig proxy,
            final OperationalConfig operational, @Nullable final TransportStrategy strategy) {
        this.locks = locksOf(config);
        this.server = server;
        this.proxy = proxy;
        this.operational = operational;
        this.connectionRequirements = new LockedSmtpConnectionRequirements(config, proxy, operational.isTrustAllSSLHost(), strategy);
    }

    void verifyAdditionalProperties(final Properties properties) { connectionRequirements.verifyAdditionalProperties(properties); }
    void verifyCallerOwnedSession(final Session session) { connectionRequirements.verifyCallerOwnedSession(session); }
    void applyLockedExtraProperties(final Session session) { connectionRequirements.applyLockedExtraProperties(session); }
    boolean hasLocks() { return !locks.isEmpty(); }

    /** Checks the selected registration and its live Session; construction has already validated the same pure connection requirements. */
    void verifySelected(final LockedSmtpConfiguration selected, final Session session) {
        if (locks.isEmpty()) {
            return;
        }
        connectionRequirements.verifySession(session);
        verifySelectedValue(SMTP_PASSWORD, selected.server == null ? null : selected.server.getPassword());
        if (locks.contains(SMTP_PASSWORD) && session.getProperties().containsKey(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY)) {
            throw locks.conflict(SMTP_PASSWORD, "The selected SMTP Session uses a runtime access-token provider, which can replace this Mailer's locked credential. "
                    + "Remove that provider from the selected SMTP configuration, "
                    + "or remove the SMTP credential lock from the configuration source if credentials should be supplied dynamically.");
        }
        verifySelectedValue(PROXY_HOST, selected.proxy.getRemoteProxyHost());
        verifySelectedValue(PROXY_PORT, selected.proxy.getRemoteProxyPort());
        verifySelectedValue(PROXY_USERNAME, selected.proxy.getUsername());
        verifySelectedValue(PROXY_PASSWORD, selected.proxy.getPassword());
        verifySelectedValue(PROXY_SOCKS5BRIDGE_PORT, selected.proxy.getProxyBridgePort());
        verifySelectedValue(SMTP_LEGACY_CONTENT_SUPPORT, selected.operational.isLegacySmtpContentSupportEnabled());
        verifySelectedSendingLimits(selected.operational);
    }

    private void verifySelectedSendingLimits(final OperationalConfig selected) {
        verifySelectedValue(SMTP_RATE_LIMIT_GROUP, selected.getRateLimitGroup());
        verifySelectedValue(SMTP_MESSAGE_RATE_LIMIT, selected.getMessageRateLimit() == null ? null : selected.getMessageRateLimit().getCount());
        verifySelectedValue(SMTP_MESSAGE_RATE_PERIOD, selected.getMessageRateLimit() == null ? null : selected.getMessageRateLimit().getPeriod());
        verifySelectedValue(SMTP_RECIPIENT_RATE_LIMIT, selected.getRecipientRateLimit() == null ? null : selected.getRecipientRateLimit().getCount());
        verifySelectedValue(SMTP_RECIPIENT_RATE_PERIOD, selected.getRecipientRateLimit() == null ? null : selected.getRecipientRateLimit().getPeriod());
        verifySelectedValue(SMTP_RATE_LIMIT_ALLOW_BURSTS, selected.isRateLimitBurstsAllowed());
    }

    private void verifySelectedValue(final Property property, @Nullable final Object actual) {
        locks.verify(property, actual, "The selected SMTP configuration does not match this Mailer's locked setting. "
                + "Make the selected Mailer's configuration agree with the lock, or put incompatible configurations in separate pool clusters. "
                + "Alternatively, change this lock in its configuration source.");
    }
}
