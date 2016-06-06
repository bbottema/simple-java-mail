package sockslib.client;

import sockslib.common.SSLConfiguration;

import java.net.InetAddress;
import java.net.SocketAddress;

/**
 * The class <code>SSLSocks5</code> represents a SSL based SOCKS5 proxy. It will build a SSL based connection between the client and SOCKS5
 * server.
 */
public class SSLSocks5 extends Socks5 {

	private final SSLConfiguration configuration;

	public SSLSocks5(SocketAddress address, SSLConfiguration configuration) {
		super(address);
		this.configuration = configuration;
	}

	private SSLSocks5(InetAddress address, int port, SSLConfiguration configuration) {
		super(address, port);
		this.configuration = configuration;
	}

	@Override
	public SocksProxy copy() {
		SSLSocks5 socks5 = new SSLSocks5(getInetAddress(), getPort(), configuration);
		socks5.setAlwaysResolveAddressLocally(isAlwaysResolveAddressLocally()).setInetAddress(getInetAddress()).setPort(getPort())
				.setSocksMethodRequester(getSocksMethodRequester());
		return socks5.setChainProxy(getChainProxy());
	}
}
