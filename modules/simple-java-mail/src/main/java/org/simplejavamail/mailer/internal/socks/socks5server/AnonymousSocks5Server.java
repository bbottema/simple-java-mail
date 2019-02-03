package org.simplejavamail.mailer.internal.socks.socks5server;

import org.simplejavamail.mailer.internal.socks.common.Socks5Bridge;
import org.simplejavamail.mailer.internal.socks.common.SocksException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SOCKS server that accepts anonymous connections from JavaMail.
 * <p>
 * Java Mail only support anonymous SOCKS proxies; in order to support authenticated proxies, we need to create a man-in-the-middle: which is the
 * {@link AnonymousSocks5Server}.
 */
public class AnonymousSocks5Server implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(AnonymousSocks5Server.class);

	private final Socks5Bridge socks5Bridge;
	private final int proxyBridgePort;

	private ExecutorService threadPool;
	private ServerSocket serverSocket;
	private boolean stopping = false;
	private boolean running = false;

	public AnonymousSocks5Server(final Socks5Bridge socks5Bridge, final int proxyBridgePort) {
		this.socks5Bridge = socks5Bridge;
		this.proxyBridgePort = proxyBridgePort;
	}

	/**
	 * Binds the port and starts a thread to listen to incoming proxy connections from JavaMail.
	 */
	public void start() {
		if (running) {
			throw new IllegalStateException("server already running!");
		}
		running = true;
		try {
			this.threadPool = Executors.newFixedThreadPool(100);
			this.serverSocket = new ServerSocket();
			this.serverSocket.setReuseAddress(true);
			this.serverSocket.bind(new InetSocketAddress(proxyBridgePort));
		} catch (final IOException e) {
			throw new SocksException("error preparing socks5bridge server for authenticated proxy session", e);
		}
		new Thread(this).start();
	}

	public void stop() {
		stopping = true;
		try {
			serverSocket.close();
		} catch (final IOException e) {
			throw new SocksException(e.getMessage(), e);
		}
	}

	@Override
	public void run() {
		LOGGER.info("Starting proxy server at port {}", serverSocket.getLocalPort());
		while (!stopping) {
			try {
				LOGGER.info("waiting for new connection...");
				@SuppressWarnings("SocketOpenedButNotSafelyClosed") // socket is closed elsewhere
				final Socket socket = serverSocket.accept();
				socket.setSoTimeout(10000);
				threadPool.execute(new Socks5Handler(new SocksSession(socket), socks5Bridge));
			} catch (final IOException e) {
				checkIoException(e);
			}
		}
		LOGGER.debug("shutting down...");
		threadPool.shutdownNow();
		running = false;
		stopping = false;
	}

	private void checkIoException(final Exception e) {
		if (e.getMessage().equalsIgnoreCase("socket closed")) {
			LOGGER.debug("socket closed");
		} else {
			running = false;
			stopping = false;
			throw new SocksException("server crashed...", e);
		}
	}

	public boolean isStopping() {
		return stopping;
	}

	public boolean isRunning() {
		return running;
	}
}