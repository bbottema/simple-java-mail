package org.simplejavamail.internal.util;

import jakarta.mail.Session;
import jakarta.mail.Provider;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;

/** Library-only optional capability of a lifecycle adapter; it does not replace a caller's Session or provider. */
public interface AuthenticatedSocksSessionConfiguration {
    /** Checks the loaded provider code without DNS, network I/O, workers or application socket factories. */
    boolean supportsDirectAuthenticatedSocks(Provider provider);

    /** Installs only the explicitly requested proxy route, before the Session can connect. */
    void configureAuthenticatedSocks(Session session, String protocol, AuthenticatedSocksSocketProvider provider);
}
