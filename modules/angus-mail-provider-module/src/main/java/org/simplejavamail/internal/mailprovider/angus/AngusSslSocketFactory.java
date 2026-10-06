package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Closes the connected socket if an application TLS factory fails before returning its wrapper to Angus.
 * Angus still performs the handshake, trust checks and protocol configuration. Class-based factories initialize only when used.
 * The MailSSLSocketFactory subtype keeps Angus's post-handshake trust hook active; all TLS work uses the original factory.
 */
final class AngusSslSocketFactory extends MailSSLSocketFactory {
    @Nullable private final SSLSocketFactory instance;
    @Nullable private final String factoryClassName;
    private final String propertyName;
    // A class can return a different factory per connection. Weak keys also cover sockets rejected before the trust hook runs.
    private final Map<SSLSocket, SSLSocketFactory> socketFactories = Collections.synchronizedMap(new WeakHashMap<>());

    AngusSslSocketFactory(final SSLSocketFactory instance, final String propertyName) throws GeneralSecurityException {
        this.instance = instance;
        this.factoryClassName = null;
        this.propertyName = propertyName;
    }

    AngusSslSocketFactory(final Class<?> factoryClass, final String propertyName) throws GeneralSecurityException {
        this.instance = null;
        this.factoryClassName = factoryClass.getName();
        this.propertyName = propertyName;
    }

    @Override
    public Socket createSocket(final Socket socket, final String host, final int port, final boolean autoClose) throws IOException {
        try {
            final SSLSocketFactory factory = delegate();
            return retainTrustFactory(factory, factory.createSocket(socket, host, port, autoClose));
        } catch (IOException | RuntimeException | Error failure) {
            if (autoClose) {
                closeFailedSocket(socket, failure);
            }
            throw failure;
        }
    }

    @Override
    public Socket createSocket(final Socket socket, final InputStream consumed, final boolean autoClose) throws IOException {
        try {
            final SSLSocketFactory factory = delegate();
            return retainTrustFactory(factory, factory.createSocket(socket, consumed, autoClose));
        } catch (IOException | RuntimeException | Error failure) {
            if (autoClose) {
                closeFailedSocket(socket, failure);
            }
            throw failure;
        }
    }

    private Socket retainTrustFactory(final SSLSocketFactory factory, final Socket socket) {
        if (socket instanceof SSLSocket) {
            socketFactories.put((SSLSocket) socket, factory);
        }
        return socket;
    }

    @Override
    public boolean isServerTrusted(final String host, final SSLSocket socket) {
        final SSLSocketFactory factory = socketFactories.remove(socket);
        if (factory == null) {
            return false;
        }
        if (factory instanceof MailSSLSocketFactory) {
            return ((MailSSLSocketFactory) factory).isServerTrusted(host, socket);
        }
        // Ordinary factories already apply their trust manager during Angus's handshake.
        return true;
    }

    private static void closeFailedSocket(final Socket socket, final Throwable failure) {
        try {
            socket.close();
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private SSLSocketFactory delegate() {
        if (instance != null) {
            return instance;
        }
        try {
            final Method factoryMethod = AngusSocketFactories.loadFactoryClass(factoryClassName).getMethod("getDefault");
            if (!Modifier.isStatic(factoryMethod.getModifiers())) {
                throw new NoSuchMethodException("getDefault() must be public and static");
            }
            final Object resolved = factoryMethod.invoke(null);
            if (resolved instanceof SSLSocketFactory) {
                return (SSLSocketFactory) resolved;
            }
            throw new IllegalStateException("The class configured by '" + propertyName + ".class' did not return an SSLSocketFactory. "
                    + "Check its public static getDefault() method, or supply an instance with withCustomSSLFactoryInstance().");
        } catch (InvocationTargetException failure) {
            return rethrowFactoryFailure(failure.getCause());
        } catch (ReflectiveOperationException | SecurityException failure) {
            throw new IllegalStateException("Couldn't use the SSL factory configured by '" + propertyName + ".class'. "
                    + "Check its public static getDefault() method. For a modular application, export its package to "
                    + "org.simplejavamail.mailprovider.angus, or supply an instance with withCustomSSLFactoryInstance().", failure);
        }
    }

    private static SSLSocketFactory rethrowFactoryFailure(final Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException("The configured SSL factory's getDefault() method failed. "
                + "Check the cause in your factory, or supply a working instance with withCustomSSLFactoryInstance().", failure);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate().getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate().getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        final SSLSocketFactory factory = delegate();
        return retainTrustFactory(factory, factory.createSocket());
    }

    @Override
    public Socket createSocket(final String host, final int port) throws IOException {
        final SSLSocketFactory factory = delegate();
        return retainTrustFactory(factory, factory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port) throws IOException {
        final SSLSocketFactory factory = delegate();
        return retainTrustFactory(factory, factory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(final String host, final int port, final InetAddress localHost, final int localPort) throws IOException {
        final SSLSocketFactory factory = delegate();
        return retainTrustFactory(factory, factory.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port, final InetAddress localHost, final int localPort) throws IOException {
        final SSLSocketFactory factory = delegate();
        return retainTrustFactory(factory, factory.createSocket(host, port, localHost, localPort));
    }
}
