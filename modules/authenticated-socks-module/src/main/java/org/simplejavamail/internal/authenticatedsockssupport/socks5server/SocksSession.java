package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;

class SocksSession {

	private static final Logger LOGGER = LoggerFactory.getLogger(SocksSession.class);

	private static int nextSessionId = 0;

	private final Socket socket;

	private final long id = ++nextSessionId;

	private InputStream inputStream;

	private OutputStream outputStream;

	private final SocketAddress clientAddress;

	public SocksSession(final Socket socket) {
		if (!socket.isConnected()) {
			throw new IllegalArgumentException("Socket should be a connected socket");
		}
		this.socket = socket;
		try {
			inputStream = this.socket.getInputStream();
			outputStream = this.socket.getOutputStream();
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
		clientAddress = socket.getRemoteSocketAddress();
		LOGGER.info("SESSION[{}] opened from {}", getId(), clientAddress);
	}

	public Socket getSocket() {
		return socket;
	}

	public void write(final byte[] bytes)
			throws IOException {
		outputStream.write(bytes, 0, bytes.length);
		outputStream.flush();
	}

	public long getId() {
		return id;
	}

	public SocketAddress getClientAddress() {
		return clientAddress;
	}

	public void close() {
		try {
			if (inputStream != null) {
				inputStream.close();
			}
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
		try {
			if (outputStream != null) {
				outputStream.close();
			}
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
		try {
			if (socket != null && !socket.isClosed()) {
				LOGGER.trace("closing client socket");
				socket.close();
			}
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	@Override
	public String toString() {
		return "SESSION[" + id + "]" + "@" + clientAddress;
	}

}
