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

import org.simplejavamail.api.mailer.config.ServerConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * @see ServerConfig
 */
class ServerConfigImpl implements ServerConfig {
	@NotNull private final String host;
	@NotNull private final Integer port;
	@Nullable private final String username;
	@Nullable private final String password;
	
	ServerConfigImpl(@NotNull final String host, @NotNull final Integer port, @Nullable final String username, @Nullable final String password) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		
		if (valueNullOrEmpty(this.username) && !valueNullOrEmpty(this.password)) {
			throw new IllegalArgumentException("Password provided but not a username");
		}
	}
	
	@Override
	public String toString() {
		String str = format("%s:%s", host, port);
		if (username != null) {
			str += format(", username: %s", username);
		}
		if (password != null) {
			str += " (authenticated)";
		}
		return str;
	}
	
	/**
	 * @see ServerConfig#getHost()
	 */
	@NotNull
	@Override
	public String getHost() {
		return host;
	}
	
	/**
	 * @see ServerConfig#getPort()
	 */
	@NotNull
	@Override
	public Integer getPort() {
		return port;
	}
	
	/**
	 * @see ServerConfig#getUsername()
	 */
	@Override
	@Nullable
	public String getUsername() {
		return username;
	}
	
	/**
	 * @see ServerConfig#getPassword()
	 */
	@Override
	@Nullable
	public String getPassword() {
		return password;
	}
}