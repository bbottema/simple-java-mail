

package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;

public class Socks5 {

	private static final int SOCKS_DEFAULT_PORT = 1080;

	static final byte AUTHENTICATION_SUCCEEDED = 0x00;

	private Socks5 chainProxy;

	private ProxyCredentials credentials = new ProxyCredentials();

	private InetAddress inetAddress;

	@SuppressWarnings("UnusedAssignment")
	private int port = SOCKS_DEFAULT_PORT;

	private Socket proxySocket;

	private SocksAuthenticationHelper socksAuthenticationHelper = new SocksAuthenticationHelper();

	private boolean alwaysResolveAddressLocally = false;

//	public Socks5(final InetSocketAddress socketAddress, final String username, final String password) {
//		this(socketAddress);
//		setCredentials(new ProxyCredentials(username, password));
//	}
//
//	public Socks5(final String host, final int port)
//			throws UnknownHostException {
//		this(InetAddress.getByName(host), port);
//	}

	Socks5(final InetAddress inetAddress, final int port) {
		this(new InetSocketAddress(inetAddress, port));
	}

	public Socks5(final InetSocketAddress socketAddress) {
		this(null, socketAddress);
	}

	@SuppressWarnings("SameParameterValue")
	private Socks5(final Socks5 chainProxy, final InetSocketAddress socketAddress) {
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
			SocksCommandSender.send(proxySocket, host, port);

		} else {
			// resolve address in local.
			final InetAddress address = InetAddress.getByName(host);
			SocksCommandSender.send(proxySocket, address, port);
		}
	}

	public void requestConnect(final InetAddress address, final int port)
			throws IOException {
		SocksCommandSender.send(proxySocket, address, port);
	}

	public void requestConnect(final SocketAddress address)
			throws IOException {
		SocksCommandSender.send(proxySocket, address);
	}

	public int getPort() {
		return port;
	}

	Socks5 setPort(final int port) {
		this.port = port;
		return this;
	}

	public Socket getProxySocket() {
		return proxySocket;
	}

	public void setProxySocket(final Socket proxySocket) {
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

	public Socks5 getChainProxy() {
		return chainProxy;
	}

	Socks5 setChainProxy(final Socks5 chainProxy) {
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
