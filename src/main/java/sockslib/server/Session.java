package sockslib.server;

import sockslib.server.msg.ReadableMessage;
import sockslib.server.msg.WritableMessage;

import java.io.IOException;
import java.net.Socket;

/**
 * The class <code>Session</code> represents a session between client with SOCKS server.
 * This class is simple encapsulation of java.net.Socket.
 */
interface Session {

	Socket getSocket();

	void write(WritableMessage message)
			throws IOException;

	void read(ReadableMessage message)
			throws IOException;

	long getId();

	void close();

}
