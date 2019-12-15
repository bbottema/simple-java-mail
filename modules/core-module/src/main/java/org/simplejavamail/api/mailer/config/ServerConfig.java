package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ServerConfig {
	@NotNull String getHost();
	@NotNull Integer getPort();
	@Nullable String getUsername();
	@Nullable String getPassword();
}
