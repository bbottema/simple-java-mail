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
package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.simplejavamail.internal.util.MiscUtil;

public class ProxyCredentials {

	private String username;

	private String password;

	public ProxyCredentials() {
	}

	public ProxyCredentials(final String username, final String password) {
		this.username = username;
		this.password = MiscUtil.checkNotNull(password, "Argument [password] may not be null");
	}

	public String getUsername() {
		return this.username;
	}

	public String getPassword() {
		return this.password;
	}

}
