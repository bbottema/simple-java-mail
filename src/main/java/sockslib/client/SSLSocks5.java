package sockslib.client;

import sockslib.common.SSLConfiguration;
import sockslib.common.SSLConfigurationException;
import sockslib.common.SocksException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

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
	public Socket createProxySocket()
			throws IOException {
		try {
			return configuration.getSSLSocketFactory().createSocket();
		} catch (SSLConfigurationException e) {
			throw new SocksException(e.getMessage());
		}
	}

	@Override
	public SocksProxy copy() {
		return copyWithoutChainProxy().setChainProxy(getChainProxy());
	}

	private SocksProxy copyWithoutChainProxy() {
		SSLSocks5 socks5 = new SSLSocks5(getInetAddress(), getPort(), configuration);
		socks5.setAcceptableMethods(getAcceptableMethods()).setAlwaysResolveAddressLocally(isAlwaysResolveAddressLocally())
				.setProxyCredentials(getProxyCredentials()).setInetAddress(getInetAddress()).setPort(getPort())
				.setSocksMethodRequester(getSocksMethodRequester());
		return socks5;
	}

}
