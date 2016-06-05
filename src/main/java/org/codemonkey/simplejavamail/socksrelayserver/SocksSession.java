package org.codemonkey.simplejavamail.socksrelayserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;

class SocksSession {

	private static final Logger logger = LoggerFactory.getLogger(SocksSession.class);

	private static int nextSessionId = 0;

	private final Socket socket;

	private final long id = ++nextSessionId;

	private InputStream inputStream;

	private OutputStream outputStream;

	private final SocketAddress clientAddress;

	public SocksSession(Socket socket) {
		if (!socket.isConnected()) {
			throw new IllegalArgumentException("Socket should be a connected socket");
		}
		this.socket = socket;
		try {
			inputStream = this.socket.getInputStream();
			outputStream = this.socket.getOutputStream();
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
		clientAddress = socket.getRemoteSocketAddress();
	}

	public Socket getSocket() {
		return socket;
	}

	public void write(byte[] bytes)
			throws IOException {
		outputStream.write(bytes, 0, bytes.length);
		outputStream.flush();
	}

	public long getId() {
		return id;
	}

	public void close() {
		try {
			if (inputStream != null) {
				inputStream.close();
			}
			if (outputStream != null) {
				outputStream.close();
			}
			if (socket != null && !socket.isClosed()) {
				socket.close();
			}
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
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
