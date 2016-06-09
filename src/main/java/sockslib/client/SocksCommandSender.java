

package sockslib.client;

import sockslib.common.SocksCommand;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

public interface SocksCommandSender {

	int RESERVED = 0x00;
	byte ATYPE_IPV4 = 0x01;
	byte ATYPE_DOMAINNAME = 0x03;
	byte ATYPE_IPV6 = 0x04;
	int REP_SUCCEEDED = 0x00;

	CommandReplyMessage send(Socket socket, SocksCommand command, InetAddress address, int port, int version)
			throws IOException;

	CommandReplyMessage send(Socket socket, SocksCommand command, SocketAddress address, int version)
			throws IOException;

	CommandReplyMessage send(Socket socket, SocksCommand command, String host, int port, int version)
			throws IOException;

	CommandReplyMessage checkServerReply(InputStream inputStream)
			throws IOException;
}
