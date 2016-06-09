

package sockslib.server.io;

public interface Pipe {


  boolean start();


  boolean stop();


  boolean close();

  void setBufferSize(int bufferSize);


  boolean isRunning();


  void addPipeListener(PipeListener pipeListener);


  void removePipeListener(PipeListener pipeListener);


  String getName();


  void setName(String name);


  void setAttribute(String name, Object value);

}
