package org.codemonkey.simplejavamail.internal.socks.socksrelayserver;

import org.codemonkey.simplejavamail.ProxyConfig;
import org.codemonkey.simplejavamail.internal.socks.socksclient.ProxyCredentials;
import org.codemonkey.simplejavamail.internal.socks.socksclient.Socks5;
import org.codemonkey.simplejavamail.internal.socks.socksclient.SocksSocket;
import org.codemonkey.simplejavamail.internal.socks.socksrelayserver.io.SocketPipe;
import org.codemonkey.simplejavamail.internal.socks.socksrelayserver.msg.CommandMessage;
import org.codemonkey.simplejavamail.internal.socks.socksrelayserver.msg.CommandResponseMessage;
import org.codemonkey.simplejavamail.internal.socks.socksrelayserver.msg.MethodSelectionMessage;
import org.codemonkey.simplejavamail.internal.socks.socksrelayserver.msg.ServerReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Socks5Handler implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(Socks5Handler.class);
	private static final Logger FRIENDLY_LOGGER = LoggerFactory.getLogger("socksrelay");
	private static final byte[] METHOD_SELECTION_RESPONSE = { (byte) 0x5, (byte) 0x00 };
	private static final int CONNECT_COMMAND = 0x01;

	public static final int VERSION = 0x5;

	private final SocksSession session;
	private final ProxyConfig proxyConfig;

	public Socks5Handler(SocksSession session, ProxyConfig proxyConfig) {
		this.session = session;
		this.proxyConfig = proxyConfig;
	}

	@Override
	public void run() {
		try {
			handle(session, proxyConfig);
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		} finally {
			session.close();
		}
	}

	private void handle(SocksSession session, ProxyConfig proxyConfig)
			throws IOException {
		if (MethodSelectionMessage.readVersion(session.getInputStream()) != VERSION) {
			throw new RuntimeException("Protocol error");
		}

		LOGGER.debug("SESSION[{}]", session.getId());
		// send select method.
		session.write(METHOD_SELECTION_RESPONSE);

		CommandMessage commandMessage = new CommandMessage();
		commandMessage.read(session.getInputStream());

		// If there is a SOCKS exception in command message, It will send a right response to client.
		if (commandMessage.hasSocksException()) {
			ServerReply serverReply = commandMessage.getSocksServerReplyException().getServerReply();
			session.write(CommandResponseMessage.getBytes(serverReply));
			LOGGER.debug("SESSION[{}] will close, because {}", session.getId(), serverReply);
			return;
		}

		if (commandMessage.getCommand() != CONNECT_COMMAND) {
			throw new RuntimeException("Only CONNECT command is supported");
		}
		doConnect(session, commandMessage, proxyConfig);
	}

	private void doConnect(SocksSession session, CommandMessage commandMessage, ProxyConfig proxyConfig)
			throws IOException {
		ServerReply reply;
		Socket socket = null;
		int bindPort = 0;
		InetAddress targetServerAddress = commandMessage.getInetAddress();
		int targetServerPort = commandMessage.getPort();

		// set default bind address.
		InetAddress bindAddress = new InetSocketAddress(0).getAddress();
		// DO connect
		try {
			socket = connectToRemoteProxy(proxyConfig, targetServerAddress, targetServerPort);
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
			InetSocketAddress remoteAddress = new InetSocketAddress(targetServerAddress, targetServerPort);

			if (e.getMessage().equals("Permission denied: connect")) {
				String msg = "Permission denied - unable to establish outbound connection to proxy. Perhaps blocked by a firewall?";
				LOGGER.info("connect {} [{}] exception: {}", session.getId(), remoteAddress, msg);
				FRIENDLY_LOGGER.error("connecting to {}: {}", remoteAddress, msg);
			} else {
				LOGGER.info("SESSION[{}] connect {} [{}] exception: {}", session.getId(), remoteAddress, reply, e.getMessage());
			}
		}

		session.write(CommandResponseMessage.getBytes(reply, bindAddress, bindPort));

		if (reply != ServerReply.SUCCEEDED) {
			session.close();
			return;
		}

		SocketPipe pipe = new SocketPipe(session.getSocket(), socket);
		pipe.setName("SESSION[" + session.getId() + "]");
		pipe.start(); // This method will build tow thread to run tow internal pipes.

		// wait for pipe exit.
		while (pipe.isRunning()) {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				pipe.stop();
				session.close();
				LOGGER.info("SESSION[{}] closed from", session.getId(), session.getClientAddress());
			}
		}

	}

	private Socket connectToRemoteProxy(ProxyConfig proxyConfig, InetAddress remoteServerAddress, int remoteServerPort)
			throws IOException {
		FRIENDLY_LOGGER.info("SESSION[{}] bridging to remote proxy {}:{} (username: {})", session.getId(), proxyConfig.getRemoteProxyHost(),
				proxyConfig.getRemoteProxyPort(), proxyConfig.getUsername());
		Socks5 proxyAuth = new Socks5(new InetSocketAddress(proxyConfig.getRemoteProxyHost(), proxyConfig.getRemoteProxyPort()));
		proxyAuth.setCredentials(new ProxyCredentials(proxyConfig.getUsername(), proxyConfig.getPassword()));
		Socket socketAuth = new SocksSocket(proxyAuth);
		// refactor to: new SocksSocket(proxy1, new InetSocketAddress("whois.internic.net", 43))
		socketAuth.connect(new InetSocketAddress(remoteServerAddress, remoteServerPort)); // refactor out (see line above)
		return socketAuth;
		//		return new Socket(remoteServerAddress, remoteServerPort);
	}

}
