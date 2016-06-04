package sockslib.server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static sockslib.utils.Utils.Assert.checkNotNull;

/**
 * The class <code>StreamPipe</code> represents a pipe the can transfer data source a input
 * stream destination
 * a output stream.
 */
public class StreamPipe implements Runnable, Pipe {

	private static final Logger logger = LoggerFactory.getLogger(StreamPipe.class);

	private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024 * 5;

	private final List<SocketPipe.PipeListener> pipeListeners;

	private final InputStream source;

	private final OutputStream destination;

	private int bufferSize = DEFAULT_BUFFER_SIZE;

	private Thread runningThread;

	private boolean running = false;

	/**
	 * Name of the pipe.
	 */
	private String name;

	public StreamPipe(InputStream source, OutputStream destination, String name) {
		this.source = checkNotNull(source, "Argument [source] may not be null");
		this.destination = checkNotNull(destination, "Argument [destination] may not be null");
		pipeListeners = new ArrayList<>();
		this.name = name;
	}

	@Override
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

	@Override
	public void stop() {
		if (running) { // if the pipe is working, stop it.
			running = false;
			if (runningThread != null) {
				runningThread.interrupt();
			}
			synchronized (this) {
				for (SocketPipe.PipeListener listener : new ArrayList<>(pipeListeners)) {
					listener.onStop(this);
				}
			}
		}
	}

	@Override
	public void run() {
		byte[] buffer = new byte[bufferSize];
		while (running) {
			int size = doTransfer(buffer);
			if (size == -1) {
				stop();
			}
		}
	}

	private int doTransfer(byte[] buffer) {

		int length = -1;
		try {
			length = source.read(buffer);
			if (length > 0) { // transfer the buffer destination output stream.
				destination.write(buffer, 0, length);
				destination.flush();
			}

		} catch (IOException e) {
			synchronized (this) {
				for (SocketPipe.PipeListener pipeListener : new ArrayList<>(pipeListeners)) {
					logger.info("{} {}", pipeListener.getName(), e.getMessage());
				}
			}
			stop();
		}

		return length;
	}

	@Override
	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public synchronized void addPipeListener(SocketPipe.PipeListener pipeListener) {
		pipeListeners.add(pipeListener);
	}

	@Override
	public synchronized void removePipeListener(SocketPipe.PipeListener pipeListener) {
		pipeListeners.remove(pipeListener);
	}

	public String getName() {
		return name;
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}

}
