package org.simplejavamail.internal.mailprovider.angus;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.security.GeneralSecurityException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;

/** Sets owned-session defaults and adds failure cleanup without changing which application factory Angus selects. */
final class AngusSocketFactories {
    private AngusSocketFactories() {
    }

    static void configureAuthenticatedSocks(final Properties properties, final String prefix, final AuthenticatedSocksSocketProvider provider) {
        properties.put(prefix + ".socketFactory.transport", new AngusSocketFactory(properties, prefix, provider));
    }

    static void configure(final Properties properties, final String prefix) {
        final AngusSocketFactory direct = AngusSocketFactory.find(properties, prefix);
        if (direct != null && direct.isDirectSocksFactory()) {
            defaultFallbackToFalse(properties, prefix);
            // The native transport hook owns setup cleanup and keeps TLS instance/class trust precedence intact.
            return;
        }
        if (hasCustomFactory(properties, prefix)) {
            defaultFallbackToFalse(properties, prefix);
            protectTlsFactory(properties, prefix + ".socketFactory");
            protectTlsFactory(properties, prefix + ".ssl.socketFactory");
        } else {
            properties.put(prefix + ".socketFactory", new AngusSocketFactory(properties, prefix));
            // Cancellation must not make SocketFetcher replace our tracked socket with an untracked one.
            properties.setProperty(prefix + ".socketFactory.fallback", "false");
        }
    }

    private static void defaultFallbackToFalse(final Properties properties, final String prefix) {
        if (!properties.containsKey(prefix + ".socketFactory.fallback") && properties.getProperty(prefix + ".socketFactory.fallback") == null) {
            properties.setProperty(prefix + ".socketFactory.fallback", "false");
        }
    }

    private static boolean hasCustomFactory(final Properties properties, final String prefix) {
        return properties.get(prefix + ".socketFactory") != null || properties.getProperty(prefix + ".socketFactory.class") != null
                || properties.get(prefix + ".ssl.socketFactory") != null || properties.getProperty(prefix + ".ssl.socketFactory.class") != null
                || properties.get(prefix + ".socketFactory.transport") != null || properties.getProperty(prefix + ".socketFactory.transport.class") != null;
    }

    static SocketFactory resolveFactory(final String className, final String propertyName) throws IOException {
        try {
            final SocketFactory resolved = invokeFactory(className);
            if (resolved != null) {
                return resolved;
            }
            throw new IOException(propertyName + " did not return a SocketFactory");
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof IOException) { throw (IOException) cause; }
            if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
            if (cause instanceof Error) { throw (Error) cause; }
            throw new IOException("Couldn't resolve the socket factory configured by " + propertyName, cause);
        } catch (ReflectiveOperationException | SecurityException | ClassCastException failure) {
            throw new IOException("Couldn't resolve the socket factory configured by " + propertyName + ". Check its public static getDefault() method "
                    + "and export its package to org.simplejavamail.mailprovider.angus in modular applications.", failure);
        }
    }

    @Nullable
    static SocketFactory resolveLegacyFactory(final String className, final String propertyName) throws IOException {
        try {
            return invokeFactory(className);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof IOException) { throw (IOException) cause; }
            if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
            // Native legacy resolution catches the reflective wrapper, including when getDefault itself throws an Error.
            throw new IOException("Couldn't resolve the socket factory configured by " + propertyName, failure);
        } catch (ReflectiveOperationException | SecurityException | ClassCastException failure) {
            throw new IOException("Couldn't resolve the socket factory configured by " + propertyName, failure);
        }
    }

    @Nullable
    private static SocketFactory invokeFactory(final String className) throws ReflectiveOperationException {
        final Method method = loadFactoryClass(className).getMethod("getDefault");
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("getDefault() must be public and static");
        }
        return (SocketFactory) method.invoke(null);
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
