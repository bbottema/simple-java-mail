package org.codemonkey.simplejavamail.internal.socks.socks5server;

import org.codemonkey.simplejavamail.internal.socks.common.Socks5Bridge;
import org.codemonkey.simplejavamail.internal.socks.common.SocksException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SOCKS server that accepts anonymous connections from JavaMail.
 * <p>
 * Java Mail only support anonymous SOCKS proxies; in order to support authenticated proxies, we need to create a man-in-the-middle: which
 * is the BasicSocksProxyServer.
 */
public class AnonymousSocks5Server implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AnonymousSocks5Server.class);

	private final Socks5Bridge socks5Bridge;
	private final ExecutorService threadPool = Executors.newFixedThreadPool(100);
	private final ServerSocket serverSocket;
	private boolean stop = false;

	public AnonymousSocks5Server(Socks5Bridge socks5Bridge, int proxyBridgePort) {
		this.socks5Bridge = socks5Bridge;
		try {
			serverSocket = new ServerSocket(proxyBridgePort);
		} catch (IOException e) {
			throw new SocksException(e.getMessage(), e);
		}
	}

	public void start() {
		new Thread(this).start();
	}

	public void stop() {
		stop = true;
		try {
			serverSocket.close();
		} catch (IOException e) {
			throw new SocksException(e.getMessage(), e);
		}
	}

	@Override
	public void run() {
		LOGGER.info("Starting proxy server at port {}", serverSocket.getLocalPort());
		while (!stop) {
			try {
				LOGGER.info("waiting for new connection...");
				Socket socket = serverSocket.accept();
				socket.setSoTimeout(10000);
				threadPool.execute(new Socks5Handler(new SocksSession(socket), socks5Bridge));
			} catch (IOException e) {
				if (e.getMessage().equals("socket closed")) {
					LOGGER.debug("socket closed");
				} else {
					throw new SocksException("server crashed...", e);
				}
			}
		}
		LOGGER.debug("shutting down...");
		threadPool.shutdownNow();
	}
}