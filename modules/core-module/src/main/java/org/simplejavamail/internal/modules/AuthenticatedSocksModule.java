package org.simplejavamail.internal.modules;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.AuthenticatedSocksSocketProvider;
import org.simplejavamail.api.mailer.config.ProxyConfig;

public interface AuthenticatedSocksModule {

	String NAME = "Authenticated socks module";

    @NotNull
    AuthenticatedSocksSocketProvider createSocketProvider(@NotNull ProxyConfig socksProxyConfig);
}
