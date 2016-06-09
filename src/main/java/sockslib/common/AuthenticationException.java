

package sockslib.common;

import java.net.SocketAddress;


public class AuthenticationException extends SocksException {


  private static final long serialVersionUID = 1L;

  private SocketAddress clientAddress;



  public AuthenticationException(String msg) {
    super(msg);
  }

  public AuthenticationException(String msg, SocketAddress clientFrom) {
    super(msg);
    this.clientAddress = clientFrom;
  }

  public SocketAddress getClientAddress() {
    return clientAddress;
  }

  public void setClientAddress(SocketAddress clientAddress) {
    this.clientAddress = clientAddress;
  }

}
