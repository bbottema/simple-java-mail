package sockslib.common.net;

interface OutputStreamMonitor {
  void onWrite(byte[] bytes);
}
