

package sockslib.server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import static sockslib.utils.Util.checkNotNull;


public class SocketPipe implements Pipe {


  protected static final Logger logger = LoggerFactory.getLogger(SocketPipe.class);

  public static final String INPUT_PIPE_NAME = "INPUT_PIPE";
  public static final String OUTPUT_PIPE_NAME = "OUTPUT_PIPE";
  public static final String ATTR_SOURCE_SOCKET = "SOURCE_SOCKET";
  public static final String ATTR_DESTINATION_SOCKET = "DESTINATION_SOCKET";
  public static final String ATTR_PARENT_PIPE = "PARENT_PIPE";


  private Pipe pipe1;


  private Pipe pipe2;


  private Socket socket1;


  private Socket socket2;

  private String name;

  private Map<String, Object> attributes = new HashMap<>();


  private boolean running = false;

  private PipeListener listener = new PipeListenerImp();


  public SocketPipe(Socket socket1, Socket socket2) throws IOException {
    this.socket1 = checkNotNull(socket1, "Argument [socks1] may not be null");
    this.socket2 = checkNotNull(socket2, "Argument [socks1] may not be null");
    pipe1 = new StreamPipe(socket1.getInputStream(), socket2.getOutputStream(), OUTPUT_PIPE_NAME);
    pipe1.setAttribute(ATTR_SOURCE_SOCKET, socket1);
    pipe1.setAttribute(ATTR_DESTINATION_SOCKET, socket2);
    pipe2 = new StreamPipe(socket2.getInputStream(), socket1.getOutputStream(), INPUT_PIPE_NAME);
    pipe2.setAttribute(ATTR_SOURCE_SOCKET, socket2);
    pipe2.setAttribute(ATTR_DESTINATION_SOCKET, socket1);

    pipe1.addPipeListener(listener);
    pipe2.addPipeListener(listener);
    pipe1.setAttribute(ATTR_PARENT_PIPE, this);
    pipe2.setAttribute(ATTR_PARENT_PIPE, this);
  }

  @Override
  public boolean start() {
    running = pipe1.start() && pipe2.start();
    return running;
  }

  @Override
  public boolean stop() {
    if (running) {
      pipe1.stop();
      pipe2.stop();
      if (!pipe1.isRunning() && !pipe2.isRunning()) {
        running = false;
      }
    }
    return running;
  }

  @Override
  public boolean close() {
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
      return true;
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
    }
    return false;
  }

  @Override
  public void setBufferSize(int bufferSize) {
    pipe1.setBufferSize(bufferSize);
    pipe2.setBufferSize(bufferSize);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void addPipeListener(PipeListener pipeListener) {
    pipe1.addPipeListener(pipeListener);
    pipe2.addPipeListener(pipeListener);
  }

  @Override
  public void removePipeListener(PipeListener pipeListener) {

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

  private class PipeListenerImp implements PipeListener {

    @Override
    public void onStop(Pipe pipe) {
      StreamPipe streamPipe = (StreamPipe) pipe;
      logger.trace("Pipe[{}] stopped", streamPipe.getName());
      close();
    }

    @Override
    public void onStart(Pipe pipe) {
      // TODO Auto-generated method stub

    }

    @Override
    public void onTransfer(Pipe pipe, byte[] buffer, int bufferLength) {
    }

    @Override
    public void onError(Pipe pipe, Exception exception) {
      logger.info("{} {}", name, exception.getMessage());
    }

  }


}
