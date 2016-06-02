package sockslib.client;

import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * The interface <code>SocksProxy</code> define a SOCKS proxy. it's will be used by
 * {@link SocksSocket}
 */
public interface SocksProxy {

	/**
	 * Get the socket which connect SOCKS server.
	 */
	Socket getProxySocket();

	/**
	 * Set a unconnected socket which will be used to connect SOCKS server.
	 */
	void setProxySocket(Socket socket);

	int getPort();

	InetAddress getInetAddress();

	void buildConnection();

	/**
	 * This method will send a CONNECT command to SOCKS server and ask SOCKS server to connect remote
	 * server.
	 *
	 * @param address Remote server's address as java.net.InetAddress instance.
	 * @param port    Remote server's port.
	 */
	void requestConnect(InetAddress address, int port);

	/**
	 * This method will send a CONNECT command to SOCKS server and ask SOCKS server to connect remote
	 * server.
	 *
	 * @param address Remote server's address as java.net.SocketAddress instance.
	 */
	void requestConnect(SocketAddress address);

	/**
	 * This method can build a same SocksProxy instance. The new instance created by this method has
	 * the same properties with the original instance, but they have different socket instance. The
	 * new instance's socket is also unconnected.
	 */
	SocksProxy copy();

	SocksProxy getChainProxy();

}
