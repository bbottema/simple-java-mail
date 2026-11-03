package org.simplejavamail.internal.util;

/** Retains the application identity of a socket factory decorated by a library-owned provider adapter. */
public interface SocketFactoryConfiguration {
    /** The original explicit instance, or this decorator when it represents a class/default configuration. */
    Object configuredInstance();
}
