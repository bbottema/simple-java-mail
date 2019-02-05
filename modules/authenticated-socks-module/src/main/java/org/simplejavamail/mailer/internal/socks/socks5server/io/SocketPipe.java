package org.simplejavamail.mailer.internal.socks.socks5server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;

/**
 * The class <code>SocketPipe</code> represents pipe that can transfer data from one socket to another socket. The tow socket should be
 * connected sockets. If any of the them occurred error the pipe will close all of them.
 */
public class SocketPipe {

	private static final Logger LOGGER = LoggerFactory.getLogger(SocketPipe.class);

	private static final String INPUT_PIPE_NAME = "INPUT_PIPE";
	private static final String OUTPUT_PIPE_NAME = "OUTPUT_PIPE";

	private final StreamPipe pipe1;

	private final StreamPipe pipe2;

	private final Socket socket1;

	private final Socket socket2;

	private String name;

	private boolean running = false;

	private final PipeListener listener = new PipeListener();

	/**
	 * Constructs SocketPipe instance by tow connected sockets.
	 */
	public SocketPipe(final Socket socket1, final Socket socket2)
			throws IOException {
		this.socket1 = socket1;
		this.socket2 = socket2;
		pipe1 = new StreamPipe(socket1.getInputStream(), socket2.getOutputStream(), OUTPUT_PIPE_NAME);
		pipe2 = new StreamPipe(socket2.getInputStream(), socket1.getOutputStream(), INPUT_PIPE_NAME);

		pipe1.addPipeListener(listener);
		pipe2.addPipeListener(listener);
	}

	public void start() {
		running = pipe1.start() && pipe2.start();
	}

	public void stop() {
		if (running) {
			pipe1.stop();
			pipe2.stop();
			if (pipe1.isStopped() && pipe2.isStopped()) {
				running = false;
			}
		}
	}

	private void close() {
		pipe2.removePipeListener(listener);
		pipe1.removePipeListener(listener);
		stop();
		try {
			if (socket1 != null && !socket1.isClosed()) {
				socket1.close();
			}
			if (socket2 != null && !socket2.isClosed()) {
				socket2.close();
			}
		} catch (final IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public boolean isRunning() {
		return running;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public class PipeListener {
		public String getName() {
			return name;
		}

		public void onStop(final StreamPipe streamPipe) {
			LOGGER.trace("Pipe[{}] stopped", streamPipe.getName());
			close();
		}

	}

}
