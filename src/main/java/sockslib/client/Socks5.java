package sockslib.client;

import sockslib.common.ProxyCredentials;
import sockslib.common.methods.GssApiMethod;
import sockslib.common.methods.NoAuthenticationRequiredMethod;
import sockslib.common.methods.SocksMethod;
import sockslib.common.methods.SocksMethodRegistry;
import sockslib.common.methods.UsernamePasswordMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class Socks5 implements SocksProxy {

	private static final byte SOCKS_VERSION = 0x05;
	public static final byte AUTHENTICATION_SUCCEEDED = 0x00;
	private SocksProxy chainProxy;
	private ProxyCredentials proxyCredentials = new ProxyCredentials();
	private InetAddress inetAddress;
	private int port = SOCKS_DEFAULT_PORT;
	private Socket proxySocket;
	private List<SocksMethod> acceptableMethods;
	private SocksMethodRequester socksMethodRequester = new GenericSocksMethodRequester();
	private final SocksCommandSender socksCmdSender = new GenericSocksCommandSender();
	private boolean alwaysResolveAddressLocally = false;

	private Socks5(SocketAddress socketAddress, String username, String password) {
		this(socketAddress);
		setProxyCredentials(new ProxyCredentials(username, password));
	}

	private Socks5(String host, int port)
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
	private Socks5(SocksProxy chainProxy, SocketAddress socketAddress) {
		init();
		if (socketAddress instanceof InetSocketAddress) {
			inetAddress = ((InetSocketAddress) socketAddress).getAddress();
			port = ((InetSocketAddress) socketAddress).getPort();
			this.setChainProxy(chainProxy);
		} else {
			throw new IllegalArgumentException("Only supports java.net.InetSocketAddress");
		}
	}

	private Socks5(String host, int port, ProxyCredentials proxyCredentials)
			throws UnknownHostException {
		init();
		this.inetAddress = InetAddress.getByName(host);
		this.port = port;
		this.proxyCredentials = proxyCredentials;
	}

	private void init() {
		acceptableMethods = new ArrayList<>();
		acceptableMethods.add(new NoAuthenticationRequiredMethod());
		acceptableMethods.add(new GssApiMethod());
		acceptableMethods.add(new UsernamePasswordMethod());
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

		SocksMethod method = socksMethodRequester.doRequest(acceptableMethods, proxySocket, SOCKS_VERSION);
		method.doMethod(this);
	}

	@Override
	public void requestConnect(String host, int port)
			throws IOException {
		if (!alwaysResolveAddressLocally) {
			// resolve address in SOCKS server
			socksCmdSender.sendConnectCommand(proxySocket, host, port, SOCKS_VERSION);

		} else {
			// resolve address in local.
			InetAddress address = InetAddress.getByName(host);
			socksCmdSender.sendConnectCommand(proxySocket, address, port, SOCKS_VERSION);
		}
	}

	@Override
	public void requestConnect(InetAddress address, int port)
			throws IOException {
		socksCmdSender.sendConnectCommand(proxySocket, address, port, SOCKS_VERSION);
	}

	@Override
	public void requestConnect(SocketAddress address)
			throws IOException {
		socksCmdSender.sendConnectCommand(proxySocket, address, SOCKS_VERSION);
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

	@Override
	public InputStream getInputStream()
			throws IOException {
		return proxySocket.getInputStream();
	}

	@Override
	public OutputStream getOutputStream()
			throws IOException {
		return proxySocket.getOutputStream();
	}

	List<SocksMethod> getAcceptableMethods() {
		return acceptableMethods;
	}

	Socks5 setAcceptableMethods(List<SocksMethod> acceptableMethods) {
		this.acceptableMethods = acceptableMethods;
		SocksMethodRegistry.overWriteRegistry(acceptableMethods);
		return this;
	}

	public ProxyCredentials getProxyCredentials() {
		return proxyCredentials;
	}

	public Socks5 setProxyCredentials(ProxyCredentials proxyCredentials) {
		this.proxyCredentials = proxyCredentials;
		return this;
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
		socks5.setAcceptableMethods(acceptableMethods).setAlwaysResolveAddressLocally(alwaysResolveAddressLocally).setProxyCredentials(proxyCredentials)
				.setSocksMethodRequester(socksMethodRequester).setChainProxy(chainProxy);
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
			return stringBuffer.append(" --> ").append(getChainProxy().toString()).toString();
		}
		return stringBuffer.toString();
	}

	private Socket createProxySocket(InetAddress address, int port)
			throws IOException {
		return new Socket(address, port);
	}

	@Override
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
