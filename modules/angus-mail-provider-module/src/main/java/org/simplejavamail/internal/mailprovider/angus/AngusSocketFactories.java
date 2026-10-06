package org.simplejavamail.internal.mailprovider.angus;

import org.jetbrains.annotations.Nullable;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.security.GeneralSecurityException;
import java.util.Properties;

/** Sets owned-session defaults and adds failure cleanup without changing which application factory Angus selects. */
final class AngusSocketFactories {
    private AngusSocketFactories() {
    }

    static void configure(final Properties properties, final String prefix) {
        if (hasCustomFactory(properties, prefix)) {
            if (!properties.containsKey(prefix + ".socketFactory.fallback") && properties.getProperty(prefix + ".socketFactory.fallback") == null) {
                properties.setProperty(prefix + ".socketFactory.fallback", "false");
            }
            protectTlsFactory(properties, prefix + ".socketFactory");
            protectTlsFactory(properties, prefix + ".ssl.socketFactory");
        } else {
            properties.put(prefix + ".socketFactory", new AngusSocketFactory(properties, prefix));
            // Cancellation must not make SocketFetcher replace our tracked socket with an untracked one.
            properties.setProperty(prefix + ".socketFactory.fallback", "false");
        }
    }

    private static boolean hasCustomFactory(final Properties properties, final String prefix) {
        return properties.get(prefix + ".socketFactory") != null || properties.getProperty(prefix + ".socketFactory.class") != null
                || properties.get(prefix + ".ssl.socketFactory") != null || properties.getProperty(prefix + ".ssl.socketFactory.class") != null;
    }

    private static void protectTlsFactory(final Properties properties, final String key) {
        try {
            final Object instance = properties.get(key);
            if (instance instanceof SSLSocketFactory && !(instance instanceof AngusSslSocketFactory)) {
                properties.put(key, new AngusSslSocketFactory((SSLSocketFactory) instance, key));
            } else if (!(instance instanceof SocketFactory)) {
                final Class<?> factoryClass = findFactoryClass(properties.getProperty(key + ".class"));
                if (factoryClass != null && SSLSocketFactory.class.isAssignableFrom(factoryClass)) {
                    properties.put(key, new AngusSslSocketFactory(factoryClass, key));
                }
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Couldn't initialize cleanup for the custom SSL socket factory. "
                    + "Check the TLS provider configuration reported by the cause.", failure);
        }
    }

    @Nullable
    private static Class<?> findFactoryClass(@Nullable final String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        try {
            return loadFactoryClass(className);
        } catch (ClassNotFoundException | LinkageError | SecurityException unavailable) {
            // Leave a bad class untouched: Angus reports its original failure when connecting, not during offline preparation.
            return null;
        }
    }

    /** Match Angus's context-loader preference without triggering the application's static initializer. */
    static Class<?> loadFactoryClass(final String className) throws ClassNotFoundException {
        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // The provider loader is the same fallback used by SocketFetcher.
            }
        }
        return Class.forName(className, false, AngusSocketFactories.class.getClassLoader());
    }
}
