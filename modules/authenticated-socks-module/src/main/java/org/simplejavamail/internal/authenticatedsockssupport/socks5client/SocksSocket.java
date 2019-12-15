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

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class SocksSocket extends Socket {

	private static final Logger LOGGER = LoggerFactory.getLogger(SocksSocket.class);

	private final Socks5 proxy;

	private String remoteServerHost;

	private int remoteServerPort;

	private Socket proxySocket;

	private SocksSocket(final Socks5 proxy, final String remoteServerHost, final int remoteServerPort)
			throws IOException {
		this.proxy = MiscUtil.checkNotNull(proxy, "Argument [proxy] may not be null").copy();
		this.proxy.setProxySocket(proxySocket);
		this.remoteServerHost = MiscUtil.checkNotNull(remoteServerHost, "Argument [remoteServerHost] may not be null");
		this.remoteServerPort = remoteServerPort;
		this.proxy.buildConnection();
		proxySocket = this.proxy.getProxySocket();
		initProxyChain();
		this.proxy.requestConnect(remoteServerHost, remoteServerPort);
	}

	private SocksSocket(final Socks5 proxy, final InetAddress address, final int port)
			throws IOException {
		this(proxy, new InetSocketAddress(address, port));
	}

	public SocksSocket(final Socks5 proxy, final InetSocketAddress socketAddress)
			throws IOException {
		MiscUtil.checkNotNull(proxy, "Argument [proxy] may not be null");
		MiscUtil.checkNotNull(socketAddress, "Argument [socketAddress] may not be null");
		this.proxy = proxy.copy();
		this.remoteServerHost = socketAddress.getHostString();
		this.remoteServerPort = socketAddress.getPort();
		this.proxy.buildConnection();
		proxySocket = this.proxy.getProxySocket();
		initProxyChain();
		this.proxy.requestConnect(socketAddress.getAddress(), socketAddress.getPort());
	}

	@SuppressWarnings("WeakerAccess")
	public SocksSocket(final Socks5 proxy)
			throws IOException {
		this(proxy, proxy.createProxySocket());
	}

	public SocksSocket(final Socks5 proxy, final Socket proxySocket, final InetSocketAddress socketAddress)
			throws IOException {
		this(proxy, proxySocket);
		connect(socketAddress);
	}

	@SuppressWarnings("WeakerAccess")
	public SocksSocket(final Socks5 proxy, final Socket proxySocket) {
		MiscUtil.checkNotNull(proxy, "Argument [proxy] may not be null");
		MiscUtil.checkNotNull(proxySocket, "Argument [proxySocket] may not be null");
		if (proxySocket.isConnected()) {
			throw new IllegalArgumentException("Proxy socket should be unconnected");
		}
		this.proxySocket = proxySocket;
		this.proxy = proxy.copy();
		this.proxy.setProxySocket(proxySocket);
	}

	private void initProxyChain()
			throws IOException {
		final List<Socks5> proxyChain = new ArrayList<>();
		Socks5 temp = proxy;
		while (temp.getChainProxy() != null) {
			temp.getChainProxy().setProxySocket(proxySocket);
			proxyChain.add(temp.getChainProxy());
			temp = temp.getChainProxy();
		}
		LOGGER.debug("Proxy chain has:{} proxy", proxyChain.size());
		if (proxyChain.size() > 0) {
			Socks5 pre = proxy;
			for (final Socks5 chain : proxyChain) {
				pre.requestConnect(chain.getInetAddress(), chain.getPort());
				requireNonNull(proxy.getChainProxy(), "chainProxy").buildConnection();
				pre = chain;
			}
		}

	}

	@Override
	public void connect(final SocketAddress endpoint)
			throws IOException {
		connect(endpoint, 0);
	}

	@Override
	public void connect(final SocketAddress endpoint, final int timeout)
			throws IOException {

		if (!(endpoint instanceof InetSocketAddress)) {
			throw new IllegalArgumentException("Unsupported address type");
		}

		remoteServerHost = ((InetSocketAddress) endpoint).getHostName();
		remoteServerPort = ((InetSocketAddress) endpoint).getPort();

		getProxySocket().setSoTimeout(timeout);
		proxy.buildConnection();
		initProxyChain();
		proxy.requestConnect(endpoint);

	}

	@Override
	public InputStream getInputStream()
			throws IOException {
		return getProxySocket().getInputStream();
	}

	@Override
	public OutputStream getOutputStream()
			throws IOException {
		return getProxySocket().getOutputStream();
	}

	@Override
	public void bind(final SocketAddress bindpoint)
			throws IOException {
		getProxySocket().bind(bindpoint);
	}

	@Override
	public InetAddress getInetAddress() {
		try {
			return InetAddress.getByName(remoteServerHost);
		} catch (final UnknownHostException e) {
			throw new SocksException(e.getMessage(), e);
		}
	}

	@Override
	public InetAddress getLocalAddress() {
		return getProxySocket().getLocalAddress();
	}

	@SuppressWarnings("SuspiciousGetterSetter")
	@Override
	public int getPort() {
		return remoteServerPort;
	}

	@Override
	public int getLocalPort() {
		return getProxySocket().getLocalPort();
	}

	@Override
	public SocketAddress getRemoteSocketAddress() {
		return getProxySocket().getRemoteSocketAddress();
	}

	@Override
	public SocketAddress getLocalSocketAddress() {
		return getProxySocket().getLocalSocketAddress();
	}

	@Override
	public SocketChannel getChannel() {
		return getProxySocket().getChannel();
	}

	@Override
	public boolean getTcpNoDelay()
			throws SocketException {
		return getProxySocket().getTcpNoDelay();
	}

	@Override
	public void setTcpNoDelay(final boolean on)
			throws SocketException {
		getProxySocket().setTcpNoDelay(on);
	}

	@Override
	public void setSoLinger(final boolean on, final int linger)
			throws SocketException {
		getProxySocket().setSoLinger(on, linger);
	}

	@Override
	public int getSoLinger()
			throws SocketException {
		return getProxySocket().getSoLinger();
	}

	@Override
	public void sendUrgentData(final int data)
			throws IOException {
		getProxySocket().sendUrgentData(data);
	}

	@Override
	public boolean getOOBInline()
			throws SocketException {
		return getProxySocket().getOOBInline();
	}

	@Override
	public void setOOBInline(final boolean on)
			throws SocketException {
		getProxySocket().setOOBInline(on);
	}

	@Override
	public synchronized int getSoTimeout()
			throws SocketException {
		return getProxySocket().getSoTimeout();
	}

	@Override
	public synchronized void setSoTimeout(final int timeout)
			throws SocketException {
		getProxySocket().setSoTimeout(timeout);
	}

	@Override
	public synchronized int getSendBufferSize()
			throws SocketException {
		return getProxySocket().getSendBufferSize();
	}

	@Override
	public synchronized void setSendBufferSize(final int size)
			throws SocketException {
		getProxySocket().setSendBufferSize(size);
	}

	@Override
	public synchronized int getReceiveBufferSize()
			throws SocketException {
		return getProxySocket().getReceiveBufferSize();
	}

	@Override
	public synchronized void setReceiveBufferSize(final int size)
			throws SocketException {
		getProxySocket().setReceiveBufferSize(size);
	}

	@Override
	public boolean getKeepAlive()
			throws SocketException {
		return getProxySocket().getKeepAlive();
	}

	@Override
	public void setKeepAlive(final boolean on)
			throws SocketException {
		getProxySocket().setKeepAlive(on);
	}

	@Override
	public int getTrafficClass()
			throws SocketException {
		return getProxySocket().getTrafficClass();
	}

	@Override
	public void setTrafficClass(final int tc)
			throws SocketException {
		getProxySocket().setTrafficClass(tc);
	}

	@Override
	public boolean getReuseAddress()
			throws SocketException {
		return getProxySocket().getReuseAddress();
	}

	@Override
	public void setReuseAddress(final boolean on)
			throws SocketException {
		getProxySocket().setReuseAddress(on);
	}

	@Override
	public synchronized void close()
			throws IOException {
		if (proxy.getProxySocket() != null) {
			proxy.getProxySocket().close();
			proxy.setProxySocket(null);
		}
	}

	@Override
	public void shutdownInput()
			throws IOException {
		getProxySocket().shutdownInput();
	}

	@Override
	public void shutdownOutput()
			throws IOException {
		getProxySocket().shutdownOutput();
	}

	@Override
	public boolean isConnected() {
		return getProxySocket().isConnected();
	}

	@Override
	public boolean isBound() {
		return getProxySocket().isBound();
	}

	@Override
	public boolean isClosed() {
		return getProxySocket().isClosed();
	}

	@Override
	public boolean isInputShutdown() {
		return getProxySocket().isInputShutdown();
	}

	@Override
	public boolean isOutputShutdown() {
		return getProxySocket().isOutputShutdown();
	}

	@Override
	public void setPerformancePreferences(final int connectionTime, final int latency, final int bandwidth) {
		getProxySocket().setPerformancePreferences(connectionTime, latency, bandwidth);
	}

	@NotNull
	private Socket getProxySocket() {
		return requireNonNull(proxy.getProxySocket(), "proxySocket");
	}
}