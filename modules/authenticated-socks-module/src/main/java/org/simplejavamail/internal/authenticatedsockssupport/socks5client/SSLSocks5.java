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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SSLSocks5 extends Socks5 {

	private final SSLConfiguration configuration;

	public SSLSocks5(final InetSocketAddress address, final SSLConfiguration configuration) {
		super(address);
		this.configuration = configuration;
	}

	private SSLSocks5(final InetAddress address, final int port, final SSLConfiguration configuration) {
		super(address, port);
		this.configuration = configuration;
	}

	@Override
	public Socket createProxySocket(final InetAddress address, final int port)
			throws IOException {
		return configuration.getSSLSocketFactory().createSocket(address, port);
	}

	@Override
	public Socket createProxySocket()
			throws IOException {
		return configuration.getSSLSocketFactory().createSocket();
	}

	@Override
	public Socks5 copy() {
		return copyWithoutChainProxy().setChainProxy(getChainProxy());
	}

	private Socks5 copyWithoutChainProxy() {
		final SSLSocks5 socks5 = new SSLSocks5(getInetAddress(), getPort(), configuration);
		socks5.setAlwaysResolveAddressLocally(isAlwaysResolveAddressLocally()).setCredentials(getCredentials())
				.setInetAddress(getInetAddress()).setPort(getPort()).setSocksAuthenticationHelper(getSocksAuthenticationHelper());
		return socks5;
	}

}
