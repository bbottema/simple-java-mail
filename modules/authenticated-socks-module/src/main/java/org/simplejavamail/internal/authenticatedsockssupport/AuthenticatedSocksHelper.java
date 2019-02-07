package org.simplejavamail.internal.authenticatedsockssupport;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.AnonymousSocks5ServerImpl;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

import javax.annotation.Nonnull;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

@SuppressWarnings("unused")
public class AuthenticatedSocksHelper implements AuthenticatedSocksModule {
	
	@Override
	public AnonymousSocks5Server createAnonymousSocks5Server(@Nonnull ProxyConfig socksProxyConfig) {
		final Integer proxyBridgePort = checkNonEmptyArgument(socksProxyConfig.getProxyBridgePort(), "proxyBridgePort");
		return new AnonymousSocks5ServerImpl(new AuthenticatingSocks5Bridge(socksProxyConfig), proxyBridgePort);
	}
}