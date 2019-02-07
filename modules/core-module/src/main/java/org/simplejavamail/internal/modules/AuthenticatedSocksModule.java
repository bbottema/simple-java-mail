package org.simplejavamail.internal.modules;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import javax.annotation.Nonnull;

public interface AuthenticatedSocksModule {
	AnonymousSocks5Server createAnonymousSocks5Server(@Nonnull ProxyConfig socksProxyConfig);
}