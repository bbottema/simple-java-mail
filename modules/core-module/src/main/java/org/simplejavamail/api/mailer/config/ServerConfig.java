package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLSocketFactory;

public interface ServerConfig {
	@NotNull String getHost();
	@NotNull Integer getPort();
	@Nullable String getUsername();
	@Nullable String getPassword();
	@Nullable String getCustomSSLFactoryClass();
	@Nullable SSLSocketFactory getCustomSSLFactoryInstance();
}
