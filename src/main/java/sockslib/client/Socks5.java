/*
 * Copyright 2015-2025 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sockslib.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * The class <code>Socks5</code> has implements SOCKS5 protocol.
 *
 * @author Youchao Feng
 * @version 1.0
 * @see <a href="http://www.ietf.org/rfc/rfc1928.txt">SOCKS Protocol Version 5</a>
 */
public class Socks5 implements SocksProxy {
	/**
	 * Version of SOCKS protocol.
	 */
	private static final byte SOCKS_VERSION = 0x05;
	private SocksProxy chainProxy;
	/**
	 * SOCKS5 server's address. IPv4 or IPv6 address.
	 */
	private InetAddress inetAddress;
	/**
	 * SOCKS5 server's port;
	 */
	private int port = SOCKS_DEFAULT_PORT;
	/**
	 * The socket that will connect to SOCKS5 server.
	 */
	private Socket proxySocket;
	/**
	 * Use to send a request to SOCKS server and receive a method that SOCKS server selected .
	 */
	private SocksMethodRequester socksMethodRequester = new GenericSocksMethodRequester();
	/**
	 * Use to send command to SOCKS5 sever
	 */
	private final SocksCommandSender socksCmdSender = new GenericSocksCommandSender();
	/**
	 * Resolve remote server's domain name in SOCKS server if it's false. It's default false.
	 */
	private boolean alwaysResolveAddressLocally = false;

	/**
	 * Constructs a Socks5 instance.
	 *
	 * @param inetAddress SOCKS5 server's address.
	 * @param port        SOCKS5 server's port.
	 */
	Socks5(InetAddress inetAddress, int port) {
		this(new InetSocketAddress(inetAddress, port));
	}

	public Socks5(SocketAddress socketAddress) {
		if (socketAddress instanceof InetSocketAddress) {
			inetAddress = ((InetSocketAddress) socketAddress).getAddress();
			port = ((InetSocketAddress) socketAddress).getPort();
			this.setChainProxy(null);
		} else {
			throw new IllegalArgumentException("Only supports java.net.InetSocketAddress");
		}
	}

	@Override
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

		socksMethodRequester.doRequest(proxySocket);
	}

	@Override
	public void requestConnect(InetAddress address, int port)
			throws IOException {
		socksCmdSender.send(proxySocket, address, port);
	}

	@Override
	public void requestConnect(SocketAddress address)
			throws IOException {
		socksCmdSender.send(proxySocket, address, SOCKS_VERSION);
	}

	@Override
	public int getPort() {
		return port;
	}

	Socks5 setPort(int port) {
		this.port = port;
		return this;
	}

	@Override
	public Socket getProxySocket() {
		return proxySocket;
	}

	@Override
	public void setProxySocket(Socket proxySocket) {
		this.proxySocket = proxySocket;
	}

	SocksMethodRequester getSocksMethodRequester() {
		return socksMethodRequester;
	}

	Socks5 setSocksMethodRequester(SocksMethodRequester requester) {
		this.socksMethodRequester = requester;
		return this;
	}

	@Override
	public SocksProxy copy() {
		Socks5 socks5 = new Socks5(inetAddress, port);
		socks5.setAlwaysResolveAddressLocally(alwaysResolveAddressLocally).setSocksMethodRequester(socksMethodRequester)
				.setChainProxy(chainProxy);
		return socks5;
	}

	@Override
	public SocksProxy getChainProxy() {
		return chainProxy;
	}

	@Override
	public SocksProxy setChainProxy(SocksProxy chainProxy) {
		this.chainProxy = chainProxy;
		return this;
	}

	@Override
	public InetAddress getInetAddress() {
		return inetAddress;
	}

	/**
	 * Sets SOCKS5 proxy server's IP address.
	 *
	 * @param inetAddress IP address of SOCKS5 proxy server.
	 * @return The instance of {@link Socks5}.
	 */
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

	private Socket createProxySocket(InetAddress address, int port)
			throws IOException {
		return new Socket(address, port);
	}

	boolean isAlwaysResolveAddressLocally() {
		return alwaysResolveAddressLocally;
	}

	Socks5 setAlwaysResolveAddressLocally(boolean alwaysResolveAddressLocally) {
		this.alwaysResolveAddressLocally = alwaysResolveAddressLocally;
		return this;
	}

}
