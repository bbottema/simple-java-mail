package sockslib.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.server.io.Pipe;
import sockslib.server.io.SocketPipe;
import sockslib.server.msg.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Socks5Handler implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(Socks5Handler.class);

	public static final int VERSION = 0x5;

	private SocksSession session;

	private final int idleTime = 2000;

	private void handle(SocksSession session)
			throws Exception {
		MethodSelectionMessage msg = new MethodSelectionMessage();
		session.read(msg);

		if (msg.getVersion() != VERSION) {
			throw new RuntimeException("Protocol error");
		}

		logger.debug("SESSION[{}]", session.getId());
		// send select method.
		session.write(new MethodSelectionResponseMessage());

		CommandMessage commandMessage = new CommandMessage();
		session.read(commandMessage); // Read command request.

		// If there is a SOCKS exception in command message, It will send a right response to client.
		if (commandMessage.hasSocksException()) {
			ServerReply serverReply = commandMessage.getSocksException().getServerReply();
			session.write(new CommandResponseMessage(serverReply));
			logger.info("SESSION[{}] will close, because {}", session.getId(), serverReply);
			return;
		}

		/**************************** DO COMMAND ******************************************/
		switch (commandMessage.getCommand()) {
		case BIND:
			doBind(session, commandMessage);
			break;
		case CONNECT:
			doConnect(session, commandMessage);
			break;
		}
	}

	private void doConnect(SocksSession session, CommandMessage commandMessage)
			throws IOException {

		ServerReply reply;
		Socket socket = null;
		int bindPort = 0;
		InetAddress remoteServerAddress = commandMessage.getInetAddress();
		int remoteServerPort = commandMessage.getPort();

		// set default bind address.
		byte[] defaultAddress = { 0, 0, 0, 0 };
		InetAddress bindAddress = InetAddress.getByAddress(defaultAddress);
		// DO connect
		try {
			// Connect directly.
			socket = new Socket(remoteServerAddress, remoteServerPort);
			bindAddress = socket.getLocalAddress();
			bindPort = socket.getLocalPort();
			reply = ServerReply.SUCCEEDED;

		} catch (IOException e) {
			if (e.getMessage().equals("Connection refused")) {
				reply = ServerReply.CONNECTION_REFUSED;
			} else if (e.getMessage().equals("Operation timed out")) {
				reply = ServerReply.TTL_EXPIRED;
			} else if (e.getMessage().equals("Network is unreachable")) {
				reply = ServerReply.NETWORK_UNREACHABLE;
			} else if (e.getMessage().equals("Connection timed out")) {
				reply = ServerReply.TTL_EXPIRED;
			} else {
				reply = ServerReply.GENERAL_SOCKS_SERVER_FAILURE;
			}
			logger.info("SESSION[{}] connect {} [{}] exception:{}", session.getId(),
					new InetSocketAddress(remoteServerAddress, remoteServerPort), reply, e.getMessage());
		}

		CommandResponseMessage responseMessage = new CommandResponseMessage(reply, bindAddress, bindPort);
		session.write(responseMessage);
		if (reply != ServerReply.SUCCEEDED) {
			session.close();
			return;
		}

		Pipe pipe = new SocketPipe(session.getSocket(), socket);
		pipe.setName("SESSION[" + session.getId() + "]");
		pipe.start(); // This method will build tow thread to run tow internal pipes.

		// wait for pipe exit.
		while (pipe.isRunning()) {
			try {
				Thread.sleep(idleTime);
			} catch (InterruptedException e) {
				pipe.stop();
				session.close();
				logger.info("SESSION[{}] closed", session.getId());
			}
		}

	}

	private void doBind(SocksSession session, CommandMessage commandMessage)
			throws IOException {
		ServerSocket serverSocket = new ServerSocket(commandMessage.getPort());
		int bindPort = serverSocket.getLocalPort();
		logger.info("Create TCP server bind at {} for session[{}]", serverSocket.getLocalSocketAddress(), session.getId());
		session.write(new CommandResponseMessage(ServerReply.SUCCEEDED, serverSocket.getInetAddress(), bindPort));

		Socket socket = serverSocket.accept();
		session.write(new CommandResponseMessage(ServerReply.SUCCEEDED, socket.getLocalAddress(), socket.getLocalPort()));

		Pipe pipe = new SocketPipe(session.getSocket(), socket);
		pipe.start();

		// wait for pipe exit.
		while (pipe.isRunning()) {
			try {
				Thread.sleep(idleTime);
			} catch (InterruptedException e) {
				pipe.stop();
				session.close();
				logger.info("Session[{}] closed", session.getId());
			}
		}
		serverSocket.close();
		// throw new NotImplementException("Not implement BIND command");
	}

	public void setSession(SocksSession session) {
		this.session = session;
	}

	@Override
	public void run() {
		try {
			handle(session);
		} catch (Exception e) {
			logger.error(e. getMessage(), e);
		} finally {
			session.close();
		}
	}
}
