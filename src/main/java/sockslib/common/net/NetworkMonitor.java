package sockslib.common.net;

public class NetworkMonitor implements SocketMonitor {

  private long receiveTCP = 0;
  private long sendTCP = 0;

  @Override
  public void onRead(byte[] bytes) {
    receiveTCP += bytes.length;
  }

  @Override
  public void onWrite(byte[] bytes) {
    sendTCP += bytes.length;
  }

  @Override
  public String toString() {
    long receiveUDP = 0;
    long sendUDP = 0;
    return "NetworkMonitor{" +
        "sendTCP=" + sendTCP +
        ", receiveTCP=" + receiveTCP +
        ", sendUDP=" + sendUDP +
        ", receiveUDP=" + receiveUDP +
        '}';
  }
}
