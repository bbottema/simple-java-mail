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

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import static java.util.Objects.requireNonNull;

public class Socks5 {

	private static final int SOCKS_DEFAULT_PORT = 1080;

	static final byte AUTHENTICATION_SUCCEEDED = 0x00;

	@Nullable
	private Socks5 chainProxy;

	private ProxyCredentials credentials = new ProxyCredentials();

	private InetAddress inetAddress;

	@SuppressWarnings("UnusedAssignment")
	private int port = SOCKS_DEFAULT_PORT;

	@Nullable
	private Socket proxySocket;

	private SocksAuthenticationHelper socksAuthenticationHelper = new SocksAuthenticationHelper();

	private boolean alwaysResolveAddressLocally = false;

	Socks5(final InetAddress inetAddress, final int port) {
		this(new InetSocketAddress(inetAddress, port));
	}

	public Socks5(final InetSocketAddress socketAddress) {
		this(null, socketAddress);
	}

	@SuppressWarnings("SameParameterValue")
	private Socks5(@Nullable final Socks5 chainProxy, final InetSocketAddress socketAddress) {
		inetAddress = socketAddress.getAddress();
		port = socketAddress.getPort();
		this.setChainProxy(chainProxy);
	}

//	public Socks5(final String host, final int port, final ProxyCredentials credentials)
//			throws UnknownHostException {
//		this.inetAddress = InetAddress.getByName(host);
//		this.port = port;
//		this.credentials = credentials;
//	}

	public void buildConnection()
			throws IOException {
		if (inetAddress == null) {
			throw new IllegalArgumentException("Please set inetAddress before calling buildConnection.");
		}
		if (proxySocket == null) {
			proxySocket = createProxySocket(inetAddress, port);
		} else if (!proxySocket.isConnected()) {
			proxySocket.connect(new InetSocketAddress(inetAddress, port));
		}

		if (SocksAuthenticationHelper.shouldAuthenticate(proxySocket)) {
			SocksAuthenticationHelper.performUserPasswordAuthentication(this);
		}
	}

	public void requestConnect(final String host, final int port)
			throws IOException {
		if (!alwaysResolveAddressLocally) {
			// resolve address in SOCKS server
			SocksCommandSender.send(requireNonNull(proxySocket, "proxySocket"), host, port);

		} else {
			// resolve address in local.
			final InetAddress address = InetAddress.getByName(host);
			SocksCommandSender.send(requireNonNull(proxySocket, "proxySocket"), address, port);
		}
	}

	public void requestConnect(final InetAddress address, final int port)
			throws IOException {
		SocksCommandSender.send(requireNonNull(proxySocket, "proxySocket"), address, port);
	}

	public void requestConnect(final SocketAddress address)
			throws IOException {
		SocksCommandSender.send(requireNonNull(proxySocket, "proxySocket"), address);
	}

	public int getPort() {
		return port;
	}

	Socks5 setPort(final int port) {
		this.port = port;
		return this;
	}

	@Nullable
	public Socket getProxySocket() {
		return proxySocket;
	}

	public void setProxySocket(@Nullable final Socket proxySocket) {
		this.proxySocket = proxySocket;
	}

	public InputStream getInputStream()
			throws IOException {
		return requireNonNull(proxySocket, "proxySocket").getInputStream();
	}

	public OutputStream getOutputStream()
			throws IOException {
		return requireNonNull(proxySocket, "proxySocket").getOutputStream();
	}

	public ProxyCredentials getCredentials() {
		return credentials;
	}

	public Socks5 setCredentials(final ProxyCredentials credentials) {
		this.credentials = credentials;
		return this;
	}

	SocksAuthenticationHelper getSocksAuthenticationHelper() {
		return socksAuthenticationHelper;
	}

	Socks5 setSocksAuthenticationHelper(final SocksAuthenticationHelper requester) {
		this.socksAuthenticationHelper = requester;
		return this;
	}

	public Socks5 copy() {
		final Socks5 socks5 = new Socks5(inetAddress, port);
		socks5.setAlwaysResolveAddressLocally(alwaysResolveAddressLocally).setCredentials(credentials)
				.setSocksAuthenticationHelper(socksAuthenticationHelper).setChainProxy(chainProxy);
		return socks5;
	}

	@Nullable
	public Socks5 getChainProxy() {
		return chainProxy;
	}

	Socks5 setChainProxy(@Nullable final Socks5 chainProxy) {
		this.chainProxy = chainProxy;
		return this;
	}

	public InetAddress getInetAddress() {
		return inetAddress;
	}

	Socks5 setInetAddress(final InetAddress inetAddress) {
		this.inetAddress = inetAddress;
		return this;
	}

	@Override
	public String toString() {
		final StringBuilder stringBuffer = new StringBuilder("[SOCKS5:");
		stringBuffer.append(new InetSocketAddress(inetAddress, port)).append("]");
		if (getChainProxy() != null) {
			return stringBuffer.append(" --> ").append(getChainProxy()).toString();
		}
		return stringBuffer.toString();
	}

	Socket createProxySocket(final InetAddress address, final int port)
			throws IOException {
		return new Socket(address, port);
	}

	public Socket createProxySocket()
			throws IOException {
		return new Socket();
	}

	boolean isAlwaysResolveAddressLocally() {
		return alwaysResolveAddressLocally;
	}

	Socks5 setAlwaysResolveAddressLocally(final boolean alwaysResolveAddressLocally) {
		this.alwaysResolveAddressLocally = alwaysResolveAddressLocally;
		return this;
	}

}
