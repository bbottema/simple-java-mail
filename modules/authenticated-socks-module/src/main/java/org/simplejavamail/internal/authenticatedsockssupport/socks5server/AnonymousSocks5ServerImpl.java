package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @see AnonymousSocks5Server
 */
public class AnonymousSocks5ServerImpl implements AnonymousSocks5Server {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnonymousSocks5Server.class);
	
	private final Socks5Bridge socks5Bridge;
	private final int proxyBridgePort;
	private final Object lifecycleMonitor = new Object();
	
	@Nullable private volatile ExecutorService threadPool;
	@Nullable private volatile ServerSocket serverSocket;
	@Nullable private volatile Thread listenerThread;
	private volatile boolean stopping = false;
	private volatile boolean running = false;
	
	public AnonymousSocks5ServerImpl(final Socks5Bridge socks5Bridge, final int proxyBridgePort) {
		this.socks5Bridge = socks5Bridge;
		this.proxyBridgePort = proxyBridgePort;
	}
	
	/**
	 * @see AnonymousSocks5Server#start()
	 */
	@Override
	public void start() {
		synchronized (lifecycleMonitor) {
			if (running || stopping) {
				throw new IllegalStateException("server already running or stopping!");
			}

			final ExecutorService preparedThreadPool = Executors.newFixedThreadPool(100);
			final ServerSocket preparedServerSocket;
			try {
				preparedServerSocket = prepareServerSocket();
			} catch (final RuntimeException e) {
				preparedThreadPool.shutdownNow();
				throw e;
			}

			threadPool = preparedThreadPool;
			serverSocket = preparedServerSocket;
			running = true;
			stopping = false;
			listenerThread = new Thread(
					() -> runServer(preparedServerSocket, preparedThreadPool),
					"simple-java-mail SOCKS5 bridge " + preparedServerSocket.getLocalPort());
			listenerThread.start();
		}
	}

	private ServerSocket prepareServerSocket() {
		ServerSocket preparedServerSocket = null;
		try {
			preparedServerSocket = new ServerSocket();
			preparedServerSocket.setReuseAddress(true);
			preparedServerSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), proxyBridgePort));
			return preparedServerSocket;
		} catch (final IOException e) {
			if (preparedServerSocket != null) {
				closeAfterFailedStart(preparedServerSocket);
			}
			throw new SocksException("error preparing socks5bridge server for authenticated proxy session", e);
		}
	}
	
	@Override
	public void stop() {
		final ServerSocket serverSocketToClose;
		final Thread listenerThreadToJoin;
		synchronized (lifecycleMonitor) {
			if (!running) {
				return;
			}
			stopping = true;
			serverSocketToClose = serverSocket;
			listenerThreadToJoin = listenerThread;
		}

		try {
			if (serverSocketToClose != null) {
				serverSocketToClose.close();
			}
		} catch (final IOException e) {
			throw new SocksException(e.getMessage(), e);
		}

		if (listenerThreadToJoin != null && listenerThreadToJoin != Thread.currentThread()) {
			try {
				listenerThreadToJoin.join();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new SocksException("interrupted while stopping socks5bridge server", e);
			}
		}
	}
	
	@Override
	public void run() {
		final ServerSocket activeServerSocket = serverSocket;
		final ExecutorService activeThreadPool = threadPool;
		if (activeServerSocket == null || activeThreadPool == null) {
			throw new IllegalStateException("server has not been started");
		}
		runServer(activeServerSocket, activeThreadPool);
	}

	private void runServer(final ServerSocket activeServerSocket, final ExecutorService activeThreadPool) {
		boolean crashed = false;
		LOGGER.info("Starting proxy server at loopback:{}", activeServerSocket.getLocalPort());
		try {
			while (!activeServerSocket.isClosed()) {
				LOGGER.info("waiting for new connection...");
				@SuppressWarnings("SocketOpenedButNotSafelyClosed") // socket is closed elsewhere
				final Socket socket = activeServerSocket.accept();
				socket.setSoTimeout(10000);
				activeThreadPool.execute(new Socks5Handler(new SocksSession(socket), socks5Bridge));
			}
		} catch (final IOException e) {
			if (!activeServerSocket.isClosed()) {
				crashed = true;
				LOGGER.error("Authenticated SOCKS proxy bridge crashed", e);
			}
		} finally {
			LOGGER.debug("shutting down proxy bridge listener...");
			if (crashed) {
				activeThreadPool.shutdownNow();
			} else {
				// Existing pooled SMTP connections keep using their accepted bridge sockets until those transports close.
				activeThreadPool.shutdown();
			}
			synchronized (lifecycleMonitor) {
				if (serverSocket == activeServerSocket) {
					serverSocket = null;
					threadPool = null;
					listenerThread = null;
					running = false;
					stopping = false;
				}
			}
		}
	}

	private static void closeAfterFailedStart(final ServerSocket preparedServerSocket) {
		try {
			preparedServerSocket.close();
		} catch (final IOException ignored) {
			// Preserve the bind failure, which is the useful cause for the caller.
		}
	}

	@Override
	public boolean isStopping() {
		return stopping;
	}
	
	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getLocalPort() {
		final ServerSocket activeServerSocket = serverSocket;
		if (activeServerSocket == null) {
			return -1;
		}
		return activeServerSocket.getLocalPort();
	}

	@Nullable
	InetAddress getLocalAddress() {
		final ServerSocket activeServerSocket = serverSocket;
		return activeServerSocket == null ? null : activeServerSocket.getInetAddress();
	}
}
