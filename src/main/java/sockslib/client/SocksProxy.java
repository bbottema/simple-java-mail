package sockslib.client;

import sockslib.common.ProxyCredentials;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

public interface SocksProxy {

	int SOCKS_DEFAULT_PORT = 1080;

	Socket getProxySocket();

	void setProxySocket(Socket socket);

	int getPort();

	InetAddress getInetAddress();

	void buildConnection()
			throws IOException;

	void requestConnect(String host, int port)
			throws IOException;

	void requestConnect(InetAddress address, int port)
			throws IOException;

	void requestConnect(SocketAddress address)
			throws IOException;

	InputStream getInputStream()
			throws IOException;

	OutputStream getOutputStream()
			throws IOException;

	ProxyCredentials getProxyCredentials();

	SocksProxy setProxyCredentials(ProxyCredentials proxyCredentials);

	SocksProxy copy();

	SocksProxy getChainProxy();

	SocksProxy setChainProxy(SocksProxy chainProxy);

	Socket createProxySocket()
			throws IOException;

}
