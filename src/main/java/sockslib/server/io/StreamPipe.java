

package sockslib.server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sockslib.utils.Util.checkNotNull;

public class StreamPipe implements Runnable, Pipe {


  protected static final Logger logger = LoggerFactory.getLogger(StreamPipe.class);


  private static final int BUFFER_SIZE = 1024 * 1024 * 5;

  private Map<String, Object> attributes = new HashMap<>();


  private List<PipeListener> pipeListeners;


  private InputStream source;


  private OutputStream destination;


  private int bufferSize = BUFFER_SIZE;


  private Thread runningThread;


  private boolean running = false;


  private String name;

  private boolean daemon = false;



  public StreamPipe(InputStream source, OutputStream destination) {
    this(source, destination, null);
  }


  public StreamPipe(InputStream source, OutputStream destination,  String name) {
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
      runningThread.setDaemon(daemon);
      runningThread.start();
      for (PipeListener listener : pipeListeners) {
        listener.onStart(this);
      }
      return true;
    }
    return false;
  }


  @Override
  public boolean stop() {
    if (running) { // if the pipe is working, stop it.
      running = false;
      if (runningThread != null) {
        runningThread.interrupt();
      }
      for (int i = 0; i < pipeListeners.size(); i++) {
        PipeListener listener = pipeListeners.get(i);
        listener.onStop(this);
      }
      return true;
    }
    return false;
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


  protected int doTransfer(byte[] buffer) {

    int length = -1;
    try {
      length = source.read(buffer);
      if (length > 0) { // transfer the buffer destination output stream.
        destination.write(buffer, 0, length);
        destination.flush();
        for (int i = 0; i < pipeListeners.size(); i++) {
          pipeListeners.get(i).onTransfer(this, buffer, length);
        }
      }

    } catch (IOException e) {
      for (int i = 0; i < pipeListeners.size(); i++) {
        pipeListeners.get(i).onError(this, e);
      }
      stop();
    }

    return length;
  }

  @Override
  public boolean close() {
    stop();

    try {
      source.close();
      destination.close();
      return true;
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }
    return false;
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
  public void addPipeListener(PipeListener pipeListener) {
    pipeListeners.add(pipeListener);
  }

  @Override
  public void removePipeListener(PipeListener pipeListener) {
    pipeListeners.remove(pipeListener);
  }


  public List<PipeListener> getPipeListeners() {
    return pipeListeners;
  }


  public void setPipeListeners(List<PipeListener> pipeListeners) {
    this.pipeListeners = pipeListeners;
  }


  @Override
  public String getName() {
    return name;
  }


  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public void setAttribute(String name, Object value) {
    attributes.put(name, value);
  }

  public Thread getRunningThread() {
    return runningThread;
  }


  public boolean isDaemon() {
    return daemon;
  }

  public void setDaemon(boolean daemon) {
    this.daemon = daemon;
  }
}
