package org.simplejavamail.mailer.internal.socks.socks5server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The class {@link StreamPipe} represents a pipe that can transfer an {@link InputStream} to an {@link OutputStream}.
 */
class StreamPipe implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(StreamPipe.class);

	private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024 * 5;

	private final List<SocketPipe.PipeListener> pipeListeners;

	private final InputStream source;

	private final OutputStream destination;

	private Thread runningThread;

	private boolean running = false;

	private final String name;

	public StreamPipe(final InputStream source, final OutputStream destination, final String name) {
		this.source = source;
		this.destination = destination;
		pipeListeners = new ArrayList<>();
		this.name = name;
	}

	public boolean start() {
		if (!running) { // If the pipe is not running, run it.
			running = true;
			runningThread = new Thread(this);
			runningThread.setDaemon(false);
			runningThread.start();
			return true;
		}
		return false;
	}

	public void stop() {
		if (running) { // if the pipe is working, stop it.
			running = false;
			if (runningThread != null) {
				runningThread.interrupt();
			}
			for (final SocketPipe.PipeListener listener : new ArrayList<>(pipeListeners)) {
				listener.onStop(this);
			}
		}
	}

	@Override
	public void run() {
		final byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
		while (running) {
			final int size = doTransfer(buffer);
			if (size == -1) {
				stop();
			}
		}
	}

	private int doTransfer(final byte[] buffer) {
		int length = -1;
		try {
			length = source.read(buffer);
			if (length > 0) { // transfer the buffer destination output stream.
				destination.write(buffer, 0, length);
				destination.flush();
			}

		} catch (final IOException e) {
			synchronized (this) {
				for (final SocketPipe.PipeListener pipeListener : new ArrayList<>(pipeListeners)) {
					LOGGER.debug("{} {}", pipeListener.getName(), e.getMessage());
				}
			}
			stop();
		}

		return length;
	}

	public boolean isStopped() {
		return !running;
	}

	public synchronized void addPipeListener(final SocketPipe.PipeListener pipeListener) {
		pipeListeners.add(pipeListener);
	}

	public synchronized void removePipeListener(final SocketPipe.PipeListener pipeListener) {
		pipeListeners.remove(pipeListener);
	}

	public String getName() {
		return name;
	}

}
