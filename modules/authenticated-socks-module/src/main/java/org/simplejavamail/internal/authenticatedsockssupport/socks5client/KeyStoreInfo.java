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

@SuppressWarnings("SameParameterValue")
class KeyStoreInfo {

	private final String keyStorePath;
	private final String password;
	private final String type /*= "JKS"*/;

//	public KeyStoreInfo() {
//	}

	public KeyStoreInfo(final String keyStorePath, final String password, final String type) {
		this.keyStorePath = MiscUtil.checkNotNull(keyStorePath, "Argument [keyStorePath] may not be null");
		this.password = MiscUtil.checkNotNull(password, "Argument [password] may not be null");
		this.type = MiscUtil.checkNotNull(type, "Argument [type] may not be null");
	}

//	public KeyStoreInfo(final String keyStorePath, final String password) {
//		this(keyStorePath, password, "JKS");
//	}

	public String getKeyStorePath() {
		return keyStorePath;
	}

	public String getPassword() {
		return password;
	}

	public String getType() {
		return type;
	}

	@Override
	public String toString() {
		return "[KEY STORE] PATH:" + keyStorePath + " PASSWORD:" + password + " TYPE:" + type;
	}

}
