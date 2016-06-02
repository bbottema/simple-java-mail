package sockslib.server.io;

interface PipeListener {

  void onStop(Pipe pipe);

  void onError(Exception exception);

}
