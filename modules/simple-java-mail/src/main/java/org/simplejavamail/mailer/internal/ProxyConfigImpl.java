package org.simplejavamail.mailer.internal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import static java.lang.String.format;

/**
 * @see ProxyConfig
 */
@AllArgsConstructor
@Getter
class ProxyConfigImpl implements ProxyConfig {
	
	@Nullable private final String remoteProxyHost;
	@Nullable private final Integer remoteProxyPort;
	@Nullable private final String username;
	@Nullable private final String password;
	
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
		}
		return str;
	}
}
