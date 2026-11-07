package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge.LOOPBACK_HOST;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;

/** Validated physical changes and their per-attempt restoration state share one description. */
final class SessionConfigurationChanges {
    private static final Object ABSENT = new Object();
    private final Map<Object, Object> properties;
    private final boolean applyDebugOutput;
    private final boolean applyDebugEnabled;

    private SessionConfigurationChanges(final Map<Object, Object> properties, final MailerSessionSettings settings) {
        this.properties = Collections.unmodifiableMap(properties);
        this.applyDebugOutput = settings.shouldApplyDebugOutput();
        this.applyDebugEnabled = settings.shouldApplyDebugEnabled();
    }

    static SessionConfigurationChanges describe(final MailerSessionSettings settings, final Properties additional,
            final ProxyConfig proxy, @Nullable final Supplier<String> provider) {
        final Map<Object, Object> properties = new LinkedHashMap<>();
        properties.putAll(additional);
        properties.putAll(settings.getConnectionOverrides());
        if (settings.getShadowedFactoryClassProperty() != null) { properties.put(settings.getShadowedFactoryClassProperty(), null); }
        if (settings.getCustomMailer() == null && proxy.requiresProxy()) {
            final TransportStrategy strategy = settings.getTransportStrategy() == null ? SMTP : settings.getTransportStrategy();
            if (settings.getProxyConfig() == null) {
                properties.put(strategy.propertyNameSocksHost(), proxy.getRemoteProxyHost());
                properties.put(strategy.propertyNameSocksPort(), String.valueOf(proxy.getRemoteProxyPort()));
            }
            if (proxy.requiresAuthentication()) {
                properties.put(strategy.propertyNameSocksHost(), LOOPBACK_HOST);
                properties.put(strategy.propertyNameSocksPort(), String.valueOf(proxy.getProxyBridgePort()));
            }
        }
        if (provider != null) { properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, provider); }
        // The context value is assembled from this attempt's completed runtime facts before application.
        properties.put(SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY, null);
        return new SessionConfigurationChanges(properties, settings);
    }

    Applied capture(final Session session, final boolean restoreCallerState) {
        return new Applied(session, restoreCallerState);
    }

    final class Applied {
        private final Session session;
        private final boolean restoreCallerState;
        private final Map<Object, Object> original = new LinkedHashMap<>();
        private final boolean debug;
        private final PrintStream debugOut;

        private Applied(final Session session, final boolean restoreCallerState) {
            this.session = session;
            this.restoreCallerState = restoreCallerState;
            this.debug = session.getDebug();
            this.debugOut = session.getDebugOut();
            if (restoreCallerState) {
                final Properties target = session.getProperties();
                for (Object key : properties.keySet()) {
                    original.put(key, target.containsKey(key) ? target.get(key) : ABSENT);
                }
            }
        }

        void apply(final SessionDebugConfig debugConfig, final SessionBasedEmailToMimeMessageConverter converter) {
            if (applyDebugOutput) {
                if (debugConfig.getPrinter() != null) { session.setDebugOut(debugConfig.getPrinter()); }
                else if (debugConfig.getOutput() != null) { session.setDebugOut(SessionDebugOutputResolver.resolve(debugConfig.getOutput())); }
            }
            if (applyDebugEnabled) { session.setDebug(Boolean.TRUE.equals(debugConfig.getEnabled())); }
            final Properties target = session.getProperties();
            properties.forEach((key, value) -> {
                if (SessionBasedEmailToMimeMessageConverter.MIMEMESSAGE_CONVERTER_KEY.equals(key)) { target.put(key, converter); }
                else if (value == null) { target.remove(key); }
                else { target.put(key, value); }
            });
        }

        boolean restore(final Throwable failure) {
            if (!restoreCallerState) { return true; }
            boolean restored = true;
            for (Map.Entry<Object, Object> entry : original.entrySet()) {
                try {
                    if (entry.getValue() == ABSENT) { session.getProperties().remove(entry.getKey()); }
                    else { session.getProperties().put(entry.getKey(), entry.getValue()); }
                } catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) { failure.addSuppressed(cleanup); }
                    restored = false;
                }
            }
            try {
                if (session.getDebugOut() != debugOut) { session.setDebugOut(debugOut); }
                if (session.getDebug() != debug) { session.setDebug(debug); }
            } catch (RuntimeException | Error cleanup) {
                if (failure != cleanup) { failure.addSuppressed(cleanup); }
                restored = false;
            }
            return restored;
        }
    }
}
