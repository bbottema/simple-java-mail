

package sockslib.common;

import java.io.IOException;


public class SocksException extends IOException {

  private static final String serverReplyMessage[] =
      {"General SOCKS server failure", "Connection not allowed by ruleset",
          "Network " + "unreachable", "Host unreachable", "Connection refused", "TTL expired",
          "Command not " + "supported", "Address type not supported"};

  public SocksException(String msg) {
    super(msg);
  }

  public static SocksException serverReplyException(byte reply) {
    int code = reply;
    code = code & 0xff;
    if (code < 0 || code > 0x08) {
      return new SocksException("Unknown reply");
    }
    code = code - 1;
    return new SocksException(serverReplyMessage[code]);
  }

}
