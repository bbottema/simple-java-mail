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
package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ProxyConfig;

import org.jetbrains.annotations.Nullable;

import static java.lang.String.format;

/**
 * @see ProxyConfig
 */
class ProxyConfigImpl implements ProxyConfig {
	
	@Nullable private final String remoteProxyHost;
	@Nullable private final Integer remoteProxyPort;
	@Nullable private final String username;
	@Nullable private final String password;
	@Nullable private final Integer proxyBridgePort;
	
	ProxyConfigImpl(@Nullable final String remoteProxyHost, @Nullable final Integer remoteProxyPort, @Nullable final String username, @Nullable final String password, @Nullable final Integer proxyBridgePort) {
		this.remoteProxyHost = remoteProxyHost;
		this.remoteProxyPort = remoteProxyPort;
		this.username = username;
		this.password = password;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	@Override
	public boolean requiresProxy() {
		return remoteProxyHost != null;
	}
	
	@Override
	public boolean requiresAuthentication() {
		return username != null;
	}
	
	@Override
	public String toString() {
		if (!requiresProxy()) {
			return "no-proxy";
		}
		String str = format("%s:%s", remoteProxyHost, remoteProxyPort);
		if (requiresAuthentication()) {
			str += format(", username: %s", username);
			str += format(", proxy bridge @ localhost:%s", proxyBridgePort);
		}
		return str;
	}
	
	@Override
	@Nullable
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}
	
	@Override
	@Nullable
	public String getRemoteProxyHost() {
		return remoteProxyHost;
	}
	
	@Override
	@Nullable
	public Integer getRemoteProxyPort() {
		return remoteProxyPort;
	}
	
	@Override
	@Nullable
	public String getUsername() {
		return username;
	}
	
	@Override
	@Nullable
	public String getPassword() {
		return password;
	}
}