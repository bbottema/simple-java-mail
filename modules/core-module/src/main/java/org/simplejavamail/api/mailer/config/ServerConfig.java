package org.simplejavamail.api.mailer.config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ServerConfig {
	@Nonnull String getHost();
	@Nonnull Integer getPort();
	@Nullable String getUsername();
	@Nullable String getPassword();
}
