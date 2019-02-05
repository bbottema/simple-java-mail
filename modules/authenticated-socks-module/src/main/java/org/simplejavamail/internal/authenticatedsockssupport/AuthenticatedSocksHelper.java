package org.simplejavamail.internal.authenticatedsockssupport;

import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.mailer.internal.socks.AuthenticatingSocks5Bridge;
import org.simplejavamail.mailer.internal.socks.SocksProxyConfig;
import org.simplejavamail.mailer.internal.socks.common.Socks5Bridge;
import org.simplejavamail.mailer.internal.socks.socks5server.AnonymousSocks5Server;
import org.simplejavamail.mailer.internal.socks.socks5server.AnonymousSocks5ServerImpl;

@SuppressWarnings("unused")
public class AuthenticatedSocksHelper implements AuthenticatedSocksModule {
	@Override
	public ProxyConfig createProxyConfig(String remoteProxyHost, Integer remoteProxyPort, String username, String password, Integer proxyBridgePort) {
		return new SocksProxyConfig(remoteProxyHost, remoteProxyPort, username, password, proxyBridgePort);
	}
	
	@Override
	public Socks5Bridge createAuthenticatingSocks5Bridge(ProxyConfig socksProxyConfig) {
		return new AuthenticatingSocks5Bridge(socksProxyConfig);
	}
	
	@Override
	public AnonymousSocks5Server createAnonymousSocks5ServerImpl(Socks5Bridge socks5Bridge, int proxyBridgePort) {
		return new AnonymousSocks5ServerImpl(socks5Bridge, proxyBridgePort);
	}
}
