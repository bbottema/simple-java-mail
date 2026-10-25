package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;

import java.util.Properties;

/** Direct storage and provider-visible values are distinct, especially when a non-string entry hides an inherited String. */
final class SessionPropertyObservation<T> {
    private final boolean directEntry;
    @Nullable private final Object directValue;
    @Nullable private final T providerValue;

    private SessionPropertyObservation(final boolean directEntry, @Nullable final Object directValue, @Nullable final T providerValue) {
        this.directEntry = directEntry;
        this.directValue = directValue;
        this.providerValue = providerValue;
    }

    static SessionPropertyObservation<String> readString(final Properties properties, final String key) {
        return new SessionPropertyObservation<>(properties.containsKey(key), properties.get(key), properties.getProperty(key));
    }

    static SessionPropertyObservation<Object> readValue(final Properties properties, final String key) {
        return new SessionPropertyObservation<>(properties.containsKey(key), properties.get(key), ConnectionPropertyValues.visibleValue(properties, key));
    }

    boolean hasDirectEntry() { return directEntry; }
    @Nullable Object directValueOrNull() { return directValue; }
    @Nullable T providerValueOrNull() { return providerValue; }

    static Properties snapshot(final Properties source) {
        final Properties inheritedView = (Properties) source.clone();
        // Clearing the clone's entries exposes inherited Strings without calling a user-defined clear/putAll implementation.
        inheritedView.entrySet().clear();
        final Properties inherited = new Properties();
        inheritedView.stringPropertyNames().forEach(key -> inherited.setProperty(key, inheritedView.getProperty(key)));
        final Properties snapshot = new Properties(inherited);
        snapshot.putAll(source);
        return snapshot;
    }
}
