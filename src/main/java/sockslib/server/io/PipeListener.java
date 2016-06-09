

package sockslib.server.io;


public interface PipeListener {


  void onStart(Pipe pipe);


  void onStop(Pipe pipe);


  void onTransfer(Pipe pipe, byte[] buffer, int bufferLength);


  void onError(Pipe pipe, Exception exception);

}
