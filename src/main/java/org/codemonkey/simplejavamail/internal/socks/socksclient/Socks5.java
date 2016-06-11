

package org.codemonkey.simplejavamail.internal.socks.socksclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

public class Socks5 {

	private final int SOCKS_DEFAULT_PORT = 1080;

	public static final byte AUTHENTICATION_SUCCEEDED = 0x00;

	private Socks5 chainProxy;

	private ProxyCredentials credentials = new ProxyCredentials();

	private InetAddress inetAddress;

	private int port = SOCKS_DEFAULT_PORT;

	private Socket proxySocket;

	private SocksAuthenticationHelper socksAuthenticationHelper = new SocksAuthenticationHelper();

	private final SocksCommandSender socksCmdSender = new SocksCommandSender();

	private boolean alwaysResolveAddressLocally = false;

	public Socks5(SocketAddress socketAddress, String username, String password) {
		this(socketAddress);
		setCredentials(new ProxyCredentials(username, password));
	}

	public Socks5(String host, int port)
			throws UnknownHostException {
		this(InetAddress.getByName(host), port);
	}

	Socks5(InetAddress inetAddress, int port) {
		this(new InetSocketAddress(inetAddress, port));
	}

	public Socks5(SocketAddress socketAddress) {
		this(null, socketAddress);
	}

	@SuppressWarnings("SameParameterValue")
	private Socks5(Socks5 chainProxy, SocketAddress socketAddress) {
		if (socketAddress instanceof InetSocketAddress) {
			inetAddress = ((InetSocketAddress) socketAddress).getAddress();
			port = ((InetSocketAddress) socketAddress).getPort();
			this.setChainProxy(chainProxy);
		} else {
			throw new IllegalArgumentException("Only supports java.net.InetSocketAddress");
		}
	}

	public Socks5(String host, int port, ProxyCredentials credentials)
			throws UnknownHostException {
		this.inetAddress = InetAddress.getByName(host);
		this.port = port;
		this.credentials = credentials;
	}

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

	public void requestConnect(String host, int port)
			throws IOException {
		if (!alwaysResolveAddressLocally) {
			// resolve address in SOCKS server
			socksCmdSender.send(proxySocket, host, port);

		} else {
			// resolve address in local.
			InetAddress address = InetAddress.getByName(host);
			socksCmdSender.send(proxySocket, address, port);
		}
	}

	public void requestConnect(InetAddress address, int port)
			throws IOException {
		socksCmdSender.send(proxySocket, address, port);
	}

	public void requestConnect(SocketAddress address)
			throws IOException {
		socksCmdSender.send(proxySocket, address);
	}

	public int getPort() {
		return port;
	}

	Socks5 setPort(int port) {
		this.port = port;
		return this;
	}

	public Socket getProxySocket() {
		return proxySocket;
	}

	public void setProxySocket(Socket proxySocket) {
		this.proxySocket = proxySocket;
	}

	public InputStream getInputStream()
			throws IOException {
		return proxySocket.getInputStream();
	}

	public OutputStream getOutputStream()
			throws IOException {
		return proxySocket.getOutputStream();
	}

	public ProxyCredentials getCredentials() {
		return credentials;
	}

	public Socks5 setCredentials(ProxyCredentials credentials) {
		this.credentials = credentials;
		return this;
	}

	SocksAuthenticationHelper getSocksAuthenticationHelper() {
		return socksAuthenticationHelper;
	}

	Socks5 setSocksAuthenticationHelper(SocksAuthenticationHelper requester) {
		this.socksAuthenticationHelper = requester;
		return this;
	}

	public Socks5 copy() {
		Socks5 socks5 = new Socks5(inetAddress, port);
		socks5.setAlwaysResolveAddressLocally(alwaysResolveAddressLocally).setCredentials(credentials)
				.setSocksAuthenticationHelper(socksAuthenticationHelper).setChainProxy(chainProxy);
		return socks5;
	}

	public Socks5 getChainProxy() {
		return chainProxy;
	}

	Socks5 setChainProxy(Socks5 chainProxy) {
		this.chainProxy = chainProxy;
		return this;
	}

	public InetAddress getInetAddress() {
		return inetAddress;
	}

	Socks5 setInetAddress(InetAddress inetAddress) {
		this.inetAddress = inetAddress;
		return this;
	}

	@Override
	public String toString() {
		StringBuilder stringBuffer = new StringBuilder("[SOCKS5:");
		stringBuffer.append(new InetSocketAddress(inetAddress, port)).append("]");
		if (getChainProxy() != null) {
			return stringBuffer.append(" --> ").append(getChainProxy()).toString();
		}
		return stringBuffer.toString();
	}

	Socket createProxySocket(InetAddress address, int port)
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

	Socks5 setAlwaysResolveAddressLocally(boolean alwaysResolveAddressLocally) {
		this.alwaysResolveAddressLocally = alwaysResolveAddressLocally;
		return this;
	}

}
