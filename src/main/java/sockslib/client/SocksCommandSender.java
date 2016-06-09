

package sockslib.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

interface SocksCommandSender {

	int RESERVED = 0x00;
	byte ATYPE_IPV4 = 0x01;
	byte ATYPE_DOMAINNAME = 0x03;
	byte ATYPE_IPV6 = 0x04;
	int REP_SUCCEEDED = 0x00;

	void send(Socket socket, InetAddress address, int port, int version)
			throws IOException;

	void send(Socket socket, SocketAddress address, int version)
			throws IOException;

	void send(Socket socket, String host, int port, int version)
			throws IOException;

}
