package org.simplejavamail.mailer.internal;

import org.simplejavamail.internal.configresolution.ConfigurationSetting;

/**
 * Logical members after connection-owned projection. Typed and configured aliases target their applicable provider property;
 * SMTP and SMTPS remain distinct, and an unknown property keeps its literal identity without acquiring supported semantics.
 */
final class ConnectionSettings {
    private static final String DOMAIN = "connection";

    private ConnectionSettings() { }

    static ConfigurationSetting<Object> projectedProperty(final String propertyName) {
        return ConfigurationSetting.of(DOMAIN, propertyName);
    }

    static String propertyName(final ConfigurationSetting<Object> setting) {
        if (!projectedProperty(setting.getName()).equals(setting)) {
            throw new IllegalArgumentException("Expected a connection logical setting, received " + setting);
        }
        return setting.getName();
    }
}
