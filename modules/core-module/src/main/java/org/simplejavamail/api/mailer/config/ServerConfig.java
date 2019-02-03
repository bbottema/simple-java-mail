package org.simplejavamail.api.mailer.config;

import javax.annotation.Nullable;

public interface ServerConfig {
	String getHost();
	
	Integer getPort();
	
	@Nullable
	String getUsername();
	
	@Nullable
	String getPassword();
}
