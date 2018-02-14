package org.simplejavamail.mailer.internal.socks.socks5client;

import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.mailer.internal.socks.common.SocksException;
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
				proxy.getChainProxy().buildConnection();
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

		proxy.getProxySocket().setSoTimeout(timeout);
		proxy.buildConnection();
		initProxyChain();
		proxy.requestConnect(endpoint);

	}

	@Override
	public InputStream getInputStream()
			throws IOException {
		return proxy.getProxySocket().getInputStream();
	}

	@Override
	public OutputStream getOutputStream()
			throws IOException {
		return proxy.getProxySocket().getOutputStream();
	}

	@Override
	public void bind(final SocketAddress bindpoint)
			throws IOException {
		proxy.getProxySocket().bind(bindpoint);
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
		return proxy.getProxySocket().getLocalAddress();
	}

	@SuppressWarnings("SuspiciousGetterSetter")
	@Override
	public int getPort() {
		return remoteServerPort;
	}

	@Override
	public int getLocalPort() {
		return proxy.getProxySocket().getLocalPort();
	}

	@Override
	public SocketAddress getRemoteSocketAddress() {
		return proxy.getProxySocket().getRemoteSocketAddress();
	}

	@Override
	public SocketAddress getLocalSocketAddress() {
		return proxy.getProxySocket().getLocalSocketAddress();
	}

	@Override
	public SocketChannel getChannel() {
		return proxy.getProxySocket().getChannel();
	}

	@Override
	public boolean getTcpNoDelay()
			throws SocketException {
		return proxy.getProxySocket().getTcpNoDelay();
	}

	@Override
	public void setTcpNoDelay(final boolean on)
			throws SocketException {
		proxy.getProxySocket().setTcpNoDelay(on);
	}

	@Override
	public void setSoLinger(final boolean on, final int linger)
			throws SocketException {
		proxy.getProxySocket().setSoLinger(on, linger);
	}

	@Override
	public int getSoLinger()
			throws SocketException {
		return proxy.getProxySocket().getSoLinger();
	}

	@Override
	public void sendUrgentData(final int data)
			throws IOException {
		proxy.getProxySocket().sendUrgentData(data);
	}

	@Override
	public boolean getOOBInline()
			throws SocketException {
		return proxy.getProxySocket().getOOBInline();
	}

	@Override
	public void setOOBInline(final boolean on)
			throws SocketException {
		proxy.getProxySocket().setOOBInline(on);
	}

	@Override
	public synchronized int getSoTimeout()
			throws SocketException {
		return proxy.getProxySocket().getSoTimeout();
	}

	@Override
	public synchronized void setSoTimeout(final int timeout)
			throws SocketException {
		proxy.getProxySocket().setSoTimeout(timeout);
	}

	@Override
	public synchronized int getSendBufferSize()
			throws SocketException {
		return proxy.getProxySocket().getSendBufferSize();
	}

	@Override
	public synchronized void setSendBufferSize(final int size)
			throws SocketException {
		proxy.getProxySocket().setSendBufferSize(size);
	}

	@Override
	public synchronized int getReceiveBufferSize()
			throws SocketException {
		return proxy.getProxySocket().getReceiveBufferSize();
	}

	@Override
	public synchronized void setReceiveBufferSize(final int size)
			throws SocketException {
		proxy.getProxySocket().setReceiveBufferSize(size);
	}

	@Override
	public boolean getKeepAlive()
			throws SocketException {
		return proxy.getProxySocket().getKeepAlive();
	}

	@Override
	public void setKeepAlive(final boolean on)
			throws SocketException {
		proxy.getProxySocket().setKeepAlive(on);
	}

	@Override
	public int getTrafficClass()
			throws SocketException {
		return proxy.getProxySocket().getTrafficClass();
	}

	@Override
	public void setTrafficClass(final int tc)
			throws SocketException {
		proxy.getProxySocket().setTrafficClass(tc);
	}

	@Override
	public boolean getReuseAddress()
			throws SocketException {
		return proxy.getProxySocket().getReuseAddress();
	}

	@Override
	public void setReuseAddress(final boolean on)
			throws SocketException {
		proxy.getProxySocket().setReuseAddress(on);
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
		proxy.getProxySocket().shutdownInput();
	}

	@Override
	public void shutdownOutput()
			throws IOException {
		proxy.getProxySocket().shutdownOutput();
	}

	@Override
	public boolean isConnected() {
		return proxy.getProxySocket().isConnected();
	}

	@Override
	public boolean isBound() {
		return proxy.getProxySocket().isBound();
	}

	@Override
	public boolean isClosed() {
		return proxy.getProxySocket().isClosed();
	}

	@Override
	public boolean isInputShutdown() {
		return proxy.getProxySocket().isInputShutdown();
	}

	@Override
	public boolean isOutputShutdown() {
		return proxy.getProxySocket().isOutputShutdown();
	}

	@Override
	public void setPerformancePreferences(final int connectionTime, final int latency, final int bandwidth) {
		proxy.getProxySocket().setPerformancePreferences(connectionTime, latency, bandwidth);
	}

}
