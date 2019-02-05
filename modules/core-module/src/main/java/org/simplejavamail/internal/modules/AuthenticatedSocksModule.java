package org.simplejavamail.internal.modules;

import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;

public interface AuthenticatedSocksModule {
	ProxyConfig createProxyConfig(String remoteProxyHost, Integer remoteProxyPort, String username, String password, Integer proxyBridgePort);
	
	Socks5Bridge createAuthenticatingSocks5Bridge(ProxyConfig socksProxyConfig);
	
	AnonymousSocks5Server createAnonymousSocks5ServerImpl(Socks5Bridge socks5Bridge, int proxyBridgePort);
}