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
import static org.simplejavamail.config.ConfigLoader.Property.CUSTOM_SSLFACTORY_CLASS;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RATE_LIMIT_GROUP;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_LIMIT;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_RECIPIENT_RATE_PERIOD;

/**
 * Checks the selected SMTP registration and its live Session, including a different member of a shared pool cluster.
 * Credentials are checked from the registration that owns them, never inferred from a connection URL.
 * Pure connection requirements remain separate from the completed Mailer's logical ownership facts.
 */
final class LockedSmtpConfiguration {

    private final ConfigurationLocks locks;
    private final LockedSmtpConnectionRequirements connectionRequirements;
    @Nullable private final ServerConfig server;
    private final ProxyConfig proxy;
    private final OperationalConfig operational;
    private final boolean suppliedSslFactoryInstance;

    LockedSmtpConfiguration(final SimpleJavaMailConfig config, @Nullable final ServerConfig server, final ProxyConfig proxy,
            final OperationalConfig operational, @Nullable final TransportStrategy strategy, final Properties resolvedProperties) {
        this.locks = locksOf(config);
        this.server = server;
        this.proxy = proxy;
        this.operational = operational;
        this.suppliedSslFactoryInstance = server != null && server.getCustomSSLFactoryInstance() != null
                || strategy != null && resolvedProperties.containsKey(strategy.propertyNameSSLSocketFactory());
        this.connectionRequirements = new LockedSmtpConnectionRequirements(config, proxy, operational.isTrustAllSSLHost(), strategy);
    }

    boolean hasLocks() { return !locks.isEmpty(); }

    /**
     * Checks the selected registration and its live Session without network activity.
     * Construction has already validated the same pure connection requirements.
     */
    void verifySelected(final LockedSmtpConfiguration selected, final Session session) {
        if (locks.isEmpty()) {
            return;
        }
        connectionRequirements.verifySession(session);
        if (locks.contains(CUSTOM_SSLFACTORY_CLASS) && selected.suppliedSslFactoryInstance) {
            throw locks.conflict(CUSTOM_SSLFACTORY_CLASS, "The selected SMTP registration supplies an SSL factory instance instead of using the locked class. "
                    + "Use a compatible registration or separate these configurations into different pool clusters.");
        }
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
