package org.simplejavamail.internal.authenticatedsockssupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.authenticatedsockssupport.socks5client.SocksSocket;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

@SuppressWarnings("unused")
public class AuthenticatedSocksHelper implements AuthenticatedSocksModule {

    @Override
    public AuthenticatedSocksSocketProvider createSocketProvider(@NotNull final ProxyConfig socksProxyConfig) {
        return SocksSocket.providerFor(socksProxyConfig);
    }
}
