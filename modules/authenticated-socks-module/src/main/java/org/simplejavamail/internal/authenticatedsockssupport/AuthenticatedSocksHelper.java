/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.authenticatedsockssupport;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.AnonymousSocks5ServerImpl;
import org.simplejavamail.internal.modules.AuthenticatedSocksModule;

import org.jetbrains.annotations.NotNull;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

@SuppressWarnings("unused")
public class AuthenticatedSocksHelper implements AuthenticatedSocksModule {
	
	@Override
	public AnonymousSocks5Server createAnonymousSocks5Server(@NotNull ProxyConfig socksProxyConfig) {
		final Integer proxyBridgePort = checkNonEmptyArgument(socksProxyConfig.getProxyBridgePort(), "proxyBridgePort");
		return new AnonymousSocks5ServerImpl(new AuthenticatingSocks5Bridge(socksProxyConfig), proxyBridgePort);
	}
}