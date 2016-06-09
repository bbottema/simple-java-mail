

package sockslib.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sockslib.common.AddressType;
import sockslib.utils.SocksUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;


public class CommandReplyMessage implements SocksMessage {


  protected Logger logger = LoggerFactory.getLogger(CommandReplyMessage.class);


  private byte[] replyBytes;


  public CommandReplyMessage(byte[] replyBytes) {
    this.replyBytes = replyBytes;
  }


  public boolean isSuccess() {
    if (replyBytes.length < 10) {
      return false;
    }
    return replyBytes[1] == 0;
  }


  public InetAddress getIp() throws UnknownHostException {
    byte[] addressBytes = null;

    if (replyBytes[3] == AddressType.IPV4) {
      addressBytes = new byte[4];
    } else if (replyBytes[3] == AddressType.IPV6) {
      addressBytes = new byte[16];
    }

    System.arraycopy(replyBytes, 4, addressBytes, 0, addressBytes.length);
    return InetAddress.getByAddress(addressBytes);
  }


  public int getPort() {

    return SocksUtil.bytesToInt(replyBytes[replyBytes.length - 2], replyBytes[replyBytes.length
        - 1]);
  }


  public byte[] getReplyBytes() {
    return replyBytes;
  }


  public void setReplyBytes(byte[] replyBytes) {
    this.replyBytes = replyBytes;
  }


  public SocketAddress getSocketAddress() {
    try {
      return new InetSocketAddress(getIp(), getPort());
    } catch (UnknownHostException e) {
      logger.error(e.getMessage(), e);
    }
    return null;
  }

}
