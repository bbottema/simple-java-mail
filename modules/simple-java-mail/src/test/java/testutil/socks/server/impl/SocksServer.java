package testutil.socks.server.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import testutil.socks.server.commons.Constants;
import testutil.socks.server.commons.Utils;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SocksServer {

	private static Logger LOGGER = LoggerFactory.getLogger(SocksServer.class);

	protected int port;
	protected boolean stopping = false;

	public int getPort() {
		return port;
	}

	public void start(int listenPort) {
		this.port = listenPort;
		new Thread(new ServerProcess()).start();
	}

	public void stop() {
		stopping = true;
	}

	private class ServerProcess implements Runnable {

		@Override
		public void run() {
			LOGGER.debug("SOCKS server started...");
			try {
				handleClients(port);
				LOGGER.debug("SOCKS server stopped...");
			} catch (IOException e) {
				LOGGER.debug("SOCKS server crashed...");
				Thread.currentThread().interrupt();
			}
		}

		protected void handleClients(int port) throws IOException {
			final ServerSocket listenSocket = new ServerSocket(port);
			listenSocket.setSoTimeout(Constants.LISTEN_TIMEOUT);
			SocksServer.this.port = listenSocket.getLocalPort();
			LOGGER.debug("SOCKS server listening at port: " + listenSocket.getLocalPort());

			while (!stopping) {
				synchronized (this) {
					if (!stopping) {
						handleNextClient(listenSocket);
					}
				}
			}
			try {
				listenSocket.close();
			} catch (IOException e) {
				// ignore
			}
		}

		private void handleNextClient(ServerSocket listenSocket) {
			try {
				final Socket clientSocket = listenSocket.accept();
				clientSocket.setSoTimeout(Constants.DEFAULT_SERVER_TIMEOUT);
				LOGGER.debug("Connection from : " + Utils.getSocketInfo(clientSocket));
				ProxyHandler proxy = new ProxyHandler(clientSocket);
				proxy.start();
			} catch (InterruptedIOException e) {
				//	This exception is thrown when accept timeout is expired
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
			}
		}
	}
}